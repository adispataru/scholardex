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
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.user.User;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    void publicationsPageRedirectsToLoginWhenAuthenticationMissing() throws Exception {
        mockMvc.perform(get("/user/publications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
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
        Researcher researcher = new Researcher();
        researcher.setScopusId(List.of("123"));
        when(userScopusTaskFacade.buildTasksView(eq("u@uvt.ro"), eq("r1")))
                .thenReturn(new UserScopusTasksViewModel(researcher, List.of(), List.of()));

        User user = userPrincipal("u@uvt.ro");
        user.setResearcherId("r1");

        mockMvc.perform(get("/user/publications/scopus-tasks").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("user/tasks"))
                .andExpect(model().attributeExists("researcher", "tasks", "citationsTasks", "user"));
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
    void publicationsPageRendersExpectedTemplateAndFrontendModelContract() throws Exception {
        Publication publication = new Publication();
        publication.setId("p1");
        publication.setForum("f1");
        publication.setAuthors(List.of("a1"));

        Author author = new Author();
        author.setId("a1");
        author.setName("Author A");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum A");
        forum.setIssn("1234-5678");
        forum.setEIssn("8765-4321");

        when(userPublicationFacade.buildUserPublicationsView(eq("r1")))
                .thenReturn(Optional.of(new UserPublicationsViewModel(
                        List.of(publication),
                        3,
                        Map.of("a1", author),
                        Map.of("f1", forum),
                        8
                )));

        User user = userPrincipal("u@uvt.ro");
        user.setResearcherId("r1");

        mockMvc.perform(get("/user/publications").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("user/publications"))
                .andExpect(model().attributeExists("publications", "hIndex", "authorMap", "forumMap", "numCitations", "user"));
    }

    @Test
    void authorPublicationsPageReusesSharedTemplateWithoutActionButtons() throws Exception {
        Publication publication = new Publication();
        publication.setId("p1");
        publication.setForum("f1");
        publication.setAuthors(List.of("sauth_1"));

        Author author = new Author();
        author.setId("sauth_1");
        author.setName("Author A");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum A");

        when(userPublicationFacade.buildAuthorPublicationsView(eq("sauth_1")))
                .thenReturn(Optional.of(new UserPublicationsViewModel(
                        List.of(publication),
                        3,
                        Map.of("sauth_1", author),
                        Map.of("f1", forum),
                        8
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
    void publicationCitationsPageAcceptsEidQueryParam() throws Exception {
        Publication publication = new Publication();
        publication.setId("p1");
        publication.setEid("2-s2.0-85137747651");
        publication.setForum("f1");
        publication.setAuthors(List.of("a1"));

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum A");
        forum.setIssn("1234-5678");
        forum.setEIssn("8765-4321");

        when(userPublicationFacade.buildCitationsView(eq("2-s2.0-85137747651")))
                .thenReturn(Optional.of(new UserPublicationCitationsViewModel(
                        publication,
                        List.of(),
                        forum,
                        Map.of(),
                        Map.of()
                )));

        mockMvc.perform(get("/user/publications/citations")
                        .param("eid", "2-s2.0-85137747651")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/citations"))
                .andExpect(model().attributeExists("publication", "citations", "forum", "authorMapping", "forumMap", "user"));
    }

    @Test
    void individualReportsListUsesCanonicalKebabCaseRoute() throws Exception {
        when(userReportFacade.buildIndividualReportsListView(eq("u@uvt.ro")))
                .thenReturn(new UserReportsListViewModel(List.of()));

        mockMvc.perform(get("/user/individual-reports")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individualReports"))
                .andExpect(model().attributeExists("individualReports", "user"));
    }

    @Test
    void editPublicationFormRendersPublicationEditTemplateWhenPublicationExists() throws Exception {
        Publication publication = new Publication();
        publication.setEid("eid-1");
        when(userPublicationFacade.findPublicationForEdit(eq("eid-1")))
                .thenReturn(Optional.of(publication));

        mockMvc.perform(get("/user/publications/edit/{eid}", "eid-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/publications-edit"))
                .andExpect(model().attributeExists("publication"));
    }

    @Test
    void removedUserRankingAliasReturnsNotFound() throws Exception {
        mockMvc.perform(get("/user/rankings/{id}", "forum-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void indicatorApplyUsesPersistedResultPayload() throws Exception {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setName("Indicator 1");
        indicator.setDomain(domain);
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");

        when(userIndicatorResultService.getOrCreateLatest(eq("u@uvt.ro"), eq("ind-1")))
                .thenReturn(new IndicatorApplyResultDto(
                        "r1",
                        "ind-1",
                        "user/indicators-apply-publications",
                        Map.of("indicator", indicator, "total", "1.00", "publications", List.of(), "scores", Map.of(), "forumMap", Map.of(), "allQuarters", List.of(), "allValues", List.of()),
                        new IndicatorApplyResultDto.Summary(1.0, null, List.of(), List.of()),
                        IndicatorApplyResultDto.Source.PERSISTED,
                        null,
                        null,
                        0
                ));

        String html = mockMvc.perform(get("/user/indicators/apply/{id}", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/indicators-apply-publications"))
                .andExpect(model().attributeExists("indicator", "total", "resultMetaSource", "resultMetaRefreshVersion"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"publications-dashboard-v2\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/js/indicator-publications-dashboard.js"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("/js/demo/datatables-demo.js"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"publications-search\""));
    }

    @Test
    void activitiesApplyRendersDashboardV2Contract() throws Exception {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setId("ind-act-1");
        indicator.setName("Activities Indicator");
        indicator.setDomain(domain);
        indicator.setOutputType(Indicator.Type.GENERIC_ACTIVITIES);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");

        when(userIndicatorResultService.getOrCreateLatest(eq("u@uvt.ro"), eq("ind-act-1")))
                .thenReturn(new IndicatorApplyResultDto(
                        "r-act-1",
                        "ind-act-1",
                        "user/indicators-apply-activities",
                        Map.of(
                                "indicator", indicator,
                                "total", "1.00",
                                "activities", List.of(),
                                "scores", Map.of(),
                                "allQuarters", List.of("Q1"),
                                "allValues", List.of(1)
                        ),
                        new IndicatorApplyResultDto.Summary(1.0, null, List.of("Q1"), List.of(1)),
                        IndicatorApplyResultDto.Source.PERSISTED,
                        null,
                        null,
                        0
                ));

        String html = mockMvc.perform(get("/user/indicators/apply/{id}", "ind-act-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/indicators-apply-activities"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"activities-dashboard-v2\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/js/indicator-activities-dashboard.js"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("/js/demo/datatables-demo.js"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"activities-search\""));
    }

    @Test
    void indicatorApplyRefreshRedirectsToApplyPage() throws Exception {
        mockMvc.perform(post("/user/indicators/apply/{id}/refresh", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/indicators/apply/ind-1"));
    }

    @Test
    void individualReportViewDisplaysCriterionNameOrFallback() throws Exception {
        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("My Report");
        report.setDescription("Desc");
        report.setIndicators(List.of());

        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion named = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        named.setName("Research Impact");
        named.setIndicatorIndices(new ArrayList<>());
        named.setThresholds(new ArrayList<>());

        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion unnamed = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        unnamed.setName("  ");
        unnamed.setIndicatorIndices(new ArrayList<>());
        unnamed.setThresholds(new ArrayList<>());

        report.setCriteria(List.of(named, unnamed));

        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(new IndividualReportRunDto(
                        "run-1",
                        "rep-1",
                        List.of(),
                        Map.of(),
                        Map.of(0, 1.0, 1, 2.0),
                        Instant.parse("2026-03-05T10:00:00Z"),
                        IndividualReportRunDto.Source.PERSISTED
                )));

        String html = mockMvc.perform(get("/user/individual-reports/view/{id}", "rep-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individualReport-view"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Research Impact"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Criterion 2"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("Total Score for All Indicators"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("criterion-main"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/css/individual-report-dashboard.css"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/js/individual-report-dashboard.js"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("/js/demo/datatables-demo.js"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Refresh all indicators"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/user/individual-reports/view/rep-1/refresh-all-indicators"));
    }

    @Test
    void individualReportRefreshAllIndicatorsRedirectsToLoginWhenAuthenticationMissing() throws Exception {
        mockMvc.perform(post("/user/individual-reports/view/{id}/refresh-all-indicators", "rep-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void individualReportRefreshAllIndicatorsRedirectsToViewRoute() throws Exception {
        mockMvc.perform(post("/user/individual-reports/view/{id}/refresh-all-indicators", "rep-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/individual-reports/view/rep-1"));

        verify(userIndividualReportRunService).refreshRunWithAllIndicators("u@uvt.ro", "rep-1");
    }

    @Test
    void individualReportViewRendersThresholdBadgesAndCompactIndicatorLinks() throws Exception {
        IndividualReport report = new IndividualReport();
        report.setId("rep-2");
        report.setTitle("Compact Report");
        report.setDescription("Desc");

        Indicator indicator = new Indicator();
        indicator.setId("ind-compact-1");
        indicator.setName("Info_B");
        report.setIndicators(List.of(indicator));

        ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold t1 = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold();
        t1.setPosition(Position.ASIST_UNIV);
        t1.setValue(10.0);

        ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold t2 = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold();
        t2.setPosition(Position.PROF_UNIV);
        t2.setValue(56.0);

        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion criterion = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        criterion.setName("Perspectiva B");
        criterion.setIndicatorIndices(List.of(0));
        criterion.setThresholds(List.of(t1, t2));
        report.setCriteria(List.of(criterion));

        when(userReportFacade.findIndividualReportById("rep-2")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-2"))
                .thenReturn(Optional.of(new IndividualReportRunDto(
                        "run-2",
                        "rep-2",
                        List.of(),
                        Map.of("ind-compact-1", 39.39),
                        Map.of(0, 39.39),
                        Instant.parse("2026-03-05T10:00:00Z"),
                        IndividualReportRunDto.Source.PERSISTED
                )));

        String html = mockMvc.perform(get("/user/individual-reports/view/{id}", "rep-2")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individualReport-view"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("threshold-icon-rail"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("threshold-icon"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("fa-leaf"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("fa-clover"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("ASIST_UNIV"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("PROF_UNIV"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-position=\"ASIST_UNIV\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-position=\"PROF_UNIV\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-threshold-value=\"10.00\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-threshold-value=\"56.00\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("criterion-selected-position"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("criterion-score-ratio"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("data-toggle=\"tooltip\""));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("View details"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("indicator-link"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Info_B"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/user/indicators/apply/ind-compact-1"));
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
    void citationsApplyRendersDashboardV2Contract() throws Exception {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setId("ind-cit-1");
        indicator.setName("Citation Indicator");
        indicator.setDomain(domain);
        indicator.setOutputType(Indicator.Type.CITATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");

        when(userIndicatorResultService.getOrCreateLatest(eq("u@uvt.ro"), eq("ind-cit-1")))
                .thenReturn(new IndicatorApplyResultDto(
                        "r-cit-1",
                        "ind-cit-1",
                        "user/indicators-apply-citations",
                        Map.of(
                                "indicator", indicator,
                                "total", "1.00",
                                "totalCit", 1,
                                "publications", List.of(),
                                "scores", Map.of(),
                                "citationMap", Map.of(),
                                "forumMap", Map.of(),
                                "forumWosLinkMap", Map.of(),
                                "allQuarters", List.of("Q1"),
                                "allValues", List.of(1)
                        ),
                        new IndicatorApplyResultDto.Summary(1.0, 1, List.of("Q1"), List.of(1)),
                        IndicatorApplyResultDto.Source.PERSISTED,
                        null,
                        null,
                        0
                ));

        String html = mockMvc.perform(get("/user/indicators/apply/{id}", "ind-cit-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/indicators-apply-citations"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"citations-dashboard-v2\""));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("id=\"citations-legacy\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/js/indicator-citations-dashboard.js"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("/js/demo/datatables-demo.js"));
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
