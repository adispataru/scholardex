package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.EvaluationSnapshotRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(EvaluationWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalControllerAdvice.class, IndividualReportViewModelAssembler.class})
class EvaluationWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    // H91: IndividualReportViewModelAssembler is @Import-ed (not mocked), so its constructor deps must
    // exist in the slice — it now assembles the dashboard's i18n bundle.
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.UiMessageBundleService uiMessageBundleService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserReportFacade userReportFacade;
    @MockitoBean
    private UserIndividualReportRunService userIndividualReportRunService;
    @MockitoBean
    private UserIndicatorResultService userIndicatorResultService;
    @MockitoBean
    private UserIndividualReportRunRepository userIndividualReportRunRepository;
    @MockitoBean
    private EvaluationSnapshotRepository evaluationSnapshotRepository;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.ReportTransferFacade reportTransferFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade userActivityInstanceFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.UserPublicationFacade userPublicationFacade;

    @org.junit.jupiter.api.BeforeEach
    void assemblerDefaults() {
        // The assembler resolves the export format through the facade; a null enum would NPE the view render.
        org.mockito.Mockito.lenient()
                .when(reportTransferFacade.preferredExportFormat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX);
    }

    @Test
    void evaluationTemplateExposesSnapshotRegionAndModalDescriptions() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-view.html"));

        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-compare-select\" class=\"form-control form-control-sm\" th:attr=\"aria-label=#{report.view.selectComparison}\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-snapshots-panel\" hidden role=\"region\" aria-labelledby=\"eval-snapshots-title\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-snapshots-title\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("aria-describedby=\"citationModalPubTitle citationModalTotal\""));
    }

    @Test
    void evaluationTemplateShipsTheWorkbenchStructure() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-view.html"));

        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-thresholds-data\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("class=\"app-eval-rail\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("app-eval-evidence__criterion"));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-overflow-menu\""));
        // The overflow menu (compare/snapshot/export/verify) stays self-only.
        org.junit.jupiter.api.Assertions.assertTrue(template.contains(
                "<div class=\"app-eval-summary__actions\" th:unless=\"${delegated}\">"));
        // Export link keeps the current run pinned in its URL.
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("run=${runMetaId}"));
    }

    @Test
    void indicatorDescriptionRendersOutsideTheJsOwnedContentDiv() throws Exception {
        // H94: the description must be a sibling of .indicator-detail-content, not inside it — the
        // dashboard JS replaces that div's innerHTML on every detail load and would erase it.
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-view.html"));
        // Anchored on the class ATTRIBUTES: a comment naming the content div precedes the element,
        // and a bare-substring search matched the comment first.
        int description = template.indexOf("class=\"indicator-description");
        int content = template.indexOf("class=\"indicator-detail-content");
        org.junit.jupiter.api.Assertions.assertTrue(description > 0, "the description element must exist");
        org.junit.jupiter.api.Assertions.assertTrue(description < content,
                "the description must precede (be a sibling of) the JS-owned content div");
        org.junit.jupiter.api.Assertions.assertTrue(
                template.contains("th:if=\"${indicator.description != null"),
                "an indicator without a description must render nothing, not an empty block");
    }

    @Test
    void indicatorSectionsAreCollapsibleAndStartExpanded() throws Exception {
        // A criterion can hold several indicators of sixty-plus rows each, all rendered at once. The
        // header button is the collapse control; it must carry the ARIA wiring, and it must start
        // expanded so the change adds a control without altering what an existing user sees first.
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-view.html"));
        String js = Files.readString(Path.of("src/main/resources/static/js/individual-report-dashboard.js"));
        String css = Files.readString(Path.of("src/main/resources/static/css/individual-report-dashboard.css"));

        org.junit.jupiter.api.Assertions.assertTrue(
                template.contains("aria-controls=${'indicator-detail-' + indicator.id}"),
                "the toggle must name the panel it controls");
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("aria-expanded=\"true\""),
                "indicators start expanded — collapsing is opt-in, not the new default");
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("app-eval-indicator__chevron"),
                "there must be a visible affordance, not just a clickable name");

        // The collapse flag must NOT be `hidden`: selectCriterion() un-hides every panel in a criterion to
        // trigger the eager load, so sharing that flag would silently re-expand everything on each switch.
        org.junit.jupiter.api.Assertions.assertTrue(js.contains("classList.toggle('is-collapsed'"),
                "collapse state belongs on a class, not on panel.hidden");
        org.junit.jupiter.api.Assertions.assertTrue(
                css.contains(".app-eval-indicator.is-collapsed .indicator-detail-panel"),
                "the class needs a rule, or the toggle does nothing visible");
    }

    @Test
    void evaluationTemplateShipsTheActionsStrip() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-view.html"));

        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-actions-strip\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-near-miss\""));
        // The pending-confirmations chip is self-only and links into My Publications.
        org.junit.jupiter.api.Assertions.assertTrue(template.contains(
                "th:if=\"${!#bools.isTrue(delegated) and pendingReviewCount != null and pendingReviewCount > 0}\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("href=\"/user/workspace#publications\""));
    }

    @Test
    void showEvaluationExposesThresholdsJsonForTheWorkbench() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        Indicator indicator = publicationIndicator("ind-pub");
        IndividualReport report = report("rep-1", indicator);
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion criterion =
                new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        criterion.setName("Articles");
        criterion.setIndicatorIndices(List.of(0));
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold threshold =
                new ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold();
        threshold.setPosition(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV);
        threshold.setValue(5.0);
        criterion.setThresholds(List.of(threshold));
        report.setCriteria(List.of(criterion));

        when(userReportFacade.buildIndividualReportsListView("u@uvt.ro"))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(runDto("run-1")));
        when(userIndividualReportRunService.listRuns("u@uvt.ro", "rep-1"))
                .thenReturn(List.of());

        String thresholdsJson = (String) mockMvc.perform(
                        get("/user/evaluation").param("report", "rep-1").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("thresholdsJson");

        org.junit.jupiter.api.Assertions.assertTrue(thresholdsJson.contains("\"name\":\"Articles\""));
        org.junit.jupiter.api.Assertions.assertTrue(thresholdsJson.contains("\"position\":\"PROF_UNIV\""));
        org.junit.jupiter.api.Assertions.assertTrue(thresholdsJson.contains("\"value\":5.0"));
        // Stage 1: no threshold-cap additions → the per-position keys must be ABSENT, so the dashboard
        // JS keeps its legacy single-score path for every existing report.
        org.junit.jupiter.api.Assertions.assertFalse(thresholdsJson.contains("scoreByPosition"));
        org.junit.jupiter.api.Assertions.assertFalse(thresholdsJson.contains("capNotesByPosition"));
    }

    @Test
    void showEvaluationExposesPositionEffectiveScoresForThresholdCapAdditions() throws Exception {
        // Stage 1 position-aware eligibility (FEAA 2026 book cap): a criterion declaring a
        // thresholdCapAddition ships per-position effective scores + "counted, capped" notes in the
        // thresholds JSON; the canonical "score" stays the raw criteriaScores value.
        User user = userPrincipal("u@uvt.ro");
        Indicator articles = publicationIndicator("ind-articles");
        articles.setName("FEEA_P");
        Indicator books = publicationIndicator("ind-books");
        books.setName("FEEA_Books");
        IndividualReport report = report("rep-1", articles);
        report.setIndicators(List.of(articles, books));

        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion criterion =
                new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        criterion.setName("P");
        criterion.setIndicatorIndices(List.of(0));
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold threshold =
                new ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold();
        threshold.setPosition(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV);
        threshold.setValue(1.25);
        criterion.setThresholds(List.of(threshold));
        ro.uvt.pokedex.core.model.reporting.AbstractReport.ThresholdCapAddition addition =
                new ro.uvt.pokedex.core.model.reporting.AbstractReport.ThresholdCapAddition();
        addition.setIndicatorIndex(1);
        addition.setPercent(25.0);
        criterion.setThresholdCapAdditions(List.of(addition));
        report.setCriteria(List.of(criterion));

        when(userReportFacade.buildIndividualReportsListView("u@uvt.ro"))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(new IndividualReportRunDto(
                        "run-1", "rep-1", List.of(),
                        Map.of("ind-articles", 1.0, "ind-books", 0.9),
                        Map.of(),
                        Map.of(0, 1.0),
                        Instant.parse("2026-04-16T12:00:00Z"),
                        IndividualReportRunDto.Source.BUILT,
                        "user@uvt.ro")));
        when(userIndividualReportRunService.listRuns("u@uvt.ro", "rep-1"))
                .thenReturn(List.of());

        String thresholdsJson = (String) mockMvc.perform(
                        get("/user/evaluation").param("report", "rep-1").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("thresholdsJson");

        // Canonical score untouched; effective CONF score = 1.0 + min(0.9, 25%·1.25) = 1.3125.
        org.junit.jupiter.api.Assertions.assertTrue(thresholdsJson.contains("\"score\":1.0"));
        org.junit.jupiter.api.Assertions.assertTrue(
                thresholdsJson.contains("\"scoreByPosition\":{\"CONF_UNIV\":1.3125}"));
        org.junit.jupiter.api.Assertions.assertTrue(thresholdsJson.contains("capNotesByPosition"));
        org.junit.jupiter.api.Assertions.assertTrue(thresholdsJson.contains("FEEA_Books"));
    }

    @Test
    void showEvaluationAddsConfirmedPublicationWarningForPublicationBackedReports() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        IndividualReport report = report("rep-1", publicationIndicator("ind-pub"));

        when(userReportFacade.buildIndividualReportsListView("u@uvt.ro"))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userReportFacade.reportUsesPublicationScoring("rep-1")).thenReturn(true);
        when(userReportFacade.hasConfirmedPublicationsForScoring("u@uvt.ro")).thenReturn(false);
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(runDto("run-1")));
        when(userIndividualReportRunService.listRuns("u@uvt.ro", "rep-1"))
                .thenReturn(List.of());

        mockMvc.perform(get("/user/evaluation").param("report", "rep-1").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individual-report-view"))
                .andExpect(model().attribute("confirmedPublicationScoringWarning", true));
    }

    @Test
    void showEvaluationExposesPendingReviewCountAndPriorRunScores() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        IndividualReport report = report("rep-1", publicationIndicator("ind-pub"));

        when(userReportFacade.buildIndividualReportsListView("u@uvt.ro"))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(runDto("run-1")));
        ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun priorRun =
                new ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun();
        priorRun.setId("run-0");
        priorRun.setCreatedAt(java.time.Instant.parse("2026-06-01T10:00:00Z"));
        priorRun.setStatus(ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun.Status.READY);
        priorRun.setCriteriaScores(new java.util.HashMap<>(Map.of(0, 4.5)));
        when(userIndividualReportRunService.listRuns("u@uvt.ro", "rep-1"))
                .thenReturn(List.of(priorRun));
        when(userPublicationFacade.countPendingAuthorshipReviews("u@uvt.ro")).thenReturn(3);

        mockMvc.perform(get("/user/evaluation").param("report", "rep-1").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pendingReviewCount", 3))
                .andExpect(model().attribute("priorRuns",
                        List.of(new ro.uvt.pokedex.core.view.user.PriorRunView(
                                "run-0", "2026-06-01T10:00:00Z", "READY", Map.of(0, 4.5)))));
    }

    @Test
    void showEvaluationOmitsConfirmedPublicationWarningForActivityOnlyReports() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        Indicator activityIndicator = new Indicator();
        activityIndicator.setId("ind-act");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(activityIndicator, "GENERIC_ACTIVITIES");
        var activity = new ro.uvt.pokedex.core.model.activities.Activity();
        activity.setName("Mentoring");
        activityIndicator.setActivity(activity);
        IndividualReport report = report("rep-1", activityIndicator);

        when(userReportFacade.buildIndividualReportsListView("u@uvt.ro"))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userReportFacade.reportUsesPublicationScoring("rep-1")).thenReturn(false);
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(runDto("run-1")));
        when(userIndividualReportRunService.listRuns("u@uvt.ro", "rep-1"))
                .thenReturn(List.of());

        mockMvc.perform(get("/user/evaluation").param("report", "rep-1").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individual-report-view"))
                .andExpect(model().attribute("confirmedPublicationScoringWarning", false));
    }

    @Test
    void exportEndpointPassesSelectedRunToFacade() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        when(reportTransferFacade.exportRunOutcome(
                "u@uvt.ro",
                "rep-1",
                "run-42",
                ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX,
                false))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportOutcome.success(
                        new ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportedReport(
                                new byte[]{1, 2, 3},
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "Report.xlsx")));

        mockMvc.perform(get("/user/evaluation/export")
                        .param("report", "rep-1")
                        .param("run", "run-42")
                        .param("format", "XLSX")
                        .with(authenticatedUser(user)))
                .andExpect(status().isOk());

        verify(reportTransferFacade).exportRunOutcome(
                "u@uvt.ro",
                "rep-1",
                "run-42",
                ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX,
                false);
    }

    @Test
    void exportEndpointMapsForbiddenRunToForbiddenStatus() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        when(reportTransferFacade.exportRunOutcome(
                "u@uvt.ro",
                "rep-1",
                "run-42",
                ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX,
                false))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportOutcome.failure(
                        ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportFailureReason.FORBIDDEN_RUN,
                        "Report run belongs to another user."));

        mockMvc.perform(get("/user/evaluation/export")
                        .param("report", "rep-1")
                        .param("run", "run-42")
                        .param("format", "XLSX")
                        .with(authenticatedUser(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportEndpointMapsInvalidConfigToUnprocessableStatus() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        when(reportTransferFacade.exportRunOutcome(
                "u@uvt.ro",
                "rep-1",
                "run-42",
                ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX,
                false))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportOutcome.failure(
                        ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportFailureReason.NOT_READY,
                        "Report export configuration is incomplete."));

        mockMvc.perform(get("/user/evaluation/export")
                        .param("report", "rep-1")
                        .param("run", "run-42")
                        .param("format", "XLSX")
                        .with(authenticatedUser(user)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void exportEndpointMapsMissingRendererToNotImplementedStatus() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        when(reportTransferFacade.exportRunOutcome(
                "u@uvt.ro",
                "rep-1",
                "run-42",
                ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.DOCX,
                false))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportOutcome.failure(
                        ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportFailureReason.RENDERER_NOT_AVAILABLE,
                        "Report type is registered, but no DOCX renderer is available."));

        mockMvc.perform(get("/user/evaluation/export")
                        .param("report", "rep-1")
                        .param("run", "run-42")
                        .param("format", "DOCX")
                        .with(authenticatedUser(user)))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void evaluationTemplateIncludesDisplayedRunInExportLink() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-view.html"));

        org.junit.jupiter.api.Assertions.assertTrue(template.contains("run=${runMetaId}"));
    }

    @Test
    void evaluationTemplateIncludesDisplayedRunInVerifyLink() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-view.html"));

        org.junit.jupiter.api.Assertions.assertTrue(template.contains("@{/user/evaluation/import(report=${report.id},run=${runMetaId})}"));
    }

    @Test
    void importTemplatePreservesDisplayedRunInUploadForm() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-import.html"));

        org.junit.jupiter.api.Assertions.assertTrue(template.contains("name=\"run\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("th:value=\"${runId}\""));
    }

    @Test
    void importEndpointPassesSelectedRunToFacade() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        when(reportTransferFacade.verifyRun(
                org.mockito.ArgumentMatchers.eq("u@uvt.ro"),
                org.mockito.ArgumentMatchers.eq("rep-1"),
                org.mockito.ArgumentMatchers.eq("run-42"),
                org.mockito.ArgumentMatchers.eq(ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationOutcome.success(
                        new ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationView(
                        new ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison(List.of(), List.of(), 0, 0, 0, 0, 0),
                        null,
                        "run-42",
                        null,
                        List.of(),
                        List.of())));

        mockMvc.perform(multipart("/user/evaluation/import")
                        .file("file", new byte[]{1, 2, 3})
                        .param("report", "rep-1")
                        .param("run", "run-42")
                        .with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("comparison"))
                .andExpect(model().attribute("runId", "run-42"));

        verify(reportTransferFacade).verifyRun(
                org.mockito.ArgumentMatchers.eq("u@uvt.ro"),
                org.mockito.ArgumentMatchers.eq("rep-1"),
                org.mockito.ArgumentMatchers.eq("run-42"),
                org.mockito.ArgumentMatchers.eq(ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void importViewRendersImportableItemWhoseBlockHasNoActivityOptions() throws Exception {
        // Regression: an old report can carry an activity block with importable rows but no bound
        // platform activity type. The "Add to platform" toggle was guarded on empty activityOptions
        // but the hidden form below it wasn't — its single-activity [0] access blew up the whole page
        // (SpelEvaluationException EL1025E at individual-report-import line ~454).
        User user = userPrincipal("u@uvt.ro");
        var importable = new ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison.ActivityItem(
                "Comisie admitere 2014", "D.3", null, 2.0,
                ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison.Status.ONLY_IN_FILE, null);
        var block = new ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison.ActivityBlockComparison(
                "D.3 Comisii", 0.0, 2.0, 2.0,
                ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison.Status.DIFFERS,
                List.of(), List.of(importable), List.of(),
                List.of()); // <- no bound activity options
        when(reportTransferFacade.verifyRun(
                org.mockito.ArgumentMatchers.eq("u@uvt.ro"),
                org.mockito.ArgumentMatchers.eq("rep-1"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationOutcome.success(
                        new ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationView(
                        new ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison(
                                List.of(), List.of(block), 0, 2.0, 0, 0, 1),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of())));

        String html = mockMvc.perform(multipart("/user/evaluation/import")
                        .file("file", new byte[]{1, 2, 3})
                        .param("report", "rep-1")
                        .with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("no activity type bound"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Comisie admitere 2014"));
    }

    @Test
    void importViewRendersLayoutWarningsCallout() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        when(reportTransferFacade.verifyRun(
                org.mockito.ArgumentMatchers.eq("u@uvt.ro"),
                org.mockito.ArgumentMatchers.eq("rep-1"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationOutcome.success(
                        new ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationView(
                        new ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison(List.of(), List.of(), 0, 0, 0, 0, 0),
                        null,
                        null,
                        null,
                        List.of("Sheet 'B-Reviste': the layout differs from the official template (found 'Titlu' column in column B, expected C). Its rows were NOT compared — please fill in the official template."),
                        List.of())));

        String html = mockMvc.perform(multipart("/user/evaluation/import")
                        .file("file", new byte[]{1, 2, 3})
                        .param("report", "rep-1")
                        .with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("layout differs from the official template"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("B-Reviste"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("responsibility"));
    }

    @Test
    void importEndpointMapsInvalidWorkbookToUnprocessableStatus() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        when(reportTransferFacade.verifyRun(
                org.mockito.ArgumentMatchers.eq("u@uvt.ro"),
                org.mockito.ArgumentMatchers.eq("rep-1"),
                org.mockito.ArgumentMatchers.eq("run-42"),
                org.mockito.ArgumentMatchers.eq(ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationOutcome.failure(
                        ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationFailureReason.INVALID_WORKBOOK,
                        "Could not read the uploaded workbook: bad workbook"));

        mockMvc.perform(multipart("/user/evaluation/import")
                        .file("file", new byte[]{1, 2, 3})
                        .param("report", "rep-1")
                        .param("run", "run-42")
                        .with(authenticatedUser(user)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(model().attribute("errorMessage", "Could not read the uploaded workbook: bad workbook"));
    }

    @Test
    void importEndpointMapsMissingParserToNotImplementedStatus() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        when(reportTransferFacade.verifyRun(
                org.mockito.ArgumentMatchers.eq("u@uvt.ro"),
                org.mockito.ArgumentMatchers.eq("rep-1"),
                org.mockito.ArgumentMatchers.eq("run-42"),
                org.mockito.ArgumentMatchers.eq(ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat.XLSX),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationOutcome.failure(
                        ro.uvt.pokedex.core.service.application.ReportTransferFacade.VerificationFailureReason.PARSER_NOT_AVAILABLE,
                        "Report type is registered, but no XLSX parser is available."));

        mockMvc.perform(multipart("/user/evaluation/import")
                        .file("file", new byte[]{1, 2, 3})
                        .param("report", "rep-1")
                        .param("run", "run-42")
                        .with(authenticatedUser(user)))
                .andExpect(status().isNotImplemented())
                .andExpect(model().attribute("errorMessage", "Report type is registered, but no XLSX parser is available."));
    }

    private static IndividualReport report(String id, Indicator indicator) {
        IndividualReport report = new IndividualReport();
        report.setId(id);
        report.setTitle("Report");
        report.setIndicators(List.of(indicator));
        report.setCriteria(List.of());
        return report;
    }

    private static Indicator publicationIndicator(String id) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS");
        return indicator;
    }

    private static IndividualReportRunDto runDto(String id) {
        return new IndividualReportRunDto(
                id,
                "rep-1",
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Instant.parse("2026-04-16T12:00:00Z"),
                IndividualReportRunDto.Source.BUILT,
                "user@uvt.ro"
        );
    }

    private static User userPrincipal(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private static RequestPostProcessor authenticatedUser(User user) {
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
