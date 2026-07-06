package ro.uvt.pokedex.core.service.application.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.OrgUnitRosterService;
import ro.uvt.pokedex.core.service.application.ReportingDataEpochService;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUnitReportViewAssemblerTest {

    @Mock private UserIndividualReportRunRepository runRepository;
    @Mock private ReportingDataEpochService reportingDataEpochService;

    private OrgUnitRunRollupService rollupService;
    private final OrgUnitReportViewAssembler assembler = new OrgUnitReportViewAssembler(new ObjectMapper());

    @BeforeEach
    void wireRollupService() {
        rollupService = new OrgUnitRunRollupService(runRepository, reportingDataEpochService);
        lenient().when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
    }

    @Test
    void toViewModelMapsRowsAndPrefixesBuildErrorsWithTheMemberName() {
        IndividualReport report = report("rep-1");
        UserIndividualReportRun anaRun = run("run-ana", "ana@uvt.ro",
                Instant.parse("2026-07-01T10:00:00Z"), Map.of(0, 4.0), false);
        anaRun.setBuildErrors(List.of("No authors found"));
        anaRun.setStatus(UserIndividualReportRun.Status.PARTIAL);
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(anaRun));
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("dan@uvt.ro", "rep-1"))
                .thenReturn(Optional.empty());

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(
                List.of(member("ana@uvt.ro", "Ana", "Pop", null, "Computer Science"),
                        member("dan@uvt.ro", "Dan", "", null, "")), report);
        OrgUnitReportViewModel vm = assembler.toViewModel("div-1", "FMI", report, rollup, List.of());

        assertEquals("div-1", vm.unitId());
        assertEquals("FMI", vm.unitName());
        assertEquals(2, vm.researchers().size());
        assertEquals(Map.of(0, 4.0), vm.researcherScores().get("ana@uvt.ro"));
        assertFalse(vm.researcherScores().containsKey("dan@uvt.ro"));
        assertEquals(List.of("Ana Pop: No authors found"), vm.buildErrors());
        assertEquals("Computer Science", vm.departmentLabelByResearcher().get("ana@uvt.ro"));
        OrgUnitReportViewModel.RunMeta meta = vm.runMetaByEmail().get("ana@uvt.ro");
        assertEquals(UserIndividualReportRun.Status.PARTIAL, meta.status());
        assertFalse(vm.runMetaByEmail().containsKey("dan@uvt.ro"));
        assertEquals(1, vm.membersWithoutRun());
        assertNull(vm.delta());
        assertEquals(1, vm.totals().scoredCount());
    }

    @Test
    void compareModeResolvesBaselinesAndComputesPerCriterionDeltas() {
        IndividualReport report = report("rep-1");
        Instant compareTo = Instant.parse("2026-07-01T00:00:00Z");

        // Ana: current 4.0 / baseline 6.5 → delta -2.5 on criterion 0; the 0.001 wiggle on 1 is noise.
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-ana-2", "ana@uvt.ro",
                        Instant.parse("2026-07-03T10:00:00Z"), Map.of(0, 4.0, 1, 3.0), false)));
        when(runRepository.findTopByUserEmailAndReportDefinitionIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                "ana@uvt.ro", "rep-1", compareTo))
                .thenReturn(Optional.of(run("run-ana-1", "ana@uvt.ro",
                        Instant.parse("2026-06-15T10:00:00Z"), Map.of(0, 6.5, 1, 3.001), false)));
        // Dan: current run but nothing before the compare point → "new".
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("dan@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-dan", "dan@uvt.ro",
                        Instant.parse("2026-07-03T10:00:00Z"), Map.of(0, 1.0), false)));
        when(runRepository.findTopByUserEmailAndReportDefinitionIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                "dan@uvt.ro", "rep-1", compareTo))
                .thenReturn(Optional.empty());

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(
                List.of(member("ana@uvt.ro", "Ana", "", null, ""),
                        member("dan@uvt.ro", "Dan", "", null, "")), report, compareTo);
        OrgUnitReportViewModel vm = assembler.toViewModel("div-1", "FMI", report, rollup, List.of());

        assertNotNull(vm.delta());
        assertEquals(compareTo, vm.delta().compareTo());
        assertEquals(Map.of(0, -2.5), vm.delta().deltasByEmail().get("ana@uvt.ro"));
        assertFalse(vm.delta().deltasByEmail().containsKey("dan@uvt.ro"));
        assertEquals(Set.of("dan@uvt.ro"), vm.delta().newMemberEmails());
    }

    @Test
    void baselineCoversCriteriaMissingFromTheCurrentRun() {
        IndividualReport report = report("rep-1");
        Instant compareTo = Instant.parse("2026-07-01T00:00:00Z");
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-2", "ana@uvt.ro",
                        Instant.parse("2026-07-03T10:00:00Z"), Map.of(0, 4.0), false)));
        when(runRepository.findTopByUserEmailAndReportDefinitionIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                "ana@uvt.ro", "rep-1", compareTo))
                .thenReturn(Optional.of(run("run-1", "ana@uvt.ro",
                        Instant.parse("2026-06-15T10:00:00Z"), Map.of(0, 4.0, 1, 2.0), false)));

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(
                List.of(member("ana@uvt.ro", "Ana", "", null, "")), report, compareTo);
        OrgUnitReportViewModel vm = assembler.toViewModel("div-1", "FMI", report, rollup, List.of());

        // Criterion 1 disappeared from the current run → reads as a -2.0 drop.
        assertEquals(Map.of(1, -2.0), vm.delta().deltasByEmail().get("ana@uvt.ro"));
    }

    @Test
    void toCompareOptionsLabelsEventsWithTheirLabelOrActorAndMode() {
        OrgUnitReportRefreshEvent labeled = new OrgUnitReportRefreshEvent();
        labeled.setCreatedAt(Instant.parse("2026-07-01T10:00:00Z"));
        labeled.setLabel("before evaluation");
        // Legacy document: mode is null → reads as a refresh.
        OrgUnitReportRefreshEvent legacy = new OrgUnitReportRefreshEvent();
        legacy.setCreatedAt(Instant.parse("2026-06-01T10:00:00Z"));
        legacy.setTriggeredByEmail("admin@uvt.ro");
        legacy.setRefreshed(42);
        OrgUnitReportRefreshEvent provisional = new OrgUnitReportRefreshEvent();
        provisional.setCreatedAt(Instant.parse("2026-05-01T10:00:00Z"));
        provisional.setMode(OrgUnitReportRefreshEvent.Mode.PROVISIONAL);
        provisional.setTriggeredByEmail("admin@uvt.ro");
        provisional.setRefreshed(7);

        List<OrgUnitReportViewModel.CompareOption> options =
                assembler.toCompareOptions(List.of(labeled, legacy, provisional));

        assertEquals("before evaluation", options.get(0).label());
        assertEquals("Refresh by admin@uvt.ro (42 refreshed)", options.get(1).label());
        assertEquals("Provisional scoring by admin@uvt.ro (7 scored)", options.get(2).label());
    }

    @Test
    void positionAggregatesBucketInEnumOrderWithUnclassifiedLastAndThresholdAwareStats() {
        IndividualReport report = reportWithThreshold("rep-1", Position.PROF_UNIV, 5.0);
        stubRun("prof-met@uvt.ro", Map.of(0, 7.5));   // ratio 1.5 → met
        stubRun("prof-near@uvt.ro", Map.of(0, 4.5));  // exactly 0.9*t → near miss (inclusive)
        stubRun("other@uvt.ro", Map.of(0, 9.0));      // OTHER: counted, no met%
        stubRun("nopos@uvt.ro", Map.of(0, 1.0));      // null position → Unclassified

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(List.of(
                member("nopos@uvt.ro", "N", "P", null, ""),
                member("other@uvt.ro", "O", "T", Position.OTHER, ""),
                member("prof-met@uvt.ro", "A", "B", Position.PROF_UNIV, ""),
                member("prof-near@uvt.ro", "C", "D", Position.PROF_UNIV, "")), report);
        OrgUnitReportViewModel vm = assembler.toViewModel("u", "U", report, rollup, List.of());

        // Enum order first (PROF_UNIV before OTHER), synthetic Unclassified last.
        assertEquals(List.of("PROF_UNIV", "OTHER", "UNCLASSIFIED"),
                vm.positionAggregates().stream().map(OrgUnitReportViewModel.PositionAggregateRow::positionKey).toList());
        assertEquals("Unclassified", vm.positionAggregates().get(2).positionLabel());

        OrgUnitReportViewModel.PositionCriterionStats prof = vm.positionAggregates().get(0).byCriterion().get(0);
        assertEquals(2, prof.count());
        assertEquals(1, prof.metCount());
        assertEquals(50.0, prof.metPercent());
        assertEquals(1, prof.nearMissCount());
        assertEquals(6.0, prof.medianScore());
        assertEquals(5.0, prof.threshold());

        // OTHER and Unclassified report count + median but no threshold-derived stats.
        OrgUnitReportViewModel.PositionCriterionStats other = vm.positionAggregates().get(1).byCriterion().get(0);
        assertNull(other.metPercent());
        assertNull(other.threshold());
        assertEquals(9.0, other.medianScore());

        assertEquals(4, vm.totals().researcherCount());
        assertEquals(4, vm.totals().scoredCount());
        assertEquals(50, vm.totals().overallMetPercent());
        assertEquals(1, vm.totals().nearMissCount());
        assertEquals(1, vm.totals().unclassifiedCount());
    }

    @Test
    void nearMissIsInclusiveAtNinetyPercentAndExclusiveAtTheThreshold() {
        IndividualReport report = reportWithThreshold("rep-1", Position.PROF_UNIV, 10.0);
        stubRun("at-threshold@uvt.ro", Map.of(0, 10.0)); // met, not near
        stubRun("at-ninety@uvt.ro", Map.of(0, 9.0));     // near (inclusive)
        stubRun("below-ninety@uvt.ro", Map.of(0, 8.99)); // neither

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(List.of(
                member("at-threshold@uvt.ro", "A", "A", Position.PROF_UNIV, ""),
                member("at-ninety@uvt.ro", "B", "B", Position.PROF_UNIV, ""),
                member("below-ninety@uvt.ro", "C", "C", Position.PROF_UNIV, "")), report);
        OrgUnitReportViewModel vm = assembler.toViewModel("u", "U", report, rollup, List.of());

        OrgUnitReportViewModel.PositionCriterionStats stats = vm.positionAggregates().get(0).byCriterion().get(0);
        assertEquals(1, stats.metCount());
        assertEquals(1, stats.nearMissCount());
    }

    @Test
    void medianHandlesOddAndEvenCounts() {
        IndividualReport report = reportWithThreshold("rep-1", Position.PROF_UNIV, 100.0);
        stubRun("a@uvt.ro", Map.of(0, 1.0));
        stubRun("b@uvt.ro", Map.of(0, 5.0));
        stubRun("c@uvt.ro", Map.of(0, 3.0));

        OrgUnitRunRollupService.OrgUnitRunRollup odd = rollupService.rollup(List.of(
                member("a@uvt.ro", "A", "A", Position.PROF_UNIV, ""),
                member("b@uvt.ro", "B", "B", Position.PROF_UNIV, ""),
                member("c@uvt.ro", "C", "C", Position.PROF_UNIV, "")), report);
        assertEquals(3.0, assembler.toViewModel("u", "U", report, odd, List.of())
                .positionAggregates().get(0).byCriterion().get(0).medianScore());

        OrgUnitRunRollupService.OrgUnitRunRollup even = rollupService.rollup(List.of(
                member("a@uvt.ro", "A", "A", Position.PROF_UNIV, ""),
                member("b@uvt.ro", "B", "B", Position.PROF_UNIV, "")), report);
        assertEquals(3.0, assembler.toViewModel("u", "U", report, even, List.of())
                .positionAggregates().get(0).byCriterion().get(0).medianScore());
    }

    @Test
    void heatClassesBucketByScoreThresholdRatioAndSkipOtherAndMissingThresholds() {
        IndividualReport report = reportWithThreshold("rep-1", Position.PROF_UNIV, 10.0);
        stubRun("high@uvt.ro", Map.of(0, 15.0));
        stubRun("met@uvt.ro", Map.of(0, 10.0));
        stubRun("near@uvt.ro", Map.of(0, 9.0));
        stubRun("below@uvt.ro", Map.of(0, 5.0));
        stubRun("far@uvt.ro", Map.of(0, 4.99));
        stubRun("other@uvt.ro", Map.of(0, 15.0));
        stubRun("lect@uvt.ro", Map.of(0, 15.0)); // no LECT_UNIV threshold defined → no class

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(List.of(
                member("high@uvt.ro", "A", "A", Position.PROF_UNIV, ""),
                member("met@uvt.ro", "B", "B", Position.PROF_UNIV, ""),
                member("near@uvt.ro", "C", "C", Position.PROF_UNIV, ""),
                member("below@uvt.ro", "D", "D", Position.PROF_UNIV, ""),
                member("far@uvt.ro", "E", "E", Position.PROF_UNIV, ""),
                member("other@uvt.ro", "F", "F", Position.OTHER, ""),
                member("lect@uvt.ro", "G", "G", Position.LECT_UNIV, "")), report);
        OrgUnitReportViewModel vm = assembler.toViewModel("u", "U", report, rollup, List.of());

        assertEquals("app-heat--high", vm.cellHeatClass().get("high@uvt.ro").get(0));
        assertEquals("app-heat--met", vm.cellHeatClass().get("met@uvt.ro").get(0));
        assertEquals("app-heat--near", vm.cellHeatClass().get("near@uvt.ro").get(0));
        assertEquals("app-heat--below", vm.cellHeatClass().get("below@uvt.ro").get(0));
        assertEquals("app-heat--far", vm.cellHeatClass().get("far@uvt.ro").get(0));
        assertFalse(vm.cellHeatClass().containsKey("other@uvt.ro"));
        assertFalse(vm.cellHeatClass().containsKey("lect@uvt.ro"));
    }

    @Test
    void dashboardJsonEscapesScriptBreakoutAndCarriesRowsForCsv() {
        IndividualReport report = report("rep-1");
        AbstractReport.Criterion hostile = new AbstractReport.Criterion();
        hostile.setName("</script><script>alert(1)</script>");
        report.setCriteria(List.of(hostile));
        stubRun("ana@uvt.ro", Map.of(0, 4.0));

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(List.of(
                member("ana@uvt.ro", "Ana", "Pop", Position.PROF_UNIV, "CS")), report);
        OrgUnitReportViewModel vm = assembler.toViewModel("u", "U", report, rollup, List.of());

        assertFalse(vm.dashboardJson().contains("</script"));
        assertTrue(vm.dashboardJson().contains("\\u003c/script"));
        assertTrue(vm.dashboardJson().contains("\"Ana Pop\""));
        assertTrue(vm.dashboardJson().contains("\"CS\""));
    }

    private void stubRun(String email, Map<Integer, Double> criteriaScores) {
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(email, "rep-1"))
                .thenReturn(Optional.of(run("run-" + email, email,
                        Instant.parse("2026-07-01T10:00:00Z"), criteriaScores, false)));
    }

    private static OrgUnitRosterService.RosterMember member(String email, String firstName, String lastName,
                                                            Position position, String label) {
        User u = new User();
        u.setEmail(email);
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(firstName);
        p.setLastName(lastName);
        p.setPosition(position);
        u.setResearcherProfile(p);
        return new OrgUnitRosterService.RosterMember(u, label);
    }

    private static UserIndividualReportRun run(String id, String email, Instant createdAt,
                                               Map<Integer, Double> criteriaScores, boolean provisional) {
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId(id);
        run.setUserEmail(email);
        run.setCreatedAt(createdAt);
        run.setCriteriaScores(new HashMap<>(criteriaScores));
        run.setProvisional(provisional);
        run.setStatus(UserIndividualReportRun.Status.READY);
        return run;
    }

    private static IndividualReport report(String id) {
        IndividualReport r = new IndividualReport();
        r.setId(id);
        r.setTitle("Test report");
        return r;
    }

    private static IndividualReport reportWithThreshold(String id, Position position, double value) {
        IndividualReport r = report(id);
        AbstractReport.Criterion criterion = new AbstractReport.Criterion();
        criterion.setName("Articles");
        AbstractReport.Threshold threshold = new AbstractReport.Threshold();
        threshold.setPosition(position);
        threshold.setValue(value);
        criterion.setThresholds(List.of(threshold));
        r.setCriteria(List.of(criterion));
        return r;
    }
}
