package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.security.ResearcherAccessService;
import ro.uvt.pokedex.core.view.user.IndividualReportViewModelAssembler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

@WebMvcTest(ResearcherReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalControllerAdvice.class, IndividualReportViewModelAssembler.class})
class ResearcherReportControllerContractTest {

    private static final String EMAIL = "florin@e-uvt.ro";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearcherAccessService researcherAccess;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserReportFacade userReportFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.UserIndicatorResultService userIndicatorResultService;
    @MockitoBean
    private UserIndividualReportRunService userIndividualReportRunService;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.ReportTransferFacade reportTransferFacade; // export + assembler dep
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.ReportComparisonFacade reportComparisonFacade;

    @org.junit.jupiter.api.BeforeEach
    void assemblerDefaults() {
        // The assembler resolves the export format through the facade; a null enum would NPE the view render.
        org.mockito.Mockito.lenient()
                .when(reportTransferFacade.preferredExportFormat(any()))
                .thenReturn(ReportFormat.XLSX);
    }

    private IndividualReport report() {
        Indicator ind = new Indicator();
        ind.setId("ind-1");
        ind.setName("Info_X");
        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("FV Test");
        report.setIndicators(List.of(ind));
        AbstractReport.Criterion crit = new AbstractReport.Criterion();
        crit.setName("C1");
        crit.setIndicatorIndices(List.of(0));
        report.setCriteria(List.of(crit));
        return report;
    }

    private User researcher() {
        User u = new User();
        u.setEmail(EMAIL);
        return u;
    }

    @Test
    void pickerRendersInScopeResearchers() throws Exception {
        User researcher = researcher();
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName("Florin");
        profile.setLastName("Spataru");
        researcher.setResearcherProfile(profile);
        when(researcherAccess.findInScopeResearchers(any())).thenReturn(List.of(researcher));

        mockMvc.perform(get("/reports/researcher"))
                .andExpect(status().isOk())
                .andExpect(view().name("reports/researcher-picker"))
                .andExpect(content().string(containsString("Florin Spataru")))   // profile name column
                .andExpect(content().string(containsString(EMAIL)))               // email column
                .andExpect(content().string(containsString("/reports/researcher/" + EMAIL))); // view-report link
    }

    @Test
    void viewRendersDelegatedReadOnlyReport() throws Exception {
        IndividualReport report = report();
        IndividualReportRunDto run = new IndividualReportRunDto(
                "run-1", "rep-1", List.of(),
                Map.of("ind-1", 12.5), Map.of(0, 12.5),
                Instant.now(), IndividualReportRunDto.Source.PERSISTED, "admin@e-uvt.ro");

        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(researcher()));
        when(userService.findDisplayLabels(List.of(EMAIL))).thenReturn(Map.of(EMAIL, "Florin S"));
        when(userReportFacade.buildIndividualReportsListView(EMAIL))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.findLatestRun(EMAIL, "rep-1")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/reports/researcher/{email}", EMAIL).param("report", "rep-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individual-report-view"))
                .andExpect(model().attribute("delegated", true))
                .andExpect(model().attribute("delegatedSubjectEmail", EMAIL))
                // read-only banner present, interactive "Refresh All" action absent
                .andExpect(content().string(containsString("read-only")))
                .andExpect(content().string(not(containsString("Refresh All"))));

        // Delegated view must never create/persist a run.
        verify(userIndividualReportRunService).findLatestRun(EMAIL, "rep-1");
        verify(userIndividualReportRunService, never()).getOrCreateLatestRun(any(), any());
    }

    @Test
    void viewDoesNotCreateRunWhenNoneExists() throws Exception {
        IndividualReport report = report();
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(researcher()));
        when(userService.findDisplayLabels(List.of(EMAIL))).thenReturn(Map.of(EMAIL, "Florin S"));
        when(userReportFacade.buildIndividualReportsListView(EMAIL))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.findLatestRun(EMAIL, "rep-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/reports/researcher/{email}", EMAIL).param("report", "rep-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individual-report-view"))
                .andExpect(model().attribute("noRun", true))
                .andExpect(model().attribute("delegated", true));

        verify(userIndividualReportRunService).findLatestRun(EMAIL, "rep-1");
        verify(userIndividualReportRunService, never()).getOrCreateLatestRun(any(), any());
    }

    @Test
    void refreshDelegatesToServiceWithActorAndRedirects() throws Exception {
        var actor = new TestingAuthenticationToken("admin@e-uvt.ro", "x");

        mockMvc.perform(post("/reports/researcher/{email}/report/{reportId}/refresh", EMAIL, "rep-1")
                        .principal(actor))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reports/researcher/" + EMAIL + "?report=rep-1"));

        // Refresh runs against the researcher's data but is attributed to the acting principal.
        verify(userIndividualReportRunService).refreshRunWithAllIndicators(EMAIL, "rep-1", "admin@e-uvt.ro");
    }

    @Test
    void exportReturnsAttachmentAndIsReadOnly() throws Exception {
        var exported = new ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportedReport(
                "xlsx-bytes".getBytes(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "FV Test.xlsx");
        when(reportTransferFacade.exportRunOutcome(EMAIL, "rep-1", null, ReportFormat.XLSX, false))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportOutcome.success(exported));

        mockMvc.perform(get("/reports/researcher/{email}/report/{reportId}/export", EMAIL, "rep-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("FV Test.xlsx")));

        // Read-only: export must never force a refresh (no mutation of the researcher's data).
        verify(reportTransferFacade).exportRunOutcome(EMAIL, "rep-1", null, ReportFormat.XLSX, false);
    }

    @Test
    void exportFailureMapsToHttpStatus() throws Exception {
        when(reportTransferFacade.exportRunOutcome(EMAIL, "rep-1", null, ReportFormat.XLSX, false))
                .thenReturn(ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportOutcome.failure(
                        ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportFailureReason.NOT_READY,
                        "Report export configuration is incomplete."));

        mockMvc.perform(get("/reports/researcher/{email}/report/{reportId}/export", EMAIL, "rep-1"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void indicatorDetailReturnsJsonReadOnly() throws Exception {
        Map<String, Object> scores = new java.util.LinkedHashMap<>();
        ro.uvt.pokedex.core.service.reporting.Score s = new ro.uvt.pokedex.core.service.reporting.Score();
        s.setAuthorScore(7.0);
        scores.put("Paper A", s);
        Map<String, Object> graph = new java.util.LinkedHashMap<>();
        graph.put("outputMode", "publications");
        graph.put("scores", scores);
        IndicatorApplyResultDto dto = new IndicatorApplyResultDto(
                "r", "ind-1", "view", graph,
                new IndicatorApplyResultDto.Summary(7.0, null, List.of(), List.of()),
                IndicatorApplyResultDto.Source.COMPUTED, null, java.time.Instant.now(), 0);
        when(userIndicatorResultService.getReportScopedDetail(EMAIL, "rep-1", "ind-1"))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(get("/reports/researcher/{email}/indicator/{id}/detail", EMAIL, "ind-1")
                        .param("report", "rep-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"indicatorId\":\"ind-1\"")))
                .andExpect(content().string(containsString("Paper A")));

        // Read-only: report-scoped compute only (no cache-writing getOrCreateLatest path exists here).
        verify(userIndicatorResultService).getReportScopedDetail(EMAIL, "rep-1", "ind-1");
    }

    @Test
    void indicatorDetailNotFoundWhenComputeEmpty() throws Exception {
        when(userIndicatorResultService.getReportScopedDetail(EMAIL, "rep-1", "ind-x"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/reports/researcher/{email}/indicator/{id}/detail", EMAIL, "ind-x")
                        .param("report", "rep-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void citationDetailReturnsJson() throws Exception {
        Map<String, Object> citing = new java.util.LinkedHashMap<>();
        ro.uvt.pokedex.core.service.reporting.Score c = new ro.uvt.pokedex.core.service.reporting.Score();
        c.setAuthorScore(4.0);
        citing.put("Citing 1", c);
        Map<String, Object> scores = new java.util.LinkedHashMap<>();
        scores.put("Cited Pub", citing);
        Map<String, Object> graph = new java.util.LinkedHashMap<>();
        graph.put("outputMode", "citations");
        graph.put("scores", scores);
        IndicatorApplyResultDto dto = new IndicatorApplyResultDto(
                "r", "ind-1", "view", graph,
                new IndicatorApplyResultDto.Summary(4.0, null, List.of(), List.of()),
                IndicatorApplyResultDto.Source.COMPUTED, null, java.time.Instant.now(), 0);
        when(userIndicatorResultService.getReportScopedDetail(EMAIL, "rep-1", "ind-1"))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(get("/reports/researcher/{email}/indicator/{id}/citations", EMAIL, "ind-1")
                        .param("pub", "Cited Pub").param("report", "rep-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"pubTitle\":\"Cited Pub\"")))
                .andExpect(content().string(containsString("Citing 1")));
    }

    @Test
    void compareRendersComparisonWhenCompatibleReportExists() throws Exception {
        IndividualReport report = report();
        IndividualReport olderReport = report();
        olderReport.setId("rep-0");
        olderReport.setTitle("FV Test 2016");

        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(researcher()));
        when(userService.findDisplayLabels(List.of(EMAIL))).thenReturn(Map.of(EMAIL, "Florin S"));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(reportComparisonFacade.findCompatibleReport(EMAIL, report)).thenReturn(Optional.of(olderReport));

        var comparison = new ro.uvt.pokedex.core.service.application.model.ReportComparisonViewModel(
                olderReport, report, true, true,
                List.of(new ro.uvt.pokedex.core.service.application.model.ReportComparisonViewModel.CriterionComparisonRow(
                        "C1", 10.0, 15.0, 5.0, 50.0, true, true, true, true)),
                10.0, 15.0, 5.0, 50.0, false, false);
        when(reportComparisonFacade.buildComparison(any(), eq(report), eq(olderReport))).thenReturn(comparison);

        mockMvc.perform(get("/reports/researcher/{email}/report/{reportId}/compare", EMAIL, "rep-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("reports/report-compare"))
                .andExpect(model().attribute("delegatedSubjectEmail", EMAIL))
                .andExpect(model().attribute("comparison", comparison));
    }

    @Test
    void delegatedViewOpensOnThePositionCarriedFromThePromotionBoard() throws Exception {
        // The board links with ?position=<targetPosition>; the view must carry it through so the page opens
        // on the rung being investigated rather than the researcher's current one.
        IndividualReport report = report();
        IndividualReportRunDto run = new IndividualReportRunDto(
                "run-1", "rep-1", List.of(),
                Map.of("ind-1", 12.5), Map.of(0, 12.5),
                Instant.now(), IndividualReportRunDto.Source.PERSISTED, "admin@e-uvt.ro");
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(researcher()));
        when(userService.findDisplayLabels(List.of(EMAIL))).thenReturn(Map.of(EMAIL, "Florin S"));
        when(userReportFacade.buildIndividualReportsListView(EMAIL))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.findLatestRun(EMAIL, "rep-1")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/reports/researcher/{email}", EMAIL)
                        .param("report", "rep-1").param("position", "CONF_UNIV"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("initialPosition", "CONF_UNIV"))
                .andExpect(content().string(containsString("data-initial-position=\"CONF_UNIV\"")))
                // The selector itself MUST render on the delegated view. It used to be hidden
                // (th:unless="${delegated}"), so a supervisor arriving from the promotion board — whose whole
                // question is "does this person clear the NEXT position" — had no way to look at it. Safe to
                // expose: switching only re-renders threshold marks client-side from data already on the page.
                .andExpect(content().string(containsString("data-eval-position-selector")));

        // No position param on a normal visit — the view falls back to the researcher's own position.
        mockMvc.perform(get("/reports/researcher/{email}", EMAIL).param("report", "rep-1"))
                .andExpect(model().attribute("initialPosition", org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void compareRedirectsBackToReportWhenNoCompatibleReportIsAssigned() throws Exception {
        IndividualReport report = report();
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(researcher()));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(reportComparisonFacade.findCompatibleReport(EMAIL, report)).thenReturn(Optional.empty());

        mockMvc.perform(get("/reports/researcher/{email}/report/{reportId}/compare", EMAIL, "rep-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reports/researcher/" + EMAIL + "?report=rep-1"));

        verify(reportComparisonFacade, never()).buildComparison(any(), any(), any());
    }
}
