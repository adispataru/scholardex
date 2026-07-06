package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared view model for department- and division-level individual-report roll-ups. Scores are
 * each member's latest persisted {@link UserIndividualReportRun} — the numbers the researcher
 * sees in their own workspace — not a live recompute. Department uses it directly; Division also
 * fills in {@code departmentLabelByResearcher} to preserve the "researcher X belongs to Y
 * department" grouping while the scoring stays flat.
 */
public record OrgUnitReportViewModel(
        String unitId,
        String unitName,
        IndividualReport report,
        List<User> researchers,
        /** email → criterion index → score (from the member's latest run; absent = no run yet) */
        Map<String, Map<Integer, Double>> researcherScores,
        /** criterion index → position name → threshold value */
        Map<Integer, Map<String, Double>> criteriaThresholds,
        List<String> buildErrors,
        /** Only populated for division views — email → department name. Empty map for department views. */
        Map<String, String> departmentLabelByResearcher,
        /** email → latest-run metadata; absent key = the member has no run yet. */
        Map<String, RunMeta> runMetaByEmail,
        int membersWithoutRun,
        int provisionalCount,
        int staleCount,
        Instant oldestRunAt,
        /** When the reporting data last changed (epoch bump); null if no rebuild was ever recorded. */
        Instant epochUpdatedAt,
        String epochLastReason
) {

    /** Metadata of a member's latest run; {@code stale} = run predates the last data-rebuild epoch bump. */
    public record RunMeta(Instant createdAt, boolean provisional, boolean stale,
                          UserIndividualReportRun.Status status) {}
}
