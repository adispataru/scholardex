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
        String epochLastReason,
        /** Per-cell deltas vs a chosen point in time; null when the view is not in compare mode. */
        DeltaView delta,
        /** Recent batch-refresh events as compare-picker options (may be empty). */
        List<CompareOption> compareOptions,
        /** Position buckets in enum order (Unclassified last); empty buckets omitted. */
        List<PositionAggregateRow> positionAggregates,
        /** email → criterion index → heat CSS class; absent = no class (no run / no threshold / OTHER). */
        Map<String, Map<Integer, String>> cellHeatClass,
        DashboardTotals totals,
        /** Chart + CSV payload, JSON-serialized server-side ('<' escaped for inline script safety). */
        String dashboardJson
) {

    /** Metadata of a member's latest run; {@code stale} = run predates the last data-rebuild epoch bump. */
    public record RunMeta(Instant createdAt, boolean provisional, boolean stale,
                          UserIndividualReportRun.Status status) {}

    /**
     * Compare mode: each member's latest run vs their latest run before {@code compareTo}.
     * {@code deltasByEmail} holds only non-trivial per-criterion changes; members with a current
     * run but no baseline run are listed in {@code newMemberEmails}.
     */
    public record DeltaView(Instant compareTo,
                            Map<String, Map<Integer, Double>> deltasByEmail,
                            java.util.Set<String> newMemberEmails) {}

    /** A nameable compare point — the instant of a past batch refresh. */
    public record CompareOption(Instant instant, String label) {}

    /** One position bucket of the dashboard; {@code byCriterion} has one entry per report criterion. */
    public record PositionAggregateRow(String positionKey, String positionLabel, int researcherCount,
                                       List<PositionCriterionStats> byCriterion) {}

    /**
     * Position × criterion aggregate over members WITH a run ({@code count}). Threshold-derived
     * numbers ({@code metCount}/{@code metPercent}/{@code nearMissCount}) are null when no
     * threshold applies to the bucket (Unclassified, OTHER, or none defined for the position).
     */
    public record PositionCriterionStats(int criterionIndex, int count, Integer metCount, Double metPercent,
                                         Double medianScore, Integer nearMissCount, Double threshold) {}

    /** {@code overallMetPercent} spans all applicable (member, criterion) checks; null when none apply. */
    public record DashboardTotals(int researcherCount, int scoredCount, Integer overallMetPercent,
                                  int nearMissCount, int unclassifiedCount) {}
}
