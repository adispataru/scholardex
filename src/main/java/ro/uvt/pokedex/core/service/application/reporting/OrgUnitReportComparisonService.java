package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.OrgUnitRosterService;
import ro.uvt.pokedex.core.service.application.ReportComparisonFacade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Org-unit-agnostic roster-wide comparison between a report and its compatible predecessor (see
 * {@link ReportComparisonFacade}) — the whole-cohort counterpart of the single-researcher compare
 * page. Mirrors {@link OrgUnitPromotionBoardService}'s unit-resolution shape: only a unit name and
 * a roster are unit-specific, everything downstream ({@link OrgUnitRunRollupService},
 * {@link ReportComparisonFacade#matchCriteriaColumns}) is already unit-neutral.
 *
 * <p>Row-major, mirroring {@code OrgUnitReportViewModel}'s existing table shape: {@link #columns}
 * (from {@link ReportComparisonFacade#matchCriteriaColumns}) drives the header once; each row then
 * carries its own scores keyed by criterion NAME, the same key used for the header, so the
 * template never needs an index to stay in sync across header vs. body iteration.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitReportComparisonService {

    public record MemberComparisonRow(User user, String departmentLabel,
                                      boolean hasOlderRun, boolean hasNewerRun,
                                      boolean olderProvisional, boolean newerProvisional,
                                      /** criterion name → score; only keys present in that side's report. */
                                      Map<String, Double> olderScoresByCriterion,
                                      Map<String, Double> newerScoresByCriterion,
                                      /** criterion name → delta; only present when BOTH sides have that criterion + a run. */
                                      Map<String, Double> deltaByCriterion,
                                      Double olderTotal, Double newerTotal, Double totalDelta) {
    }

    public record OrgUnitReportComparisonView(String unitName, IndividualReport olderReport, IndividualReport newerReport,
                                              List<ReportComparisonFacade.CriterionColumn> columns,
                                              List<MemberComparisonRow> rows,
                                              int membersWithoutOlderRun, int membersWithoutNewerRun) {
    }

    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final GroupRepository groupRepository;
    private final IndividualReportRepository individualReportRepository;
    private final OrgUnitRosterService orgUnitRosterService;
    private final OrgUnitRunRollupService orgUnitRunRollupService;
    private final ReportComparisonFacade reportComparisonFacade;

    public Optional<OrgUnitReportComparisonView> build(OrgUnitPromotionBoardService.OrgUnitType unitType, String unitId, String reportId) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }
        IndividualReport report = reportOpt.get();
        Optional<IndividualReport> compatibleOpt = reportComparisonFacade.findCompatibleReport(report);
        if (compatibleOpt.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> unitName = resolveUnitName(unitType, unitId);
        if (unitName.isEmpty()) {
            return Optional.empty();
        }

        boolean reportIsOlder = reportComparisonFacade.isOlder(report);
        IndividualReport olderReport = reportIsOlder ? report : compatibleOpt.get();
        IndividualReport newerReport = reportIsOlder ? compatibleOpt.get() : report;

        List<OrgUnitRosterService.RosterMember> roster = resolveRoster(unitType, unitId);
        OrgUnitRunRollupService.OrgUnitRunRollup olderRollup = orgUnitRunRollupService.rollup(roster, olderReport);
        OrgUnitRunRollupService.OrgUnitRunRollup newerRollup = orgUnitRunRollupService.rollup(roster, newerReport);
        Map<String, OrgUnitRunRollupService.MemberRunRow> olderByEmail = indexByEmail(olderRollup.rows());
        Map<String, OrgUnitRunRollupService.MemberRunRow> newerByEmail = indexByEmail(newerRollup.rows());

        List<ReportComparisonFacade.CriterionColumn> columns =
                reportComparisonFacade.matchCriteriaColumns(olderReport, newerReport);

        List<MemberComparisonRow> rows = new ArrayList<>();
        for (OrgUnitRosterService.RosterMember member : roster) {
            String email = member.user().getEmail();
            OrgUnitRunRollupService.RunSummary olderRun = currentRun(olderByEmail.get(email));
            OrgUnitRunRollupService.RunSummary newerRun = currentRun(newerByEmail.get(email));

            Map<String, Double> olderScores = new LinkedHashMap<>();
            Map<String, Double> newerScores = new LinkedHashMap<>();
            Map<String, Double> deltas = new LinkedHashMap<>();
            for (ReportComparisonFacade.CriterionColumn col : columns) {
                Double os = (olderRun != null && col.olderIndex() != null)
                        ? olderRun.criteriaScores().getOrDefault(col.olderIndex(), 0.0) : null;
                Double ns = (newerRun != null && col.newerIndex() != null)
                        ? newerRun.criteriaScores().getOrDefault(col.newerIndex(), 0.0) : null;
                if (os != null) olderScores.put(col.name(), os);
                if (ns != null) newerScores.put(col.name(), ns);
                if (os != null && ns != null) deltas.put(col.name(), ns - os);
            }

            Double olderTotal = olderRun != null ? olderRun.total() : null;
            Double newerTotal = newerRun != null ? newerRun.total() : null;
            Double totalDelta = (olderTotal != null && newerTotal != null) ? newerTotal - olderTotal : null;

            rows.add(new MemberComparisonRow(member.user(), member.departmentLabel(),
                    olderRun != null, newerRun != null,
                    olderRun != null && olderRun.provisional(), newerRun != null && newerRun.provisional(),
                    olderScores, newerScores, deltas, olderTotal, newerTotal, totalDelta));
        }

        return Optional.of(new OrgUnitReportComparisonView(unitName.get(), olderReport, newerReport, columns, rows,
                olderRollup.membersWithoutRun(), newerRollup.membersWithoutRun()));
    }

    private OrgUnitRunRollupService.RunSummary currentRun(OrgUnitRunRollupService.MemberRunRow row) {
        return row == null ? null : row.current();
    }

    private Map<String, OrgUnitRunRollupService.MemberRunRow> indexByEmail(List<OrgUnitRunRollupService.MemberRunRow> rows) {
        Map<String, OrgUnitRunRollupService.MemberRunRow> byEmail = new HashMap<>();
        for (OrgUnitRunRollupService.MemberRunRow row : rows) {
            byEmail.put(row.user().getEmail(), row);
        }
        return byEmail;
    }

    private Optional<String> resolveUnitName(OrgUnitPromotionBoardService.OrgUnitType unitType, String unitId) {
        return switch (unitType) {
            case DIVISION -> orgDivisionRepository.findById(unitId).map(OrgDivision::getName);
            case DEPARTMENT -> departmentRepository.findById(unitId).map(Department::getName);
            case GROUP -> groupRepository.findById(unitId).map(Group::getName);
        };
    }

    private List<OrgUnitRosterService.RosterMember> resolveRoster(OrgUnitPromotionBoardService.OrgUnitType unitType, String unitId) {
        return switch (unitType) {
            case DIVISION -> orgUnitRosterService.divisionRoster(unitId);
            case DEPARTMENT -> orgUnitRosterService.departmentRoster(unitId);
            case GROUP -> orgUnitRosterService.groupRoster(unitId);
        };
    }
}
