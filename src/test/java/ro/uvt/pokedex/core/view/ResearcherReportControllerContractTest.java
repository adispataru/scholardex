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
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
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
    private UserIndividualReportRunService userIndividualReportRunService;
    @MockitoBean
    private UserIndividualReportRunRepository userIndividualReportRunRepository; // assembler dependency
    @MockitoBean
    private ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade reportExportFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.reporting.transfer.ReportImportRegistry reportImportRegistry; // assembler dep

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
        var exported = new ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade.ExportedReport(
                "xlsx-bytes".getBytes(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "FV Test.xlsx");
        when(reportExportFacade.exportRunOutcome(EMAIL, "rep-1", null, ReportFormat.XLSX, false))
                .thenReturn(ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade.ExportOutcome.success(exported));

        mockMvc.perform(get("/reports/researcher/{email}/report/{reportId}/export", EMAIL, "rep-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("FV Test.xlsx")));

        // Read-only: export must never force a refresh (no mutation of the researcher's data).
        verify(reportExportFacade).exportRunOutcome(EMAIL, "rep-1", null, ReportFormat.XLSX, false);
    }

    @Test
    void exportFailureMapsToHttpStatus() throws Exception {
        when(reportExportFacade.exportRunOutcome(EMAIL, "rep-1", null, ReportFormat.XLSX, false))
                .thenReturn(ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade.ExportOutcome.failure(
                        ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade.ExportFailureReason.NOT_READY,
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
        when(userReportFacade.buildReportScopedIndicatorDetail(EMAIL, "rep-1", "ind-1"))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(get("/reports/researcher/{email}/indicator/{id}/detail", EMAIL, "ind-1")
                        .param("report", "rep-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"indicatorId\":\"ind-1\"")))
                .andExpect(content().string(containsString("Paper A")));

        // Read-only: report-scoped compute only (no cache-writing getOrCreateLatest path exists here).
        verify(userReportFacade).buildReportScopedIndicatorDetail(EMAIL, "rep-1", "ind-1");
    }

    @Test
    void indicatorDetailNotFoundWhenComputeEmpty() throws Exception {
        when(userReportFacade.buildReportScopedIndicatorDetail(EMAIL, "rep-1", "ind-x"))
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
        when(userReportFacade.buildReportScopedIndicatorDetail(EMAIL, "rep-1", "ind-1"))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(get("/reports/researcher/{email}/indicator/{id}/citations", EMAIL, "ind-1")
                        .param("pub", "Cited Pub").param("report", "rep-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"pubTitle\":\"Cited Pub\"")))
                .andExpect(content().string(containsString("Citing 1")));
    }
}
