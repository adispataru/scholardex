package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitReportViewAssembler;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the SUPERVISOR cockpit at {@code /supervisor}: a health strip and an attention-ranked list
 * of the units a leader heads, for one chosen report. Reuses the run-sourced org-unit roll-up path —
 * no live recompute — so every number is the same score the researcher sees in their own workspace.
 *
 * <p>The aggregate strip is computed over a <em>de-duplicated</em> roster (the union of every headed
 * unit's roster, distinct by email): a supervisor who heads a division implicitly heads its
 * departments, so summing per-unit totals would multi-count people. The per-unit rows, by contrast,
 * are intentionally each scoped to their own dashboard — an overlap between a division row and a
 * department row beneath it is expected drill-down, not an error.</p>
 */
@Service
@RequiredArgsConstructor
public class SupervisorCockpitService {

    private final SupervisorWorkspaceService supervisorWorkspaceService;
    private final ReportVisibilityService reportVisibilityService;
    private final IndividualReportRepository individualReportRepository;
    private final OrgUnitRosterService orgUnitRosterService;
    private final OrgUnitRunRollupService orgUnitRunRollupService;
    private final OrgUnitReportViewAssembler orgUnitReportViewAssembler;

    public SupervisorCockpitView buildView(String userId, String requestedReportId) {
        SupervisorWorkspaceService.SupervisorWorkspaceView units = supervisorWorkspaceService.buildView(userId);
        if (units.isEmpty()) {
            return SupervisorCockpitView.empty();
        }

        List<ReportOption> reportOptions = resolveReportOptions(units);
        if (reportOptions.isEmpty()) {
            // Heads units but no report is defined/visible anywhere — show the directory without a strip.
            return new SupervisorCockpitView(true, List.of(), null, null, null,
                    buildUnitRows(units, null));
        }

        IndividualReport selected = selectReport(reportOptions, requestedReportId);
        CockpitStrip strip = buildStrip(units, selected);
        List<UnitHealthRow> unitRows = buildUnitRows(units, selected);
        return new SupervisorCockpitView(true, reportOptions, selected.getId(), selected.getTitle(),
                strip, unitRows);
    }

    /** Candidate reports = union of the reports visible to the headed divisions and departments. */
    private List<ReportOption> resolveReportOptions(SupervisorWorkspaceService.SupervisorWorkspaceView units) {
        Map<String, IndividualReport> byId = new LinkedHashMap<>();
        for (OrgDivision division : units.divisions()) {
            for (IndividualReport r : reportVisibilityService.listVisibleReportsForDivision(division.getId())) {
                if (r.getId() != null) byId.putIfAbsent(r.getId(), r);
            }
        }
        for (Department department : units.departments()) {
            for (IndividualReport r : reportVisibilityService.listVisibleReportsForDepartment(department.getId())) {
                if (r.getId() != null) byId.putIfAbsent(r.getId(), r);
            }
        }
        // Group-only heads (or divisions with no explicit selection) still need something to roll up.
        if (byId.isEmpty() && !units.groups().isEmpty()) {
            for (IndividualReport r : individualReportRepository.findAll()) {
                if (r.getId() != null) byId.putIfAbsent(r.getId(), r);
            }
        }
        return byId.values().stream()
                .sorted(Comparator.comparing(r -> nullSafe(r.getTitle())))
                .map(r -> new ReportOption(r.getId(), r.getTitle()))
                .toList();
    }

    private IndividualReport selectReport(List<ReportOption> options, String requestedReportId) {
        String targetId = options.stream().anyMatch(o -> o.id().equals(requestedReportId))
                ? requestedReportId
                : options.get(0).id();
        // Options are already validated ids; the report must exist to have been listed.
        return individualReportRepository.findById(targetId).orElseThrow();
    }

    /** One aggregate rollup over the de-duplicated roster of everyone the supervisor oversees. */
    private CockpitStrip buildStrip(SupervisorWorkspaceService.SupervisorWorkspaceView units,
                                    IndividualReport report) {
        List<OrgUnitRosterService.RosterMember> roster = dedupedRoster(units);
        OrgUnitRunRollupService.OrgUnitRunRollup rollup = orgUnitRunRollupService.rollup(roster, report);
        OrgUnitReportViewModel vm = orgUnitReportViewAssembler.toViewModel(
                "supervisor-scope", "Your scope", report, rollup, List.of());
        int researcherCount = vm.totals().researcherCount();
        int withRun = Math.max(0, researcherCount - vm.membersWithoutRun());
        Integer onboardedPercent = researcherCount > 0 ? Math.round(100f * withRun / researcherCount) : null;
        return new CockpitStrip(
                researcherCount,
                withRun,
                onboardedPercent,
                vm.totals().overallMetPercent(),
                vm.totals().nearMissCount(),
                vm.provisionalCount(),
                vm.staleCount());
    }

    /** Distinct-by-email union of every headed unit's roster — avoids multi-counting nested units. */
    private List<OrgUnitRosterService.RosterMember> dedupedRoster(
            SupervisorWorkspaceService.SupervisorWorkspaceView units) {
        Map<String, OrgUnitRosterService.RosterMember> byEmail = new LinkedHashMap<>();
        List<OrgUnitRosterService.RosterMember> all = new ArrayList<>();
        for (OrgDivision division : units.divisions()) {
            all.addAll(orgUnitRosterService.divisionRoster(division.getId()));
        }
        for (Department department : units.departments()) {
            all.addAll(orgUnitRosterService.departmentRoster(department.getId()));
        }
        for (Group group : units.groups()) {
            all.addAll(orgUnitRosterService.groupRoster(group.getId()));
        }
        for (OrgUnitRosterService.RosterMember member : all) {
            String email = member.user() != null ? member.user().getEmail() : null;
            if (email != null) byEmail.putIfAbsent(email, member);
        }
        return new ArrayList<>(byEmail.values());
    }

    /** One attention-ranked row per headed unit, each deep-linking to its own dashboard. */
    private List<UnitHealthRow> buildUnitRows(SupervisorWorkspaceService.SupervisorWorkspaceView units,
                                              IndividualReport report) {
        List<UnitHealthRow> rows = new ArrayList<>();
        for (OrgDivision division : units.divisions()) {
            rows.add(unitRow("division", division.getId(), division.getName(),
                    "/admin/divisions/" + division.getId() + "/reports/"
                            + (report != null ? report.getId() : ""),
                    orgUnitRosterService.divisionRoster(division.getId()), report));
        }
        for (Department department : units.departments()) {
            rows.add(unitRow("department", department.getId(), department.getName(),
                    "/admin/departments/" + department.getId() + "/reports/"
                            + (report != null ? report.getId() : ""),
                    orgUnitRosterService.departmentRoster(department.getId()), report));
        }
        for (Group group : units.groups()) {
            rows.add(unitRow("group", group.getId(), group.getName(),
                    "/admin/groups/" + group.getId() + "/reports/view/"
                            + (report != null ? report.getId() : ""),
                    orgUnitRosterService.groupRoster(group.getId()), report));
        }
        // Neediest first, then by name — puts the unit a leader should click at the top.
        rows.sort(Comparator.comparingInt(UnitHealthRow::attentionCount).reversed()
                .thenComparing(r -> nullSafe(r.name())));
        return rows;
    }

    private UnitHealthRow unitRow(String unitType, String unitId, String unitName, String dashboardHref,
                                  List<OrgUnitRosterService.RosterMember> roster, IndividualReport report) {
        int researcherCount = roster.size();
        int withoutRun = 0;
        int stale = 0;
        int provisional = 0;
        int attention = 0;
        if (report != null) {
            OrgUnitRunRollupService.OrgUnitRunRollup rollup = orgUnitRunRollupService.rollup(roster, report);
            withoutRun = rollup.membersWithoutRun();
            stale = rollup.staleCount();
            provisional = rollup.provisionalCount();
            // Distinct people needing a look, NOT the sum of issue counts — a provisional run is usually
            // also stale, so summing double-counts the same member (and can exceed the headcount).
            for (OrgUnitRunRollupService.MemberRunRow row : rollup.rows()) {
                boolean noRun = row.current() == null;
                boolean isProvisional = row.current() != null && row.current().provisional();
                if (noRun || row.stale() || isProvisional) {
                    attention++;
                }
            }
        }
        return new UnitHealthRow(unitType, unitId, unitName, dashboardHref,
                researcherCount, withoutRun, stale, provisional, attention);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // ── View records ─────────────────────────────────────────────────────────

    public record SupervisorCockpitView(
            boolean hasUnits,
            List<ReportOption> reportOptions,
            String selectedReportId,
            String selectedReportTitle,
            CockpitStrip strip,
            List<UnitHealthRow> unitRows) {

        public static SupervisorCockpitView empty() {
            return new SupervisorCockpitView(false, List.of(), null, null, null, List.of());
        }

        public boolean isEmpty() {
            return !hasUnits;
        }

        /** A report was resolvable and the strip was computed. */
        public boolean hasStrip() {
            return strip != null;
        }
    }

    public record ReportOption(String id, String title) {}

    /** Aggregate health over the de-duplicated scope; {@code metPercent} is null when no threshold applies. */
    public record CockpitStrip(int researcherCount, int withRunCount, Integer onboardedPercent,
                               Integer metPercent, int nearMissCount, int provisionalCount, int staleCount) {}

    /** One headed unit's summary row; {@code attentionCount} = without-run + stale + provisional. */
    public record UnitHealthRow(String unitType, String unitId, String unitName, String dashboardHref,
                                int researcherCount, int membersWithoutRun, int staleCount,
                                int provisionalCount, int attentionCount) {
        public String name() {
            return unitName;
        }
    }
}
