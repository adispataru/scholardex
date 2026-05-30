package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;

import java.util.List;
import java.util.Map;

/**
 * Shared view model for department- and division-level individual-report runs.
 * Department uses it directly; Division also fills in {@code researchersByDepartment} to
 * preserve the "researcher X belongs to Y department" grouping while the scoring stays flat.
 */
public record OrgUnitReportViewModel(
        String unitId,
        String unitName,
        IndividualReport report,
        List<User> researchers,
        /** email → criterion index → score */
        Map<String, Map<Integer, Double>> researcherScores,
        /** criterion index → position name → threshold value */
        Map<Integer, Map<String, Double>> criteriaThresholds,
        List<String> buildErrors,
        /** Only populated for division views — email → department name. Empty map for department views. */
        Map<String, String> departmentLabelByResearcher
) {
}
