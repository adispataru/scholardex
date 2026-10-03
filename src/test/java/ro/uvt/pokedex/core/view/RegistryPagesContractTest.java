package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.ArtisticEventSeedService;
import ro.uvt.pokedex.core.service.application.RegistryExpertsAdminService;
import ro.uvt.pokedex.core.service.application.RegistryReviewService;
import ro.uvt.pokedex.core.service.security.RegistryAccessService;
import ro.uvt.pokedex.core.view.user.RegistryReviewController;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H142 slice 3, H144 — the experts' page of the registries and the admin page that names them, with the REAL security rules. */
@WebMvcTest({RegistryReviewController.class, AdminRegistryExpertsController.class})
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class RegistryPagesContractTest {

    private static final String REVIEW = "/user/registry/review";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private CustomUserDetailsService userDetailsService;
    @MockitoBean private UserService userService;
    @MockitoBean private RegistryReviewService review;
    @MockitoBean(name = "registryAccess") private RegistryAccessService access;
    @MockitoBean private RegistryExpertsAdminService experts;
    @MockitoBean private ArtisticEventSeedService seed;

    private static RequestPostProcessor as(String email, UserRole... roles) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(new HashSet<>(Set.of(roles)));
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private static List<RegistryReviewService.KindTab> tabs(int artistic, int conferences) {
        return List.of(new RegistryReviewService.KindTab(RegistryKind.ARTISTIC_EVENT, artistic),
                new RegistryReviewService.KindTab(RegistryKind.SCIENTIFIC_EVENT, conferences),
                new RegistryReviewService.KindTab(RegistryKind.ORGANIZATION, 0),
                new RegistryReviewService.KindTab(RegistryKind.AWARD, 0));
    }

    private static RegistryReviewService.ReviewPage artisticPage() {
        var entry = new RegistryReviewService.QueueEntry("filarmonica banatul", "Filarmonica <Banatul>",
                List.of("Filarmonica <Banatul>"), Set.of("Muzică"), 3, 2, List.of(2023, 2024),
                List.of("https://filarmonica.ro/stagiune"), Map.of("Fișa de verificare: CS 1.1", 2),
                "registry.source.records", true, null);
        var own = new RegistryReviewService.QueueEntry("gala ucmr", "Gala UCMR", List.of("Gala UCMR"), Set.of("Muzică"),
                1, 1, List.of(2024), List.of(), Map.of(), "registry.source.records", false, "registry.refused.own");
        var enescu = new RegistryReviewService.ItemView("e1", "Festivalul „George Enescu” (România)", List.of(),
                "Muzică", "INTERNATIONAL_TOP", null, List.of(), null, "CNFIS_LIST", null, null, null, true);
        var decision = new RegistryReviewService.DecisionView(Instant.parse("2026-10-03T08:00:00Z"), "dean@uvt.ro",
                "RANKED", "Filarmonica Banatul", null, "NATIONAL", "Stagiune", "e2", RegistryStatus.CONFIRMED);
        var meridian = new RegistryReviewService.MergeTarget("e9", "Festivalul Internațional „Meridian”", "Muzică", "INTERNATIONAL");
        return new RegistryReviewService.ReviewPage(RegistryKind.ARTISTIC_EVENT, tabs(2, 1), List.of(entry, own), List.of(enescu),
                List.of(decision), List.of("Muzică"), false, List.of(meridian));
    }

    @Test
    void anExpertSeesTheNamesWaitingTheirCountsAndTheFormsToDecide() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        when(review.page(eq(RegistryKind.ARTISTIC_EVENT), any(), any())).thenReturn(artisticPage());
        when(userService.findDisplayLabels(anyCollection())).thenReturn(Map.of("dean@uvt.ro", "Decan <dean@uvt.ro>"));

        String html = mockMvc.perform(get(REVIEW).with(as("dean@uvt.ro", UserRole.RESEARCHER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Filarmonica &lt;Banatul&gt;"), "a name is text, never markup");
        assertTrue(html.contains("https://filarmonica.ro/stagiune"));
        assertTrue(html.contains("Fișa de verificare: CS 1.1 (2)"));
        assertTrue(html.contains("/user/registry/review/ARTISTIC_EVENT/rank"));
        assertTrue(html.contains("formaction=\"/user/registry/review/ARTISTIC_EVENT/merge\""), "merge acts on the ticked names");
        assertTrue(html.contains("/user/registry/review/ARTISTIC_EVENT/reject"));
        assertTrue(html.contains("value=\"filarmonica banatul\""));
        assertTrue(html.contains("Unul dintre numele dumneavoastră") || html.contains("One of your own names"));
        assertTrue(html.contains("/user/registry/review/ARTISTIC_EVENT/items/e1/edit"));
        assertTrue(html.contains("value=\"e9\"") && html.contains("Festivalul Internațional „Meridian”"),
                "the merge list offers every ranked entry of the domain");
        assertTrue(html.contains("03.10.2026 11:00"), "decided at, in the university's time");
        assertTrue(html.contains("/user/registry/review?kind=SCIENTIFIC_EVENT"), "a tab per registry");
        assertTrue(html.contains("/user/registry/review\""), "the sidebar offers the page");
        assertTrue(html.contains("name=\"_csrf\""));
        assertFalse(html.contains("??"), "a message key did not resolve");
    }

    @Test
    void theConferenceTabAsksForComisia28sCriteria() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        var entry = new RegistryReviewService.QueueEntry("ecer 2024", "ECER 2024", List.of("ECER 2024"), Set.of("Științe ale educației"),
                1, 1, List.of(2024), List.of(), Map.of(), "registry.source.records", true, null);
        when(review.page(eq(RegistryKind.SCIENTIFIC_EVENT), any(), any())).thenReturn(new RegistryReviewService.ReviewPage(
                RegistryKind.SCIENTIFIC_EVENT, tabs(0, 1), List.of(entry), List.of(), List.of(), List.of("Științe ale educației"),
                false, List.of()));

        String html = mockMvc.perform(get(REVIEW).param("kind", "SCIENTIFIC_EVENT").with(as("dean@uvt.ro", UserRole.RESEARCHER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("/user/registry/review/SCIENTIFIC_EVENT/rank"));
        assertTrue(html.contains("name=\"criteria\" value=\"INTERNATIONAL_ORGANISER\""));
        assertTrue(html.contains("name=\"criteria\" value=\"SESSIONS_LANGUAGE\""));
        assertTrue(html.contains("value=\"COMISIA_28_CRITERIA\""));
        assertFalse(html.contains("INTERNATIONAL_TOP"), "a conference has no top level");
        assertFalse(html.contains("??"));
    }

    @Test
    void someoneWhoRanksNothingIsTurnedAway() throws Exception {
        when(access.canReviewAny(any())).thenReturn(false);
        mockMvc.perform(get(REVIEW).with(as("ana@uvt.ro", UserRole.RESEARCHER)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post(REVIEW + "/ARTISTIC_EVENT/reject").with(as("ana@uvt.ro", UserRole.RESEARCHER)).with(csrf())
                        .param("key", "x").param("note", "no"))
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void aRankIsPostedWithItsFormAndComesBackToItsTab() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        when(review.rank(any(), any(), any(), any())).thenReturn(new RegistryReviewService.Outcome(true, "registry.done.ranked"));
        mockMvc.perform(post(REVIEW + "/SCIENTIFIC_EVENT/rank").with(as("dean@uvt.ro", UserRole.RESEARCHER)).with(csrf())
                        .param("keys", "ecer 2024").param("level", "INTERNATIONAL").param("basis", "COMISIA_28_CRITERIA")
                        .param("category", "CONFERENCE").param("criteria", "INTERNATIONAL_ORGANISER", "PROCEEDINGS_LANGUAGE")
                        .param("country", "Cipru").param("note", "EERA"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(REVIEW + "?kind=SCIENTIFIC_EVENT#queue"))
                .andExpect(flash().attribute("doneKey", "registry.done.ranked"));
        verify(review).rank(eq(RegistryKind.SCIENTIFIC_EVENT), eq(List.of("ecer 2024")), eq(new RegistryReviewService.RankForm(
                "INTERNATIONAL", "CONFERENCE", List.of("INTERNATIONAL_ORGANISER", "PROCEEDINGS_LANGUAGE"), "Cipru",
                "COMISIA_28_CRITERIA", "EERA", null)), any());
    }

    @Test
    void anUnknownLevelReachesTheServiceAndIsRefusedThere() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        when(review.rank(any(), any(), any(), any())).thenReturn(new RegistryReviewService.Outcome(false, "registry.refused.rank"));
        mockMvc.perform(post(REVIEW + "/ARTISTIC_EVENT/rank").with(as("dean@uvt.ro", UserRole.RESEARCHER)).with(csrf())
                        .param("keys", "x").param("level", "BEST").param("basis", " "))
                .andExpect(flash().attribute("refusedKey", "registry.refused.rank"));
        verify(review).rank(eq(RegistryKind.ARTISTIC_EVENT), eq(List.of("x")),
                eq(new RegistryReviewService.RankForm("BEST", null, List.of(), null, null, null, null)), any());
    }

    @Test
    void theTickedNamesAreMergedIntoTheChosenEntry() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        when(review.mergeAll(any(), any(), any(), any())).thenReturn(new RegistryReviewService.Outcome(true, "registry.done.mergedMany"));
        mockMvc.perform(post(REVIEW + "/ARTISTIC_EVENT/merge").with(as("dean@uvt.ro", UserRole.RESEARCHER)).with(csrf())
                        .param("keys", "festivalul enescu", "festivalul george enescu").param("target", "e1")
                        .param("level", "").param("basis", ""))
                .andExpect(redirectedUrl(REVIEW + "?kind=ARTISTIC_EVENT#queue"))
                .andExpect(flash().attribute("doneKey", "registry.done.mergedMany"));
        verify(review).mergeAll(eq(RegistryKind.ARTISTIC_EVENT), eq(List.of("festivalul enescu", "festivalul george enescu")), eq("e1"), any());
    }

    @Test
    void onlyAPlatformAdminNamesTheExpertsAndAddsADomain() throws Exception {
        when(experts.page()).thenReturn(new RegistryExpertsAdminService.Page(
                List.of(new RegistryExpertsAdminService.DomainView("Muzică", Set.of("dep-music"), "critic@uvt.ro")),
                List.of(new RegistryExpertsAdminService.DepartmentOption("dep-music", "Departamentul de Muzică — Facultatea de Muzică și Teatru"))));
        String html = mockMvc.perform(get("/admin/registry/experts").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Departamentul de Muzică — Facultatea de Muzică și Teatru"));
        assertTrue(html.contains("critic@uvt.ro"));
        assertTrue(html.contains("/admin/registry/experts/domains"), "an admin can add a domain");
        assertFalse(html.contains("??"));

        mockMvc.perform(get("/admin/registry/experts").with(as("dean@uvt.ro", UserRole.RESEARCHER)))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/registry/experts").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf())
                        .param("domain", "Muzică").param("departmentIds", "dep-music").param("expertEmails", "critic@uvt.ro"))
                .andExpect(redirectedUrl("/admin/registry/experts"));
        verify(experts).save(eq("Muzică"), eq(List.of("dep-music")), eq("critic@uvt.ro"), eq("admin@uvt.ro"));

        when(experts.addDomain(eq("Științe  ale educației "), eq("admin@uvt.ro"))).thenReturn(Optional.of("Științe ale educației"));
        when(experts.addDomain(eq("Muzică"), eq("admin@uvt.ro"))).thenReturn(Optional.empty());
        mockMvc.perform(post("/admin/registry/experts/domains").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf())
                        .param("domain", "Științe  ale educației "))
                .andExpect(redirectedUrl("/admin/registry/experts"))
                .andExpect(flash().attribute("savedDomain", "Științe ale educației"));
        mockMvc.perform(post("/admin/registry/experts/domains").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf())
                        .param("domain", "Muzică"))
                .andExpect(flash().attribute("domainErrorKey", "registry.experts.domain.invalid"));
    }

    @Test
    void anAdminProposesTheEventsOfAnAnexa61TableAndReadsWhatCameOfIt() throws Exception {
        when(experts.page()).thenReturn(new RegistryExpertsAdminService.Page(
                List.of(new RegistryExpertsAdminService.DomainView("Muzică", Set.of(), "")), List.of()));
        var report = new ArtisticEventSeedService.SeedReport(427, 2, 150, 121,
                List.of("Festivalul <Meridian>", "Stagiunea Filarmonicii Tg. Mureș"));
        when(seed.seedFromAnexa61(any(), eq("FMT_Anexa6.1.xlsx"), eq("Muzică"), eq("admin@uvt.ro"))).thenReturn(report);
        MockMultipartFile file = new MockMultipartFile("file", "FMT_Anexa6.1.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/registry/experts/seed").file(file).param("domain", "Muzică")
                        .with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(redirectedUrl("/admin/registry/experts#seed"))
                .andExpect(flash().attribute("seedReport", report))
                .andExpect(flash().attribute("seedDomain", "Muzică"));

        String html = mockMvc.perform(get("/admin/registry/experts").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN))
                        .flashAttr("seedReport", report).flashAttr("seedDomain", "Muzică"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("/admin/registry/experts/seed"));
        assertTrue(html.contains("enctype=\"multipart/form-data\""));
        assertTrue(html.contains("427") && html.contains("121"));
        assertTrue(html.contains("Festivalul &lt;Meridian&gt;"), "a proposed name is text, never markup");
        assertFalse(html.contains("??"));
    }

    @Test
    void anEmptyOrUnreadableTableIsRefusedAndOnlyAnAdminMayUploadOne() throws Exception {
        mockMvc.perform(multipart("/admin/registry/experts/seed")
                        .file(new MockMultipartFile("file", "empty.xlsx", "application/octet-stream", new byte[0]))
                        .param("domain", "Muzică").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(flash().attribute("seedErrorKey", "registry.experts.seed.missing"));

        when(seed.seedFromAnexa61(any(), any(), any(), any())).thenThrow(new java.io.IOException("not a zip"));
        mockMvc.perform(multipart("/admin/registry/experts/seed")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "x".getBytes()))
                        .param("domain", "Muzică").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(flash().attribute("seedErrorKey", "registry.experts.seed.unreadable"));

        mockMvc.perform(multipart("/admin/registry/experts/seed")
                        .file(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", new byte[]{1}))
                        .param("domain", "Muzică").with(as("dean@uvt.ro", UserRole.RESEARCHER)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        verify(seed, never()).seedFromAnexa61(any(), eq("a.xlsx"), any(), any());
    }
}
