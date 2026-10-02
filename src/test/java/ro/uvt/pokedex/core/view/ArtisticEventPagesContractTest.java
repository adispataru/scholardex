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
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.ArtisticEventExpertsAdminService;
import ro.uvt.pokedex.core.service.application.ArtisticEventReviewService;
import ro.uvt.pokedex.core.service.application.ArtisticEventSeedService;
import ro.uvt.pokedex.core.service.security.ArtisticEventAccessService;
import ro.uvt.pokedex.core.view.user.ArtisticEventReviewController;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

/** H142 slice 3 — the experts' page and the admin page that names them, with the REAL security rules. */
@WebMvcTest({ArtisticEventReviewController.class, AdminArtisticEventExpertsController.class})
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class ArtisticEventPagesContractTest {

    private static final String REVIEW = "/user/artistic-events/review";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private CustomUserDetailsService userDetailsService;
    @MockitoBean private UserService userService;
    @MockitoBean private ArtisticEventReviewService review;
    @MockitoBean(name = "artisticEventAccess") private ArtisticEventAccessService access;
    @MockitoBean private ArtisticEventExpertsAdminService experts;
    @MockitoBean private ArtisticEventSeedService seed;

    private static RequestPostProcessor as(String email, UserRole... roles) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(new HashSet<>(Set.of(roles)));
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private static ArtisticEventReviewService.ReviewPage page() {
        var entry = new ArtisticEventReviewService.QueueEntry("filarmonica banatul", "Filarmonica <Banatul>",
                List.of("Filarmonica <Banatul>"), Set.of("Muzică"), 3, 2, List.of(2023, 2024),
                List.of("https://filarmonica.ro/stagiune"), Map.of("Fișa de verificare: CS 1.1", 2),
                "artisticEvents.source.records", true, null);
        var own = new ArtisticEventReviewService.QueueEntry("gala ucmr", "Gala UCMR", List.of("Gala UCMR"), Set.of("Muzică"),
                1, 1, List.of(2024), List.of(), Map.of(), "artisticEvents.source.records", false, "artisticEvents.refused.own");
        var enescu = new ArtisticEventReviewService.EventView("e1", "Festivalul „George Enescu” (România)", List.of(),
                "Muzică", ArtisticEvent.Rank.INTERNATIONAL_TOP, null, null, "CNFIS_LIST", null, null, null, true);
        var decision = new ArtisticEventReviewService.DecisionView(Instant.parse("2026-10-03T08:00:00Z"), "dean@uvt.ro",
                "RANKED", "Filarmonica Banatul", null, ArtisticEvent.Rank.NATIONAL, "Stagiune", "e2", ArtisticEvent.Status.CONFIRMED);
        var meridian = new ArtisticEventReviewService.MergeTarget("e9", "Festivalul Internațional „Meridian”", "Muzică",
                ArtisticEvent.Rank.INTERNATIONAL);
        return new ArtisticEventReviewService.ReviewPage(List.of(entry, own), List.of(enescu), List.of(decision),
                List.of("Muzică"), false, List.of(meridian));
    }

    @Test
    void anExpertSeesTheNamesWaitingTheirCountsAndTheFormsToDecide() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        when(review.page(any(), any())).thenReturn(page());
        when(userService.findDisplayLabels(anyCollection())).thenReturn(Map.of("dean@uvt.ro", "Decan <dean@uvt.ro>"));

        String html = mockMvc.perform(get(REVIEW).with(as("dean@uvt.ro", UserRole.RESEARCHER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Filarmonica &lt;Banatul&gt;"), "a name is text, never markup");
        assertTrue(html.contains("https://filarmonica.ro/stagiune"));
        assertTrue(html.contains("Fișa de verificare: CS 1.1 (2)"));
        assertTrue(html.contains("/user/artistic-events/review/rank"));
        assertTrue(html.contains("formaction=\"/user/artistic-events/review/merge\""), "merge acts on the ticked names");
        assertTrue(html.contains("value=\"e9\"") && html.contains("Festivalul Internațional „Meridian”"),
                "the merge list offers every ranked event of the domain, not only the table's rows");
        assertTrue(html.contains("/user/artistic-events/review/reject"));
        assertTrue(html.contains("value=\"filarmonica banatul\""));
        assertTrue(html.contains("Unul dintre evenimentele dumneavoastră") || html.contains("One of your own events"));
        assertTrue(html.contains("/user/artistic-events/review/events/e1/edit"));
        assertTrue(html.contains("03.10.2026 11:00"), "decided at, in the university's time");
        assertTrue(html.contains("/user/artistic-events/review\""), "the sidebar offers the page");
        assertTrue(html.contains("name=\"_csrf\""));
        assertFalse(html.contains("??"), "a message key did not resolve");
    }

    @Test
    void someoneWhoRanksNothingIsTurnedAway() throws Exception {
        when(access.canReviewAny(any())).thenReturn(false);
        mockMvc.perform(get(REVIEW).with(as("ana@uvt.ro", UserRole.RESEARCHER)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post(REVIEW + "/reject").with(as("ana@uvt.ro", UserRole.RESEARCHER)).with(csrf())
                        .param("key", "x").param("note", "no"))
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void aRankIsPostedWithItsFormAndComesBackWithItsMessage() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        when(review.rank(any(), any(), any())).thenReturn(new ArtisticEventReviewService.Outcome(true, "artisticEvents.done.ranked"));
        mockMvc.perform(post(REVIEW + "/rank").with(as("dean@uvt.ro", UserRole.RESEARCHER)).with(csrf())
                        .param("keys", "filarmonica banatul").param("rank", "NATIONAL_TOP").param("basis", "TOP_INSTITUTION_ROMANIA")
                        .param("kind", "SEASON").param("country", "România").param("note", "Stagiunea"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(REVIEW + "#queue"))
                .andExpect(flash().attribute("doneKey", "artisticEvents.done.ranked"));
        verify(review).rank(eq(List.of("filarmonica banatul")), eq(new ArtisticEventReviewService.RankForm(
                ArtisticEvent.Rank.NATIONAL_TOP, ArtisticEvent.Kind.SEASON, "România", "TOP_INSTITUTION_ROMANIA",
                "Stagiunea", null)), any());
    }

    @Test
    void theTickedNamesAreMergedIntoTheChosenEvent() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        when(review.mergeAll(any(), any(), any())).thenReturn(new ArtisticEventReviewService.Outcome(true, "artisticEvents.done.mergedMany"));
        mockMvc.perform(post(REVIEW + "/merge").with(as("dean@uvt.ro", UserRole.RESEARCHER)).with(csrf())
                        .param("keys", "festivalul enescu", "festivalul george enescu").param("target", "e1")
                        .param("rank", "").param("basis", ""))
                .andExpect(redirectedUrl(REVIEW + "#queue"))
                .andExpect(flash().attribute("doneKey", "artisticEvents.done.mergedMany"));
        verify(review).mergeAll(eq(List.of("festivalul enescu", "festivalul george enescu")), eq("e1"), any());
    }

    @Test
    void anUnknownRankReachesTheServiceAsNoneAndIsRefusedThere() throws Exception {
        when(access.canReviewAny(any())).thenReturn(true);
        when(review.rank(any(), any(), any())).thenReturn(new ArtisticEventReviewService.Outcome(false, "artisticEvents.refused.rank"));
        mockMvc.perform(post(REVIEW + "/rank").with(as("dean@uvt.ro", UserRole.RESEARCHER)).with(csrf())
                        .param("keys", "x").param("rank", "BEST").param("basis", "NONSENSE"))
                .andExpect(flash().attribute("refusedKey", "artisticEvents.refused.rank"));
        verify(review).rank(eq(List.of("x")), eq(new ArtisticEventReviewService.RankForm(null, null, null, null, null, null)), any());
    }

    @Test
    void onlyAPlatformAdminNamesTheExperts() throws Exception {
        when(experts.page()).thenReturn(new ArtisticEventExpertsAdminService.Page(
                List.of(new ArtisticEventExpertsAdminService.DomainView("Muzică", Set.of("dep-music"), "critic@uvt.ro")),
                List.of(new ArtisticEventExpertsAdminService.DepartmentOption("dep-music", "Departamentul de Muzică — Facultatea de Muzică și Teatru"))));
        String html = mockMvc.perform(get("/admin/artistic-events/experts").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Departamentul de Muzică — Facultatea de Muzică și Teatru"));
        assertTrue(html.contains("critic@uvt.ro"));
        assertFalse(html.contains("??"));

        mockMvc.perform(get("/admin/artistic-events/experts").with(as("dean@uvt.ro", UserRole.RESEARCHER)))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/artistic-events/experts").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf())
                        .param("domain", "Muzică").param("departmentIds", "dep-music").param("expertEmails", "critic@uvt.ro"))
                .andExpect(redirectedUrl("/admin/artistic-events/experts"));
        verify(experts).save(eq("Muzică"), eq(List.of("dep-music")), eq("critic@uvt.ro"), eq("admin@uvt.ro"));
    }

    @Test
    void anAdminProposesTheEventsOfAnAnexa61TableAndReadsWhatCameOfIt() throws Exception {
        when(experts.page()).thenReturn(new ArtisticEventExpertsAdminService.Page(
                List.of(new ArtisticEventExpertsAdminService.DomainView("Muzică", Set.of(), "")), List.of()));
        var report = new ArtisticEventSeedService.SeedReport(427, 2, 150, 121,
                List.of("Festivalul <Meridian>", "Stagiunea Filarmonicii Tg. Mureș"));
        when(seed.seedFromAnexa61(any(), eq("FMT_Anexa6.1.xlsx"), eq("Muzică"), eq("admin@uvt.ro"))).thenReturn(report);
        MockMultipartFile file = new MockMultipartFile("file", "FMT_Anexa6.1.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/artistic-events/experts/seed").file(file).param("domain", "Muzică")
                        .with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(redirectedUrl("/admin/artistic-events/experts#seed"))
                .andExpect(flash().attribute("seedReport", report))
                .andExpect(flash().attribute("seedDomain", "Muzică"));

        String html = mockMvc.perform(get("/admin/artistic-events/experts").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN))
                        .flashAttr("seedReport", report).flashAttr("seedDomain", "Muzică"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("/admin/artistic-events/experts/seed"));
        assertTrue(html.contains("enctype=\"multipart/form-data\""));
        assertTrue(html.contains("427") && html.contains("121"));
        assertTrue(html.contains("Festivalul &lt;Meridian&gt;"), "a proposed name is text, never markup");
        assertFalse(html.contains("??"));
    }

    @Test
    void anEmptyOrUnreadableTableIsRefusedAndOnlyAnAdminMayUploadOne() throws Exception {
        mockMvc.perform(multipart("/admin/artistic-events/experts/seed")
                        .file(new MockMultipartFile("file", "empty.xlsx", "application/octet-stream", new byte[0]))
                        .param("domain", "Muzică").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(flash().attribute("seedErrorKey", "artisticEvents.experts.seed.missing"));

        when(seed.seedFromAnexa61(any(), any(), any(), any())).thenThrow(new java.io.IOException("not a zip"));
        mockMvc.perform(multipart("/admin/artistic-events/experts/seed")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "x".getBytes()))
                        .param("domain", "Muzică").with(as("admin@uvt.ro", UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(flash().attribute("seedErrorKey", "artisticEvents.experts.seed.unreadable"));

        mockMvc.perform(multipart("/admin/artistic-events/experts/seed")
                        .file(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", new byte[]{1}))
                        .param("domain", "Muzică").with(as("dean@uvt.ro", UserRole.RESEARCHER)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        verify(seed, never()).seedFromAnexa61(any(), eq("a.xlsx"), any(), any());
    }
}
