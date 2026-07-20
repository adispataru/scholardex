package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.OrgUnitRosterService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Org-unit-agnostic promotion-readiness board: the board itself only needs a unit name, a report, and
 * a roster — everything downstream ({@link OrgUnitRunRollupService} → {@link PromotionReadinessService})
 * is already unit-neutral. This service resolves the three unit flavors (division / department / group)
 * to that common shape so every unit dashboard can link the same board.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitPromotionBoardService {

    public enum OrgUnitType { DIVISION, DEPARTMENT, GROUP }

    public record PromotionBoardView(String unitName, IndividualReport report,
                                     PromotionReadinessService.PromotionBoard board) {}

    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final GroupRepository groupRepository;
    private final IndividualReportRepository individualReportRepository;
    private final OrgUnitRosterService orgUnitRosterService;
    private final OrgUnitRunRollupService orgUnitRunRollupService;
    private final PromotionReadinessService promotionReadinessService;

    /**
     * @param excludedCriteria criterion indices the head has toggled off for this view — see
     *   {@link PromotionReadinessService#build(IndividualReport, OrgUnitRunRollupService.OrgUnitRunRollup, Set)}.
     */
    public Optional<PromotionBoardView> build(OrgUnitType unitType, String unitId, String reportId,
                                              Set<Integer> excludedCriteria) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> unitName = resolveUnitName(unitType, unitId);
        if (unitName.isEmpty()) {
            return Optional.empty();
        }
        IndividualReport report = reportOpt.get();
        List<OrgUnitRosterService.RosterMember> roster = resolveRoster(unitType, unitId);
        OrgUnitRunRollupService.OrgUnitRunRollup rollup = orgUnitRunRollupService.rollup(roster, report, null);
        return Optional.of(new PromotionBoardView(unitName.get(), report,
                promotionReadinessService.build(report, rollup, excludedCriteria)));
    }

    private Optional<String> resolveUnitName(OrgUnitType unitType, String unitId) {
        return switch (unitType) {
            case DIVISION -> orgDivisionRepository.findById(unitId).map(OrgDivision::getName);
            case DEPARTMENT -> departmentRepository.findById(unitId).map(Department::getName);
            case GROUP -> groupRepository.findById(unitId).map(Group::getName);
        };
    }

    private List<OrgUnitRosterService.RosterMember> resolveRoster(OrgUnitType unitType, String unitId) {
        return switch (unitType) {
            case DIVISION -> orgUnitRosterService.divisionRoster(unitId);
            case DEPARTMENT -> orgUnitRosterService.departmentRoster(unitId);
            case GROUP -> orgUnitRosterService.groupRoster(unitId);
        };
    }
}
