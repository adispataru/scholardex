package ro.uvt.pokedex.core.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.DivisionReportFacade;
import ro.uvt.pokedex.core.service.application.OrgUnitRosterService;
import ro.uvt.pokedex.core.service.application.ReportingDataEpochService;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitReportViewAssembler;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDivisionReportsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminDivisionReportsControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DivisionReportFacade divisionReportFacade;

    @Test
    void reportViewRendersDashboardCardsHeatClassesAndJsonPayload() throws Exception {
        // Built before stubbing — sampleVm() stubs its own helper mocks.
        OrgUnitReportViewModel vm = sampleVm();
        when(divisionReportFacade.buildView(eq("div-1"), eq("rep-1"), isNull()))
                .thenReturn(Optional.of(vm));

        String html = mockMvc.perform(get("/admin/divisions/div-1/reports/rep-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Stat cards + dashboard sections
        assertTrue(html.contains("Met thresholds"));
        assertTrue(html.contains("Near misses"));
        assertTrue(html.contains("Researchers by position"));
        // Score cell heat class + numeric sort attribute
        assertTrue(html.contains("app-heat--met"));
        assertTrue(html.contains("data-order"));
        // Division views show the Department column
        assertTrue(html.contains("<th>Department</th>"));
        // The JSON payload script tag is emitted un-escaped
        assertTrue(html.contains("id=\"orgunit-dashboard-data\""));
        assertTrue(html.contains("\"metPercentByPosition\""));
    }

    @Test
    void compareModeRendersSignedDeltaBadgesOnScoreCells() throws Exception {
        // Regression: th:if outranks th:with on the same element, which once made the badge
        // silently never render — assert the actual markup, not just a 200.
        Instant compareTo = Instant.parse("2026-07-01T09:00:00Z");
        OrgUnitReportViewModel vm = sampleVm(compareTo);
        when(divisionReportFacade.buildView(eq("div-1"), eq("rep-1"), eq(compareTo)))
                .thenReturn(Optional.of(vm));

        String html = mockMvc.perform(get("/admin/divisions/div-1/reports/rep-1")
                        .param("compareTo", "2026-07-01T09:00:00Z"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Comparing each member"));
        // Current 6.0 vs baseline 2.0 → +4.00 badge on the score cell.
        assertTrue(html.matches("(?s).*badge badge-success ml-1\">\\+4[.,]00.*"));
    }

    private static OrgUnitReportViewModel sampleVm() {
        return sampleVm(null);
    }

    /** Realistic VM built through the real assembler over a fabricated rollup. */
    private static OrgUnitReportViewModel sampleVm(Instant compareTo) {
        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("CS 2026");
        AbstractReport.Criterion criterion = new AbstractReport.Criterion();
        criterion.setName("Articles");
        AbstractReport.Threshold threshold = new AbstractReport.Threshold();
        threshold.setPosition(Position.PROF_UNIV);
        threshold.setValue(5.0);
        criterion.setThresholds(List.of(threshold));
        report.setCriteria(List.of(criterion));

        User ana = new User();
        ana.setEmail("ana@uvt.ro");
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName("Ana");
        profile.setLastName("Pop");
        profile.setPosition(Position.PROF_UNIV);
        ana.setResearcherProfile(profile);

        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId("run-1");
        run.setUserEmail("ana@uvt.ro");
        run.setCreatedAt(Instant.parse("2026-07-01T10:00:00Z"));
        run.setCriteriaScores(new HashMap<>(Map.of(0, 6.0)));
        run.setStatus(UserIndividualReportRun.Status.READY);

        UserIndividualReportRunRepository runRepository = mock(UserIndividualReportRunRepository.class);
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.of(run));
        if (compareTo != null) {
            UserIndividualReportRun baseline = new UserIndividualReportRun();
            baseline.setId("run-0");
            baseline.setUserEmail("ana@uvt.ro");
            baseline.setCreatedAt(compareTo.minusSeconds(3600));
            baseline.setCriteriaScores(new HashMap<>(Map.of(0, 2.0)));
            baseline.setStatus(UserIndividualReportRun.Status.READY);
            when(runRepository.findTopByUserEmailAndReportDefinitionIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                    any(), any(), eq(compareTo))).thenReturn(Optional.of(baseline));
        }
        ReportingDataEpochService epochService = mock(ReportingDataEpochService.class);
        when(epochService.currentEpochInfo()).thenReturn(Optional.empty());

        OrgUnitRunRollupService rollupService = new OrgUnitRunRollupService(runRepository, epochService);
        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(
                List.of(new OrgUnitRosterService.RosterMember(ana, "Computer Science")), report, compareTo);
        OrgUnitReportViewModel vm = new OrgUnitReportViewAssembler(new ObjectMapper())
                .toViewModel("div-1", "FMI", report, rollup, List.of());
        assertFalse(vm.cellHeatClass().isEmpty());
        return vm;
    }

    @Test
    void promotionBoardRendersBandsGapsAndDisclaimer() throws Exception {
        User lect = new User();
        lect.setEmail("bob@uvt.ro");
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName("Bob");
        profile.setLastName("Ionescu");
        profile.setPosition(Position.LECT_UNIV);
        lect.setResearcherProfile(profile);
        OrgUnitRunRollupService.RunSummary run = new OrgUnitRunRollupService.RunSummary(
                "run-1", Instant.parse("2026-07-01T10:00:00Z"), UserIndividualReportRun.Status.READY,
                false, Map.of(0, 30.0), null, null, null, List.of());
        OrgUnitRunRollupService.MemberRunRow row =
                new OrgUnitRunRollupService.MemberRunRow(lect, "Computer Science", run, false, null);

        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("FV Info 2026");
        AbstractReport.Criterion criterion = new AbstractReport.Criterion();
        criterion.setName("Perspectiva B");
        report.setCriteria(List.of(criterion));

        var readiness = new ro.uvt.pokedex.core.service.application.reporting.PromotionReadinessService()
                .build(report, new OrgUnitRunRollupService.OrgUnitRunRollup(
                        List.of(row), Map.of(0, Map.of("CONF_UNIV", 32.0)), 0, 0, 0, null, null, null, null));
        when(divisionReportFacade.buildPromotionBoard(eq("div-1"), eq("rep-1"), org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(Optional.of(new DivisionReportFacade.PromotionBoardView("FMI", report, readiness)));

        String html = mockMvc.perform(get("/admin/divisions/div-1/reports/rep-1/promotions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Band headers + the honest-scope disclaimer
        assertTrue(html.contains("Meets next-position standards"));
        assertTrue(html.contains("Borderline"));
        assertTrue(html.contains("scientific minimum standards"));
        // 30 vs 32 → borderline row with the criterion gap and target position rendered
        assertTrue(html.contains("Bob Ionescu"));
        assertTrue(html.contains("LECT_UNIV"));
        assertTrue(html.contains("CONF_UNIV"));
        assertTrue(html.contains("Perspectiva B"));
        assertTrue(html.contains("0/1 criteria"));
        // Row links to the delegated individual report
        assertTrue(html.contains("/reports/researcher/bob@uvt.ro"));
    }

    @Test
    void promotionBoardRendersCriterionToggleChipsAndExclusionBanner() throws Exception {
        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("FV Info 2026");
        AbstractReport.Criterion b = new AbstractReport.Criterion();
        b.setName("Perspectiva B");
        AbstractReport.Criterion d = new AbstractReport.Criterion();
        d.setName("Perspectiva D");
        report.setCriteria(List.of(b, d));
        var emptyBoard = new ro.uvt.pokedex.core.service.application.reporting.PromotionReadinessService()
                .build(report, new OrgUnitRunRollupService.OrgUnitRunRollup(
                        List.of(), Map.of(), 0, 0, 0, null, null, null, null));
        when(divisionReportFacade.buildPromotionBoard(eq("div-1"), eq("rep-1"), org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(Optional.of(new DivisionReportFacade.PromotionBoardView("FMI", report, emptyBoard)));

        String html = mockMvc.perform(get("/admin/divisions/div-1/reports/rep-1/promotions")
                        .param("exclude", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Chip bar: both criteria render; the excluded one carries the struck-through modifier and its
        // toggle href re-includes it (back to the bare URL); the banner + reset link are present.
        assertTrue(html.contains("Perspectiva B"));
        assertTrue(html.contains("Perspectiva D"));
        assertTrue(html.contains("is-off"));
        assertTrue(html.contains("Buckets computed without 1 excluded criterion"));
        assertTrue(html.contains("?exclude=0,1") || html.contains("?exclude=0&#44;1")); // toggling B adds it to the set
        assertTrue(html.contains("/admin/divisions/div-1/reports/rep-1/promotions\">reset</a>")
                || html.contains(">reset<"));
    }

    @Test
    void promotionBoardLinkRendersOnDivisionReportView() throws Exception {
        OrgUnitReportViewModel vm = sampleVm();
        when(divisionReportFacade.buildView(eq("div-1"), eq("rep-1"), isNull()))
                .thenReturn(Optional.of(vm));

        String html = mockMvc.perform(get("/admin/divisions/div-1/reports/rep-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Promotion readiness"));
        assertTrue(html.contains("/admin/divisions/div-1/reports/rep-1/promotions"));
    }
}
