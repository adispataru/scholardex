package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitReportViewAssembler;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisorCockpitServiceTest {

    @Mock private SupervisorWorkspaceService supervisorWorkspaceService;
    @Mock private ReportVisibilityService reportVisibilityService;
    @Mock private ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository individualReportRepository;
    @Mock private OrgUnitRosterService orgUnitRosterService;
    @Mock private OrgUnitRunRollupService orgUnitRunRollupService;
    @Mock private OrgUnitReportViewAssembler orgUnitReportViewAssembler;

    @InjectMocks private SupervisorCockpitService service;

    @Test
    void stripRollsUpADeDuplicatedRosterAndUnitsAreAttentionRanked() {
        OrgDivision fmi = division("div-fmi", "FMI");
        Department cs = department("dept-cs", "Computer Science");
        Group ml = group("g-ml", "ML lab");
        when(supervisorWorkspaceService.buildView("ana@uvt.ro")).thenReturn(
                new SupervisorWorkspaceService.SupervisorWorkspaceView(
                        List.of(fmi), List.of(cs), List.of(ml), Map.of(), Map.of(), Map.of()));

        IndividualReport report = report("rep-1", "FV Info 2026");
        when(reportVisibilityService.listVisibleReportsForDivision("div-fmi")).thenReturn(List.of(report));
        when(reportVisibilityService.listVisibleReportsForDepartment("dept-cs")).thenReturn(List.of(report));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));

        // "b" is affiliated with BOTH the division and the department it contains — the classic nesting overlap.
        var a = member("a@uvt.ro");
        var b = member("b@uvt.ro");
        var c = member("c@uvt.ro");
        var d = member("d@uvt.ro");
        when(orgUnitRosterService.divisionRoster("div-fmi")).thenReturn(List.of(a, b));
        when(orgUnitRosterService.departmentRoster("dept-cs")).thenReturn(List.of(b, c));
        when(orgUnitRosterService.groupRoster("g-ml")).thenReturn(List.of(d));

        // Per-unit rollups, by DISTINCT members needing a look: division 2 (a no-run, b stale),
        // department 1 (c provisional; b has a clean run here), group 0 (d clean).
        when(orgUnitRunRollupService.rollup(eq(List.of(a, b)), eq(report)))
                .thenReturn(rollupOf(List.of(row(a, null, false), row(b, run(false), true))));
        when(orgUnitRunRollupService.rollup(eq(List.of(b, c)), eq(report)))
                .thenReturn(rollupOf(List.of(row(b, run(false), false), row(c, run(true), false))));
        when(orgUnitRunRollupService.rollup(eq(List.of(d)), eq(report)))
                .thenReturn(rollupOf(List.of(row(d, run(false), false))));
        // The strip's rollup runs over the de-duplicated union [a,b,c,d]; its value only feeds the
        // assembler, which yields the strip totals below.
        when(orgUnitRunRollupService.rollup(eq(List.of(a, b, c, d)), eq(report)))
                .thenReturn(rollupOf(List.of()));
        when(orgUnitReportViewAssembler.toViewModel(any(), any(), eq(report), any(), any()))
                .thenReturn(viewModel(4, 60, 3, /*withoutRun*/ 3, /*provisional*/ 2, /*stale*/ 1));

        SupervisorCockpitService.SupervisorCockpitView view = service.buildView("ana@uvt.ro", null);

        // Strip: the assembler's totals surface, and onboarded % = (4-3)/4 = 25.
        assertEquals(4, view.strip().researcherCount());
        assertEquals(25, view.strip().onboardedPercent());
        assertEquals(60, view.strip().metPercent());

        // The strip rollup ran over the DE-DUPLICATED union (a,b,c,d = 4), not the 5-with-b-twice sum.
        ArgumentCaptor<List<OrgUnitRosterService.RosterMember>> rosterCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(orgUnitRunRollupService, org.mockito.Mockito.atLeastOnce())
                .rollup(rosterCaptor.capture(), eq(report));
        boolean sawDedupedUnion = rosterCaptor.getAllValues().stream().anyMatch(roster -> {
            long distinctEmails = roster.stream().map(m -> m.user().getEmail()).distinct().count();
            return roster.size() == 4 && distinctEmails == 4;
        });
        assertTrue(sawDedupedUnion, "strip should roll up 4 distinct members, not the double-counted 5");

        // Units are attention-ranked: division (6) first, department (2), group (0) last.
        List<SupervisorCockpitService.UnitHealthRow> rows = view.unitRows();
        assertEquals(List.of("FMI", "Computer Science", "ML lab"),
                rows.stream().map(SupervisorCockpitService.UnitHealthRow::unitName).toList());
        assertEquals(2, rows.get(0).attentionCount()); // division: a no-run + b stale
        assertEquals(1, rows.get(1).attentionCount()); // department: c provisional
        assertEquals(0, rows.get(2).attentionCount()); // group: clear
        assertEquals("/admin/groups/g-ml/reports/view/rep-1", rows.get(2).dashboardHref());
    }

    @Test
    void noHeadedUnitsYieldsAnEmptyView() {
        when(supervisorWorkspaceService.buildView("nobody@uvt.ro"))
                .thenReturn(SupervisorWorkspaceService.SupervisorWorkspaceView.empty());

        assertTrue(service.buildView("nobody@uvt.ro", null).isEmpty());
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private OrgUnitRosterService.RosterMember member(String email) {
        User u = new User();
        u.setEmail(email);
        return new OrgUnitRosterService.RosterMember(u, "");
    }

    private OrgUnitRunRollupService.RunSummary run(boolean provisional) {
        return new OrgUnitRunRollupService.RunSummary("run-1", null,
                ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun.Status.READY,
                provisional, Map.of(), null, null, null, List.of());
    }

    private OrgUnitRunRollupService.MemberRunRow row(OrgUnitRosterService.RosterMember m,
                                                     OrgUnitRunRollupService.RunSummary current, boolean stale) {
        return new OrgUnitRunRollupService.MemberRunRow(m.user(), "", current, stale, null);
    }

    private OrgUnitRunRollupService.OrgUnitRunRollup rollupOf(List<OrgUnitRunRollupService.MemberRunRow> rows) {
        int withoutRun = (int) rows.stream().filter(r -> r.current() == null).count();
        int provisional = (int) rows.stream().filter(r -> r.current() != null && r.current().provisional()).count();
        int stale = (int) rows.stream().filter(OrgUnitRunRollupService.MemberRunRow::stale).count();
        return new OrgUnitRunRollupService.OrgUnitRunRollup(
                rows, Map.of(), withoutRun, provisional, stale, null, null, null, null);
    }

    private OrgUnitReportViewModel viewModel(int researcherCount, Integer metPercent, int nearMiss,
                                             int withoutRun, int provisional, int stale) {
        return new OrgUnitReportViewModel(
                "supervisor-scope", "Your scope", null, List.of(), Map.of(), Map.of(), List.of(),
                Map.of(), Map.of(), withoutRun, provisional, stale, null, null, null, null, List.of(),
                List.of(), Map.of(),
                new OrgUnitReportViewModel.DashboardTotals(researcherCount, researcherCount - withoutRun,
                        metPercent, nearMiss, 0),
                "{}");
    }

    private static OrgDivision division(String id, String name) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private static Department department(String id, String name) {
        Department d = new Department();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private static Group group(String id, String name) {
        Group g = new Group();
        g.setId(id);
        g.setName(name);
        return g;
    }

    private static IndividualReport report(String id, String title) {
        IndividualReport r = new IndividualReport();
        r.setId(id);
        r.setTitle(title);
        return r;
    }
}
