package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorWorkbookExportViewModel;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.UserPublicationCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.application.model.UserScopusTasksViewModel;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.reporting.Score;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(UserViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class UserViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ResearcherService researcherService;
    @MockitoBean
    private UserPublicationFacade userPublicationFacade;
    @MockitoBean
    private UserScopusTaskFacade userScopusTaskFacade;
    @MockitoBean
    private UserReportFacade userReportFacade;
    @MockitoBean
    private UserIndicatorResultService userIndicatorResultService;
    @MockitoBean
    private UserIndividualReportRunService userIndividualReportRunService;

    @Test
    void dashboardUsesCanonicalRouteAndLegacyRootRedirects() throws Exception {
        mockMvc.perform(get("/user/dashboard").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace"));

        mockMvc.perform(get("/user").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace"));
    }

    @Test
    void indicatorExportRedirectsToLoginWhenAuthenticationMissing() throws Exception {
        mockMvc.perform(get("/user/indicators/export/{id}", "ind-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void indicatorExportReturnsNotFoundWhenWorkbookMissing() throws Exception {
        when(userReportFacade.buildIndicatorWorkbookExport(eq("u@uvt.ro"), eq("ind-1")))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/user/indicators/export/{id}", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void indicatorExportReturnsWorkbookResponseContract() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        when(userReportFacade.buildIndicatorWorkbookExport(eq("u@uvt.ro"), eq("ind-1")))
                .thenReturn(Optional.of(new UserIndicatorWorkbookExportViewModel(
                        bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "indicator_results.xlsx"
                )));

        mockMvc.perform(get("/user/indicators/export/{id}", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"indicator_results.xlsx\""))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void cnfis2025ExportRedirectsToLoginWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/user/exports/cnfis"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void publicationsPageRedirectsToWorkspace() throws Exception {
        mockMvc.perform(get("/user/publications").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace#publications"));
    }

    @Test
    void cnfis2025ExportReturnsWorkbookResponseContract() throws Exception {
        byte[] bytes = new byte[]{9, 8, 7};
        when(userReportFacade.buildUserCnfisWorkbookExport(eq("u@uvt.ro"), eq(2021), eq(2024)))
                .thenReturn(UserWorkbookExportResult.ok(
                        bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx"
                ));

        mockMvc.perform(get("/user/exports/cnfis")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx\""))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void cnfis2025ExportWithInvalidStartYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/exports/cnfis")
                        .param("start", "bad")
                        .param("end", "2024")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cnfis2025ExportWithInvalidEndYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/exports/cnfis")
                        .param("start", "2021")
                        .param("end", "bad")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cnfis2025ExportWithStartGreaterThanEndReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/exports/cnfis")
                        .param("start", "2024")
                        .param("end", "2021")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cnfis2025ExportWithOutOfRangeYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/exports/cnfis")
                        .param("start", "1899")
                        .param("end", "2024")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removedLegacyUserExportAliasesReturnNotFound() throws Exception {
        mockMvc.perform(get("/user/publications/exportCNFIS2025").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/user/export/cnfis").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void scopusTasksPageUsesCanonicalKebabCaseRoute() throws Exception {
        mockMvc.perform(get("/user/publications/scopus-tasks").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace#profile"));
    }

    @Test
    void removedLegacyScopusTaskRoutesReturnNotFound() throws Exception {
        mockMvc.perform(get("/user/publications/scopus_tasks").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/user/tasks/scopus/update").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/user/tasks/scopus/updateCitations").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void canonicalScopusTaskActionsReturnCreated() throws Exception {
        when(userScopusTaskFacade.createPublicationTask(eq("u@uvt.ro"), org.mockito.ArgumentMatchers.any(ScopusPublicationUpdate.class)))
                .thenReturn(new ScopusPublicationUpdate());
        when(userScopusTaskFacade.createCitationTask(eq("u@uvt.ro"), org.mockito.ArgumentMatchers.any(ScopusCitationsUpdate.class)))
                .thenReturn(new ScopusCitationsUpdate());

        mockMvc.perform(post("/user/tasks/scopus/update-publications")
                        .param("scopusId", "123")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/user/tasks/scopus/update-citations")
                        .param("scopusId", "123")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isCreated());
    }

    @Test
    void authorPublicationsPageRendersExpectedTemplateAndFrontendModelContract() throws Exception {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setForum("f1");
        publication.setAuthors(List.of("a1"));

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author A");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum A");
        forum.setIssn("1234-5678");
        forum.setEIssn("8765-4321");

        when(userPublicationFacade.buildAuthorPublicationsView(eq("a1")))
                .thenReturn(Optional.of(new UserPublicationsViewModel(
                        List.of(publication),
                        3,
                        Map.of("a1", author),
                        Map.of("f1", forum),
                        Map.of(),
                        Map.of(),
                        0,
                        0,
                        0,
                        8,
                        null,
                        List.of()
                )));

        mockMvc.perform(get("/user/authors/view/{id}", "a1").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/publications"))
                .andExpect(model().attributeExists("publications", "hIndex", "authorMap", "forumMap", "numCitations", "user"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/user/workspace\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("href=\"/user\""))));
    }

    @Test
    void userRouteKeepsUserSidebarForPlatformAdmin() throws Exception {
        User user = userPrincipal("admin@uvt.ro");
        user.setResearcherId("r1");
        user.setRoles(Set.of(UserRole.PLATFORM_ADMIN));

        mockMvc.perform(get("/user/publications").with(authenticatedUser(user)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace#publications"));
    }

    @Test
    void authorPublicationsPageReusesSharedTemplateWithoutActionButtons() throws Exception {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setForum("f1");
        publication.setAuthors(List.of("sauth_1"));

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("sauth_1");
        author.setName("Author A");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum A");

        when(userPublicationFacade.buildAuthorPublicationsView(eq("sauth_1")))
                .thenReturn(Optional.of(new UserPublicationsViewModel(
                        List.of(publication),
                        3,
                        Map.of("sauth_1", author),
                        Map.of("f1", forum),
                        Map.of(),
                        Map.of(),
                        0,
                        0,
                        0,
                        8,
                        author,
                        List.of()
                )));

        String html = mockMvc.perform(get("/user/authors/view/{id}", "sauth_1").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/publications"))
                .andExpect(model().attributeExists("publications", "hIndex", "authorMap", "forumMap", "numCitations", "user"))
                .andExpect(model().attribute("publicationPageTitle", "Author Publications"))
                .andExpect(model().attribute("publicationTableTitle", "Author Publications"))
                .andExpect(model().attribute("publicationPageSubtitle", "Author A"))
                .andExpect(model().attribute("showPublicationActions", false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(html.contains("Export CNFIS"));
        assertFalse(html.contains("Add Publication"));
        assertFalse(html.contains("Scopus Updates"));
    }

    @Test
    void authorPublicationsPageRedirectsToSharedPublicationsWhenAuthorMissing() throws Exception {
        when(userPublicationFacade.buildAuthorPublicationsView(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/user/authors/view/{id}", "missing").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/publications"));
    }

    @Test
    void publicationCitationsPageRedirectsToWorkspace() throws Exception {
        mockMvc.perform(get("/user/publications/citations")
                        .param("eid", "2-s2.0-85137747651")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace#publications"));
    }

    @Test
    void individualReportsListRedirectsToEvaluation() throws Exception {
        mockMvc.perform(get("/user/individual-reports")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/evaluation"));
    }

    @Test
    void editPublicationFormRedirectsToWorkspace() throws Exception {
        mockMvc.perform(get("/user/publications/edit/{eid}", "eid-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace#publications"));
    }

    @Test
    void removedUserRankingAliasReturnsNotFound() throws Exception {
        mockMvc.perform(get("/user/rankings/{id}", "forum-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void indicatorApplyRedirectsToEvaluationWithHash() throws Exception {
        mockMvc.perform(get("/user/indicators/apply/{id}", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/evaluation#indicator-ind-1"));
    }

    @Test
    void activitiesApplyRedirectsToEvaluationWithHash() throws Exception {
        mockMvc.perform(get("/user/indicators/apply/{id}", "ind-act-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/evaluation#indicator-ind-act-1"));
    }

    @Test
    void indicatorApplyRefreshRedirectsToEvaluation() throws Exception {
        mockMvc.perform(post("/user/indicators/apply/{id}/refresh", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/evaluation#indicator-ind-1"));
    }

    @Test
    void individualReportViewRedirectsToEvaluation() throws Exception {
        mockMvc.perform(get("/user/individual-reports/view/{id}", "rep-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/evaluation?report=rep-1"));
    }

    @Test
    void individualReportRefreshAllIndicatorsRedirectsToLoginWhenAuthenticationMissing() throws Exception {
        mockMvc.perform(post("/user/individual-reports/view/{id}/refresh-all-indicators", "rep-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void individualReportRefreshAllIndicatorsRedirectsToEvaluation() throws Exception {
        mockMvc.perform(post("/user/individual-reports/view/{id}/refresh-all-indicators", "rep-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/evaluation?report=rep-1"));

        verify(userIndividualReportRunService).refreshRunWithAllIndicators("u@uvt.ro", "rep-1");
    }

    @Test
    void individualReportViewRedirectsToEvaluationWithReportParam() throws Exception {
        mockMvc.perform(get("/user/individual-reports/view/{id}", "rep-2")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/evaluation?report=rep-2"));
    }

    @Test
    void removedLegacyIndividualReportsRouteReturnsNotFound() throws Exception {
        mockMvc.perform(get("/user/individualReports")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/user/individualReports/view/{id}", "rep-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void citationsApplyRedirectsToEvaluationWithHash() throws Exception {
        mockMvc.perform(get("/user/indicators/apply/{id}", "ind-cit-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/evaluation#indicator-ind-cit-1"));
    }

    private User userPrincipal(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private RequestPostProcessor authenticatedUser(String email) {
        return authenticatedUser(userPrincipal(email));
    }

    private RequestPostProcessor authenticatedUser(User user) {
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
