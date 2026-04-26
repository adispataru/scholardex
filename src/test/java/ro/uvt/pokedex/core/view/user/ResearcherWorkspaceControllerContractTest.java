package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.workspace.WorkspacePreferences;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.WorkspacePreferencesRepository;
import ro.uvt.pokedex.core.service.application.PublicationAuthorshipDecisionService;
import ro.uvt.pokedex.core.service.application.PublicationWizardFacade;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;
import ro.uvt.pokedex.core.service.application.model.PublicationAuthorshipReviewState;
import ro.uvt.pokedex.core.service.application.model.SuspiciousAuthorshipState;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearcherWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class ResearcherWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserPublicationFacade userPublicationFacade;
    @MockitoBean
    private UserActivityInstanceFacade userActivityInstanceFacade;
    @MockitoBean
    private UserScopusTaskFacade userScopusTaskFacade;
    @MockitoBean
    private UserReportFacade userReportFacade;
    @MockitoBean
    private WorkspacePreferencesRepository workspacePreferencesRepository;
    @MockitoBean
    private PublicationWizardFacade publicationWizardFacade;
    @MockitoBean
    private PublicationAuthorshipDecisionService publicationAuthorshipDecisionService;
    @MockitoBean
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @MockitoBean
    private ScholardexProjectionReadService scholardexProjectionReadService;

    @Test
    void workspaceTemplateUsesSharedSearchInputFragment() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/user/workspace.html"));

        assertTrue(template.contains("fragments :: search-input"));
        assertTrue(template.contains("id='ws-search-input'"));
        assertTrue(template.contains("kbdHint='/ or Ctrl+K'"));
    }

    @Test
    void workspacePublicationsJsonIncludesAuthorshipReviewState() throws Exception {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setTitle("Paper One");

        when(userPublicationFacade.buildWorkspacePublicationsView("u@uvt.ro"))
                .thenReturn(Optional.of(new UserPublicationsViewModel(
                        List.of(publication),
                        1,
                        Map.of(),
                        Map.of(),
                        Map.of("p1", new PublicationAuthorshipReviewState(
                                PublicationAuthorshipReviewState.Status.REJECTED,
                                "not mine",
                                Instant.parse("2026-04-16T12:00:00Z")
                        )),
                        Map.of("p1", new SuspiciousAuthorshipState(List.of(
                                new SuspiciousAuthorshipState.Flag(
                                        SuspiciousAuthorshipState.Code.NAME_MISMATCH,
                                        "name mismatch"
                                )
                        ))),
                        1,
                        1,
                        0,
                        0,
                        null,
                        List.of()
                )));

        mockMvc.perform(get("/user/workspace/publications").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorshipReviewStateByPublicationId.p1.status").value("REJECTED"))
                .andExpect(jsonPath("$.authorshipReviewStateByPublicationId.p1.reason").value("not mine"))
                .andExpect(jsonPath("$.suspiciousAuthorshipByPublicationId.p1.flags[0].code").value("NAME_MISMATCH"))
                .andExpect(jsonPath("$.pendingReviewCount").value(1))
                .andExpect(jsonPath("$.suspiciousPendingCount").value(1));
    }

    @Test
    void bulkConfirmAuthorshipEndpointReturnsMixedResult() throws Exception {
        PublicationAuthorshipDecision confirmed = new PublicationAuthorshipDecision();
        confirmed.setPublicationId("p1");
        confirmed.setStatus(PublicationAuthorshipDecision.Status.CONFIRMED);
        confirmed.setUpdatedAt(Instant.parse("2026-04-18T12:00:00Z"));

        when(publicationAuthorshipDecisionService.upsertBulkDecisions(
                eq("u@uvt.ro"),
                eq(List.of("p1", "p2")),
                eq(PublicationAuthorshipDecision.Status.CONFIRMED),
                eq("mine")))
                .thenReturn(new PublicationAuthorshipDecisionService.BulkDecisionResult(
                        Map.of("p1", confirmed),
                        List.of(new PublicationAuthorshipDecisionService.DecisionFailure("p2", "Only pending publications can be reviewed in bulk."))
                ));

        mockMvc.perform(post("/user/workspace/publications/authorship/bulk")
                        .with(authenticatedUser("u@uvt.ro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicationIds\":[\"p1\",\"p2\"],\"action\":\"CONFIRM\",\"reason\":\"mine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeededIds[0]").value("p1"))
                .andExpect(jsonPath("$.failures[0].publicationId").value("p2"))
                .andExpect(jsonPath("$.updatedStates[0].status").value("CONFIRMED"));
    }

    @Test
    void bulkAuthorshipEndpointReturnsConflictWhenAffiliationScopeIsUnconfirmed() throws Exception {
        when(publicationAuthorshipDecisionService.upsertBulkDecisions(
                eq("u@uvt.ro"),
                eq(List.of("p1")),
                eq(PublicationAuthorshipDecision.Status.REJECTED),
                eq("not mine")))
                .thenThrow(new IllegalStateException("Confirm your current and past affiliations in the profile tab before reviewing publication authorship."));

        mockMvc.perform(post("/user/workspace/publications/authorship/bulk")
                        .with(authenticatedUser("u@uvt.ro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicationIds\":[\"p1\"],\"action\":\"REJECT\",\"reason\":\"not mine\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].message").value("Confirm your current and past affiliations in the profile tab before reviewing publication authorship."));
    }

    @Test
    void confirmAuthorshipEndpointReturnsUpdatedState() throws Exception {
        PublicationAuthorshipDecision decision = new PublicationAuthorshipDecision();
        decision.setPublicationId("p1");
        decision.setStatus(PublicationAuthorshipDecision.Status.CONFIRMED);
        decision.setReason("mine");
        decision.setUpdatedAt(Instant.parse("2026-04-16T12:00:00Z"));

        when(publicationAuthorshipDecisionService.upsertDecision(
                eq("u@uvt.ro"), eq("p1"), eq(PublicationAuthorshipDecision.Status.CONFIRMED), eq("mine")))
                .thenReturn(decision);

        mockMvc.perform(post("/user/workspace/publications/p1/authorship/confirm")
                        .with(authenticatedUser("u@uvt.ro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"mine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value("p1"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reason").value("mine"));
    }

    @Test
    void rejectAuthorshipEndpointReturnsUpdatedState() throws Exception {
        PublicationAuthorshipDecision decision = new PublicationAuthorshipDecision();
        decision.setPublicationId("p1");
        decision.setStatus(PublicationAuthorshipDecision.Status.REJECTED);
        decision.setReason("not mine");
        decision.setUpdatedAt(Instant.parse("2026-04-16T12:00:00Z"));

        when(publicationAuthorshipDecisionService.upsertDecision(
                eq("u@uvt.ro"), eq("p1"), eq(PublicationAuthorshipDecision.Status.REJECTED), eq("not mine")))
                .thenReturn(decision);

        mockMvc.perform(post("/user/workspace/publications/p1/authorship/reject")
                        .with(authenticatedUser("u@uvt.ro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not mine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("not mine"));
    }

    @Test
    void confirmAuthorshipEndpointReturnsConflictWhenAffiliationScopeIsUnconfirmed() throws Exception {
        when(publicationAuthorshipDecisionService.upsertDecision(
                eq("u@uvt.ro"), eq("p1"), eq(PublicationAuthorshipDecision.Status.CONFIRMED), eq("mine")))
                .thenThrow(new IllegalStateException("Confirm your current and past affiliations in the profile tab before reviewing publication authorship."));

        mockMvc.perform(post("/user/workspace/publications/p1/authorship/confirm")
                        .with(authenticatedUser("u@uvt.ro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"mine\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("Confirm your current and past affiliations in the profile tab before reviewing publication authorship."));
    }

    @Test
    void workspaceProfileJsonIncludesObservedAndConfirmedAffiliations() throws Exception {
        User user = new User();
        user.setEmail("u@uvt.ro");
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName("Adrian");
        profile.setLastName("Spataru");
        profile.setScopusId(List.of("55637349100"));
        profile.setCurrentAffiliationIds(List.of("saff_uvt"));
        profile.setPastAffiliationIds(List.of("saff_old"));
        profile.setAffiliationsConfirmedAt(Instant.parse("2026-04-18T10:00:00Z"));
        user.setResearcherProfile(profile);

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("sauth_1");
        author.setAffiliationIds(List.of("saff_old", "saff_uvt"));

        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView uvt = new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView();
        uvt.setId("saff_uvt");
        uvt.setName("Universitatea de Vest din Timișoara");
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView old = new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView();
        old.setId("saff_old");
        old.setName("Old Institute");

        when(userRepository.findById("u@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(User.ResearcherProfile.class))).thenReturn(List.of("sauth_1"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAffiliationsByIdIn(any())).thenReturn(List.of(uvt, old));
        when(userScopusTaskFacade.buildTasksView("u@uvt.ro", "u@uvt.ro"))
                .thenReturn(new ro.uvt.pokedex.core.service.application.model.UserScopusTasksViewModel(new User(), List.of(), List.of()));

        mockMvc.perform(get("/user/workspace/profile").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.researcher.currentAffiliationIds[0]").value("saff_uvt"))
                .andExpect(jsonPath("$.researcher.pastAffiliationIds[0]").value("saff_old"))
                .andExpect(jsonPath("$.observedAffiliations.length()").value(2))
                .andExpect(jsonPath("$.affiliationConfirmationRequired").value(false));
    }

    @Test
    void clearAuthorshipEndpointReturnsPendingState() throws Exception {
        when(publicationAuthorshipDecisionService.clearDecision("u@uvt.ro", "p1")).thenReturn(true);

        mockMvc.perform(delete("/user/workspace/publications/p1/authorship")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void workspaceAuthorshipEndpointsRejectUnauthorizedRequests() throws Exception {
        mockMvc.perform(post("/user/workspace/publications/p1/authorship/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/user/workspace/publications/p1/authorship/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/user/workspace/publications/p1/authorship"))
                .andExpect(status().isUnauthorized());
    }

    private RequestPostProcessor authenticatedUser(String email) {
        User user = new User();
        user.setEmail(email);
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
