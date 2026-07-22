package ro.uvt.pokedex.core.service.application.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.OrgUnitRosterService;
import ro.uvt.pokedex.core.service.application.ReportComparisonFacade;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUnitReportComparisonServiceTest {

    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private IndividualReportRepository individualReportRepository;
    @Mock private OrgUnitRosterService orgUnitRosterService;
    @Mock private OrgUnitRunRollupService orgUnitRunRollupService;
    @Mock private ReportComparisonFacade reportComparisonFacade;

    @InjectMocks
    private OrgUnitReportComparisonService service;

    private IndividualReport olderReport() {
        IndividualReport r = new IndividualReport();
        r.setId("rep-2016");
        r.setTitle("FV Info 2016");
        r.setReportTypeKey("informatica-2016");
        r.setCriteria(List.of(criterion("Perspectiva B")));
        return r;
    }

    private IndividualReport newerReport() {
        IndividualReport r = new IndividualReport();
        r.setId("rep-2026");
        r.setTitle("FV Info 2026");
        r.setReportTypeKey("informatica-2026");
        r.setCriteria(List.of(criterion("Perspectiva B"), criterion("Publicații A*+A")));
        return r;
    }

    private AbstractReport.Criterion criterion(String name) {
        AbstractReport.Criterion c = new AbstractReport.Criterion();
        c.setName(name);
        c.setContributesToTotal(true);
        return c;
    }

    private User user(String email) {
        User u = new User();
        u.setEmail(email);
        return u;
    }

    private OrgUnitRunRollupService.RunSummary summary(Map<Integer, Double> scores, boolean provisional, double total) {
        return new OrgUnitRunRollupService.RunSummary(
                "run", null, UserIndividualReportRun.Status.READY, provisional, scores, total, null, null, List.of());
    }

    @Test
    void buildsRosterWideComparisonMatchedByCriterionName() {
        IndividualReport older = olderReport();
        IndividualReport newer = newerReport();
        when(individualReportRepository.findById("rep-2026")).thenReturn(Optional.of(newer));
        when(reportComparisonFacade.findCompatibleReport(newer)).thenReturn(Optional.of(older));
        when(reportComparisonFacade.isOlder(newer)).thenReturn(false);

        OrgDivision division = new OrgDivision();
        division.setName("FMI");
        when(orgDivisionRepository.findById("u-1")).thenReturn(Optional.of(division));

        User alice = user("alice@e-uvt.ro");
        User bob = user("bob@e-uvt.ro");
        List<OrgUnitRosterService.RosterMember> roster = List.of(
                new OrgUnitRosterService.RosterMember(alice, "CS Dept"),
                new OrgUnitRosterService.RosterMember(bob, "CS Dept"));
        when(orgUnitRosterService.divisionRoster("u-1")).thenReturn(roster);

        // Alice: older=30 (Perspectiva B idx 0), newer=40 (idx 0) + 12 (Publicații A*+A idx 1)
        var aliceOlderRow = new OrgUnitRunRollupService.MemberRunRow(alice, "CS Dept",
                summary(Map.of(0, 30.0), false, 30.0), false, null);
        var aliceNewerRow = new OrgUnitRunRollupService.MemberRunRow(alice, "CS Dept",
                summary(Map.of(0, 40.0, 1, 12.0), true, 40.0), false, null);
        // Bob: no older run, newer=20 (idx 0)
        var bobOlderRow = new OrgUnitRunRollupService.MemberRunRow(bob, "CS Dept", null, false, null);
        var bobNewerRow = new OrgUnitRunRollupService.MemberRunRow(bob, "CS Dept",
                summary(Map.of(0, 20.0), false, 20.0), false, null);

        var olderRollup = new OrgUnitRunRollupService.OrgUnitRunRollup(
                List.of(aliceOlderRow, bobOlderRow), Map.of(), 1, 0, 0, null, null, null, null);
        var newerRollup = new OrgUnitRunRollupService.OrgUnitRunRollup(
                List.of(aliceNewerRow, bobNewerRow), Map.of(), 0, 1, 0, null, null, null, null);
        when(orgUnitRunRollupService.rollup(roster, older)).thenReturn(olderRollup);
        when(orgUnitRunRollupService.rollup(roster, newer)).thenReturn(newerRollup);

        when(reportComparisonFacade.matchCriteriaColumns(older, newer)).thenReturn(List.of(
                new ReportComparisonFacade.CriterionColumn("Perspectiva B", 0, 0),
                new ReportComparisonFacade.CriterionColumn("Publicații A*+A", null, 1)));

        var view = service.build(OrgUnitPromotionBoardService.OrgUnitType.DIVISION, "u-1", "rep-2026");

        assertTrue(view.isPresent());
        var vm = view.get();
        assertEquals("FMI", vm.unitName());
        assertEquals("rep-2016", vm.olderReport().getId());
        assertEquals("rep-2026", vm.newerReport().getId());
        assertEquals(1, vm.membersWithoutOlderRun());
        assertEquals(0, vm.membersWithoutNewerRun());
        assertEquals(2, vm.columns().size());
        assertEquals(2, vm.rows().size());

        var aliceRow = vm.rows().get(0);
        assertEquals("alice@e-uvt.ro", aliceRow.user().getEmail());
        assertTrue(aliceRow.hasOlderRun());
        assertTrue(aliceRow.hasNewerRun());
        assertEquals(30.0, aliceRow.olderScoresByCriterion().get("Perspectiva B"));
        assertEquals(40.0, aliceRow.newerScoresByCriterion().get("Perspectiva B"));
        assertEquals(10.0, aliceRow.deltaByCriterion().get("Perspectiva B"));
        assertEquals(100.0 / 3.0, aliceRow.deltaPercentByCriterion().get("Perspectiva B"), 0.0001); // 10/30 * 100
        // one-sided column: no delta, no older score, no percent
        assertNull(aliceRow.olderScoresByCriterion().get("Publicații A*+A"));
        assertEquals(12.0, aliceRow.newerScoresByCriterion().get("Publicații A*+A"));
        assertNull(aliceRow.deltaByCriterion().get("Publicații A*+A"));
        assertNull(aliceRow.deltaPercentByCriterion().get("Publicații A*+A"));
        assertEquals(30.0, aliceRow.olderTotal());
        assertEquals(40.0, aliceRow.newerTotal());
        assertEquals(10.0, aliceRow.totalDelta());
        assertEquals(100.0 / 3.0, aliceRow.totalDeltaPercent(), 0.0001);
        assertTrue(aliceRow.newerProvisional());

        var bobRow = vm.rows().get(1);
        assertFalse(bobRow.hasOlderRun());
        assertTrue(bobRow.hasNewerRun());
        assertNull(bobRow.olderTotal());
        assertEquals(20.0, bobRow.newerTotal());
        assertNull(bobRow.totalDelta());
        assertNull(bobRow.totalDeltaPercent());
    }

    @Test
    void missingCompatibleReportYieldsEmpty() {
        IndividualReport newer = newerReport();
        when(individualReportRepository.findById("rep-2026")).thenReturn(Optional.of(newer));
        when(reportComparisonFacade.findCompatibleReport(newer)).thenReturn(Optional.empty());

        var view = service.build(OrgUnitPromotionBoardService.OrgUnitType.DIVISION, "u-1", "rep-2026");

        assertTrue(view.isEmpty());
    }

    @Test
    void missingReportYieldsEmpty() {
        when(individualReportRepository.findById("rep-x")).thenReturn(Optional.empty());
        var view = service.build(OrgUnitPromotionBoardService.OrgUnitType.DIVISION, "u-1", "rep-x");
        assertTrue(view.isEmpty());
    }
}
