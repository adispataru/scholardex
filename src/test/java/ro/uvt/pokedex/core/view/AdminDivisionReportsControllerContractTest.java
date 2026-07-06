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
    void compareToParamIsPlumbedThroughToTheFacade() throws Exception {
        Instant compareTo = Instant.parse("2026-07-01T10:00:00Z");
        OrgUnitReportViewModel vm = sampleVm();
        when(divisionReportFacade.buildView(eq("div-1"), eq("rep-1"), eq(compareTo)))
                .thenReturn(Optional.of(vm));

        mockMvc.perform(get("/admin/divisions/div-1/reports/rep-1")
                        .param("compareTo", "2026-07-01T10:00:00Z"))
                .andExpect(status().isOk());
    }

    /** Realistic VM built through the real assembler over a fabricated rollup. */
    private static OrgUnitReportViewModel sampleVm() {
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
        ReportingDataEpochService epochService = mock(ReportingDataEpochService.class);
        when(epochService.currentEpochInfo()).thenReturn(Optional.empty());

        OrgUnitRunRollupService rollupService = new OrgUnitRunRollupService(runRepository, epochService);
        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(
                List.of(new OrgUnitRosterService.RosterMember(ana, "Computer Science")), report);
        OrgUnitReportViewModel vm = new OrgUnitReportViewAssembler(new ObjectMapper())
                .toViewModel("div-1", "FMI", report, rollup, List.of());
        assertFalse(vm.cellHeatClass().isEmpty());
        return vm;
    }
}
