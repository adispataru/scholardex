package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Kind;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Status;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.DeclarationException;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.PublicationState;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.Refusal;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.Role;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.WorkspaceState;
import ro.uvt.pokedex.core.view.user.PrincipalAuthorDeclarationWorkspaceController;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two sides of a declaration of principal authorship, with the REAL security rules and the real page:
 * the researcher's JSON endpoints under the workspace, and the head's review page.
 */
@WebMvcTest({PrincipalAuthorDeclarationReviewController.class, PrincipalAuthorDeclarationWorkspaceController.class})
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class PrincipalAuthorDeclarationPagesContractTest {

    private static final String DENIED = "/custom-error?error=403";
    private static final String REVIEW = "/supervisor/declarations";
    private static final String WORKSPACE = "/user/workspace/publications/principal-author";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private PrincipalAuthorDeclarationService declarations;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.PublisherClaimReviewService publisherClaims;

    @BeforeEach
    void names() {
        when(userService.findDisplayLabels(anyCollection())).thenReturn(Map.of(
                "researcher@uvt.ro", "Ana Pop <researcher@uvt.ro>", "dean@uvt.ro", "Decan <dean@uvt.ro>"));
    }

    private static RequestPostProcessor as(String email, boolean head, UserRole... stored) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(new HashSet<>(Set.of(stored)));
        user.setSupervisorByPosition(head);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private static RequestPostProcessor head() {
        return as("dean@uvt.ro", true, UserRole.RESEARCHER);
    }

    private static RequestPostProcessor researcher() {
        return as("researcher@uvt.ro", false, UserRole.RESEARCHER);
    }

    private static PrincipalAuthorDeclaration declaration(String id, Status status) {
        PrincipalAuthorDeclaration declaration = new PrincipalAuthorDeclaration();
        declaration.setId(id);
        declaration.setStatus(status);
        declaration.setKind(Kind.EQUAL_CONTRIBUTION);
        declaration.setUserEmail("researcher@uvt.ro");
        declaration.setPublicationId("spub_1");
        declaration.setPublicationTitle("Emotions at <work>");
        declaration.setDoiNormalized("10.1000/emotions");
        declaration.setYear(2023);
        declaration.setEvidence("Footnote on the first page: equal contribution.");
        declaration.setEvidenceUrl("https://example.org/paper.pdf");
        if (status != Status.PENDING) {
            declaration.setDecidedBy("dean@uvt.ro");
            declaration.setDecidedAt(Instant.parse("2026-09-29T18:30:00Z"));
            declaration.setDecisionNote("Checked in the PDF.");
        }
        return declaration;
    }

    // ------------------------------------------------------------------ the head's page

    @Test
    void aHeadSeesWhatWaitsAndWhatWasDecided() throws Exception {
        when(declarations.pendingFor(any())).thenReturn(List.of(declaration("d1", Status.PENDING)));
        when(declarations.decidedFor(any(), anyInt())).thenReturn(List.of(declaration("d2", Status.APPROVED)));

        String html = mockMvc.perform(get(REVIEW).with(head())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Ana Pop"));
        assertTrue(html.contains("Emotions at &lt;work&gt;"), "the title is text, never markup");
        assertTrue(html.contains("Footnote on the first page: equal contribution."));
        assertTrue(html.contains("https://doi.org/10.1000/emotions"));
        assertTrue(html.contains("https://example.org/paper.pdf"));
        assertTrue(html.contains("/supervisor/declarations/d1/approve"));
        assertTrue(html.contains("/supervisor/declarations/d1/reject"));
        assertTrue(html.contains("/supervisor/declarations/d2/revoke"), "an approval can be taken back");
        assertFalse(html.contains("/supervisor/declarations/d2/approve"));
        assertTrue(html.contains("29.09.2026 21:30"), "decided at, in the local time of the university");
        assertTrue(html.contains("Checked in the PDF."));
        assertTrue(html.contains("name=\"_csrf\""));
        assertFalse(html.contains("??"), "a message key did not resolve");
    }

    // ------------------------------------------------------------------ H143: publisher categories on the same page

    private static ro.uvt.pokedex.core.service.application.PublisherClaimReviewService.ClaimItem claimItem(
            String activityId, String status) {
        var standards = List.of(new ro.uvt.pokedex.core.service.application.PublisherCategoryFacade.StandardView(
                "PSIHOLOGIE_2026", "Psihologie", null, "NOT_LISTED", null, null));
        var request = new ro.uvt.pokedex.core.service.application.PublisherCategoryFacade.ClaimView(status,
                "A1 — minimum 25 de biblioteci universitare din UE/OCDE în WorldCat", "https://worldcat.org/<29>",
                Instant.parse("2026-10-01T08:00:00Z"), "PENDING".equals(status) ? null : "dean@uvt.ro",
                "PENDING".equals(status) ? null : Instant.parse("2026-10-02T08:00:00Z"),
                "PENDING".equals(status) ? null : "29 libraries, checked.");
        var record = new ro.uvt.pokedex.core.service.application.PublisherCategoryFacade.RecordView(
                activityId, "Editura <Proprie>", standards, request);
        return new ro.uvt.pokedex.core.service.application.PublisherClaimReviewService.ClaimItem(activityId,
                "researcher@uvt.ro", "Carte coordonată (Comisia 28, I17)", "Coordinated <book>", record);
    }

    @Test
    void aHeadSeesThePublisherCategoriesAskedForBesideTheDeclarations() throws Exception {
        when(declarations.pendingFor(any())).thenReturn(List.of());
        when(declarations.decidedFor(any(), anyInt())).thenReturn(List.of());
        when(publisherClaims.pendingFor(any())).thenReturn(List.of(claimItem("a1", "PENDING")));
        when(publisherClaims.decidedFor(any(), anyInt())).thenReturn(List.of(claimItem("a2", "APPROVED")));

        String html = mockMvc.perform(get(REVIEW).with(head())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("data-publisher-claim"));
        assertTrue(html.contains("Ana Pop"));
        assertTrue(html.contains("Coordinated &lt;book&gt;") && html.contains("Editura &lt;Proprie&gt;"), "text, never markup");
        assertTrue(html.contains("A1 — minimum 25 de biblioteci universitare din UE/OCDE în WorldCat"));
        assertTrue(html.contains("/supervisor/declarations/publisher-claims/a1/approve"));
        assertTrue(html.contains("/supervisor/declarations/publisher-claims/a1/reject"));
        assertTrue(html.contains("/supervisor/declarations/publisher-claims/a2/revoke"), "an approval can be taken back");
        assertTrue(html.contains("29 libraries, checked."));
        assertTrue(html.contains("02.10.2026 11:00"));
        assertFalse(html.contains("??"), "a message key did not resolve");
    }

    @Test
    void aDecisionOnAPublisherCategoryComesBackToItsSection() throws Exception {
        mockMvc.perform(post(REVIEW + "/publisher-claims/a1/approve").param("note", "ok").with(head()).with(csrf()))
                .andExpect(redirectedUrl(REVIEW + "#publisher-claims"))
                .andExpect(flash().attribute("doneKey", "supervisor.declarations.done.claimApproved"));
        verify(publisherClaims).approve(eq("a1"), any(), eq("ok"));

        when(publisherClaims.reject(eq("a1"), any(), any())).thenThrow(
                new ro.uvt.pokedex.core.service.application.PublisherClaimReviewService.ClaimRefused(
                        ro.uvt.pokedex.core.service.application.PublisherClaimReviewService.Refusal.NOTE_REQUIRED));
        mockMvc.perform(post(REVIEW + "/publisher-claims/a1/reject").with(head()).with(csrf()))
                .andExpect(flash().attribute("refusedKey", "supervisor.claims.refused.NOTE_REQUIRED"));

        mockMvc.perform(post(REVIEW + "/publisher-claims/a1/approve").with(researcher()).with(csrf()))
                .andExpect(redirectedUrl(DENIED));
    }

    @Test
    void everyPublisherCategoryOutcomeHasASentence() throws Exception {
        when(declarations.pendingFor(any())).thenReturn(List.of());
        when(declarations.decidedFor(any(), anyInt())).thenReturn(List.of());
        for (String done : List.of("claimApproved", "claimRejected")) {
            String html = mockMvc.perform(get(REVIEW).with(head()).flashAttr("doneKey", "supervisor.declarations.done." + done))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(html.contains("??"), done);
        }
        for (var refusal : ro.uvt.pokedex.core.service.application.PublisherClaimReviewService.Refusal.values()) {
            String html = mockMvc.perform(get(REVIEW).with(head()).flashAttr("refusedKey", "supervisor.claims.refused." + refusal.name()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(html.contains("??"), refusal.name());
        }
    }

    @Test
    void theEmptyPageSaysSo() throws Exception {
        when(declarations.pendingFor(any())).thenReturn(List.of());
        when(declarations.decidedFor(any(), anyInt())).thenReturn(List.of());

        String html = mockMvc.perform(get(REVIEW).with(as("admin@uvt.ro", false, UserRole.PLATFORM_ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertFalse(html.contains("data-declaration"));
        assertFalse(html.contains("??"));
    }

    @Test
    void aDecisionComesBackToThePageWithItsOutcome() throws Exception {
        when(declarations.approve(eq("d1"), any(), eq("fine"))).thenReturn(declaration("d1", Status.APPROVED));

        mockMvc.perform(post(REVIEW + "/d1/approve").param("note", "fine").with(head()).with(csrf()))
                .andExpect(redirectedUrl(REVIEW))
                .andExpect(flash().attribute("doneKey", "supervisor.declarations.done.approved"));

        when(declarations.reject(eq("d1"), any(), any())).thenThrow(new DeclarationException(Refusal.NOTE_REQUIRED));
        mockMvc.perform(post(REVIEW + "/d1/reject").with(head()).with(csrf()))
                .andExpect(redirectedUrl(REVIEW))
                .andExpect(flash().attribute("refusedKey", "supervisor.declarations.refused.NOTE_REQUIRED"));

        when(declarations.revoke(eq("d2"), any(), any())).thenThrow(new DeclarationException(Refusal.NOT_ALLOWED));
        mockMvc.perform(post(REVIEW + "/d2/revoke").param("note", "x").with(head()).with(csrf()))
                .andExpect(flash().attribute("refusedKey", "supervisor.declarations.refused.NOT_ALLOWED"));
    }

    @Test
    void everyOutcomeHasASentenceOnThePage() throws Exception {
        when(declarations.pendingFor(any())).thenReturn(List.of());
        when(declarations.decidedFor(any(), anyInt())).thenReturn(List.of());
        for (String done : List.of("approved", "rejected", "revoked")) {
            String html = mockMvc.perform(get(REVIEW).with(head())
                            .flashAttr("doneKey", "supervisor.declarations.done." + done))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(html.contains("??"), done);
        }
        for (Refusal refusal : List.of(Refusal.NOT_FOUND, Refusal.NOT_ALLOWED, Refusal.NOT_PENDING,
                Refusal.NOT_APPROVED, Refusal.NOTE_REQUIRED, Refusal.NOTE_TOO_LONG)) {
            String html = mockMvc.perform(get(REVIEW).with(head())
                            .flashAttr("refusedKey", "supervisor.declarations.refused." + refusal.name()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(html.contains("??"), refusal.name());
        }
    }

    @Test
    void whoHeadsNothingDoesNotReachThePageOrItsActions() throws Exception {
        mockMvc.perform(get(REVIEW).with(researcher()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(DENIED));
        mockMvc.perform(post(REVIEW + "/d1/approve").with(researcher()).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(DENIED));
        mockMvc.perform(get(REVIEW)).andExpect(status().is3xxRedirection());

        verify(declarations, never()).pendingFor(any());
        verify(declarations, never()).approve(any(), any(), any());
    }

    // ------------------------------------------------------------------ the researcher's endpoints

    @Test
    void theWorkspaceReadsTheRoleAndTheDeclarationOfEachPublication() throws Exception {
        when(declarations.stateFor("researcher@uvt.ro")).thenReturn(new WorkspaceState(Map.of(
                "spub_1", new PublicationState(Role.CO_AUTHOR, Status.REJECTED, Kind.EQUAL_CONTRIBUTION, "Not stated."))));

        mockMvc.perform(get(WORKSPACE + "/state").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byPublicationId.spub_1.role").value("CO_AUTHOR"))
                .andExpect(jsonPath("$.byPublicationId.spub_1.declaration").value("REJECTED"))
                .andExpect(jsonPath("$.byPublicationId.spub_1.decisionNote").value("Not stated."));
    }

    @Test
    void aResearcherDeclaresForThemselvesOnly() throws Exception {
        when(declarations.declare("researcher@uvt.ro", "spub_1", Kind.CORRESPONDING_AUTHOR,
                "Corresponding author line under the title.", null)).thenReturn(declaration("d1", Status.PENDING));

        mockMvc.perform(post(WORKSPACE).with(researcher()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"publicationId":"spub_1","kind":"CORRESPONDING_AUTHOR",
                                 "evidence":"Corresponding author line under the title.","userEmail":"somebody.else@uvt.ro"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // The signed-in person is the one declared for, whatever the body says.
        verify(declarations).declare(eq("researcher@uvt.ro"), eq("spub_1"), eq(Kind.CORRESPONDING_AUTHOR), any(), any());
    }

    @Test
    void aRefusalIsAnsweredWithItsCode() throws Exception {
        when(declarations.declare(any(), any(), any(), any(), any()))
                .thenThrow(new DeclarationException(Refusal.ALREADY_PRINCIPAL));
        mockMvc.perform(post(WORKSPACE).with(researcher()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicationId\":\"spub_1\",\"kind\":\"NOT_A_KIND\",\"evidence\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ALREADY_PRINCIPAL"));

        when(declarations.withdraw("researcher@uvt.ro", "spub_1"))
                .thenThrow(new DeclarationException(Refusal.NOTHING_TO_WITHDRAW));
        mockMvc.perform(post(WORKSPACE + "/withdraw").with(researcher()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicationId\":\"spub_1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOTHING_TO_WITHDRAW"));
    }

    @Test
    void withoutSigningInNothingIsDeclared() throws Exception {
        mockMvc.perform(get(WORKSPACE + "/state")).andExpect(status().is3xxRedirection());
        mockMvc.perform(post(WORKSPACE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicationId\":\"spub_1\"}")).andExpect(status().is3xxRedirection());

        verify(declarations, never()).declare(any(), any(), any(), any(), any());
    }
}
