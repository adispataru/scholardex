package ro.uvt.pokedex.core.service.application.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService.MemberRunRow;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService.OrgUnitRunRollup;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService.RunSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Packs an {@link OrgUnitRunRollup} into the Thymeleaf-ready {@link OrgUnitReportViewModel}:
 * per-member scores and run metadata, compare-mode deltas, and the position-based dashboard
 * (aggregates by {@link Position}, score/threshold heat classes, and the JSON payload feeding
 * the charts + CSV export). All numbers are precomputed here — templates only do null-checked
 * lookups.
 */
@Component
@RequiredArgsConstructor
public class OrgUnitReportViewAssembler {

    /** Deltas smaller than the 2-decimal display precision are treated as unchanged. */
    private static final double DELTA_DISPLAY_EPSILON = 0.005;
    /** A score within 10% below its threshold counts as a near miss. */
    static final double NEAR_MISS_MARGIN = 0.10;
    /** Synthetic bucket for members without a position. */
    static final String UNCLASSIFIED_KEY = "UNCLASSIFIED";

    private final ObjectMapper objectMapper;

    public OrgUnitReportViewModel toViewModel(String unitId, String unitName, IndividualReport report,
                                              OrgUnitRunRollup rollup,
                                              List<OrgUnitReportViewModel.CompareOption> compareOptions) {
        List<User> researchers = new ArrayList<>();
        Map<String, Map<Integer, Double>> scoresByEmail = new LinkedHashMap<>();
        Map<String, String> departmentLabelByResearcher = new LinkedHashMap<>();
        Map<String, OrgUnitReportViewModel.RunMeta> runMetaByEmail = new LinkedHashMap<>();
        List<String> buildErrors = new ArrayList<>();
        for (MemberRunRow row : rollup.rows()) {
            User user = row.user();
            researchers.add(user);
            if (row.departmentLabel() != null && !row.departmentLabel().isBlank()) {
                departmentLabelByResearcher.put(user.getEmail(), row.departmentLabel());
            }
            RunSummary run = row.current();
            if (run == null) continue;
            scoresByEmail.put(user.getEmail(), run.criteriaScores());
            runMetaByEmail.put(user.getEmail(), new OrgUnitReportViewModel.RunMeta(
                    run.createdAt(), run.provisional(), row.stale(), run.status()));
            for (String err : run.buildErrors()) {
                buildErrors.add(user.getResearcherProfile().getName().trim() + ": " + err);
            }
        }
        Dashboard dashboard = buildDashboard(report, rollup);
        return new OrgUnitReportViewModel(unitId, unitName, report, researchers, scoresByEmail,
                rollup.criteriaThresholds(), buildErrors, departmentLabelByResearcher, runMetaByEmail,
                rollup.membersWithoutRun(), rollup.provisionalCount(), rollup.staleCount(),
                rollup.oldestRunAt(), rollup.epochUpdatedAt(), rollup.epochLastReason(),
                toDeltaView(rollup), compareOptions == null ? List.of() : compareOptions,
                dashboard.positionAggregates(), dashboard.cellHeatClass(), dashboard.totals(),
                dashboard.json());
    }

    /** Labels the last batch-refresh events as picker options for the compare form. */
    public List<OrgUnitReportViewModel.CompareOption> toCompareOptions(List<OrgUnitReportRefreshEvent> events) {
        return events.stream()
                .filter(e -> e.getCreatedAt() != null)
                .map(e -> new OrgUnitReportViewModel.CompareOption(e.getCreatedAt(),
                        e.getLabel() != null ? e.getLabel()
                                : "Refresh by " + e.getTriggeredByEmail() + " (" + e.getRefreshed() + " refreshed)"))
                .toList();
    }

    // ---------------------------------------------------------------- deltas

    private static OrgUnitReportViewModel.DeltaView toDeltaView(OrgUnitRunRollup rollup) {
        if (rollup.compareTo() == null) return null;
        Map<String, Map<Integer, Double>> deltasByEmail = new LinkedHashMap<>();
        Set<String> newMemberEmails = new LinkedHashSet<>();
        for (MemberRunRow row : rollup.rows()) {
            if (row.current() == null) continue;
            if (row.baseline() == null) {
                newMemberEmails.add(row.user().getEmail());
                continue;
            }
            Map<Integer, Double> current = row.current().criteriaScores();
            Map<Integer, Double> baseline = row.baseline().criteriaScores();
            Map<Integer, Double> deltas = new LinkedHashMap<>();
            Set<Integer> indices = new TreeSet<>();
            indices.addAll(current.keySet());
            indices.addAll(baseline.keySet());
            for (Integer i : indices) {
                double delta = current.getOrDefault(i, 0.0) - baseline.getOrDefault(i, 0.0);
                if (Math.abs(delta) >= DELTA_DISPLAY_EPSILON) {
                    deltas.put(i, delta);
                }
            }
            if (!deltas.isEmpty()) {
                deltasByEmail.put(row.user().getEmail(), deltas);
            }
        }
        return new OrgUnitReportViewModel.DeltaView(rollup.compareTo(), deltasByEmail, newMemberEmails);
    }

    // ------------------------------------------------------------- dashboard

    private record Dashboard(List<OrgUnitReportViewModel.PositionAggregateRow> positionAggregates,
                             Map<String, Map<Integer, String>> cellHeatClass,
                             OrgUnitReportViewModel.DashboardTotals totals,
                             String json) {}

    private Dashboard buildDashboard(IndividualReport report, OrgUnitRunRollup rollup) {
        int criteriaCount = report.getCriteria() == null ? 0 : report.getCriteria().size();
        Map<String, List<MemberRunRow>> byPosition = bucketByPosition(rollup.rows());

        List<OrgUnitReportViewModel.PositionAggregateRow> aggregates = new ArrayList<>();
        int applicableChecks = 0;
        int metChecks = 0;
        int nearMissTotal = 0;
        for (Map.Entry<String, List<MemberRunRow>> bucket : byPosition.entrySet()) {
            String positionKey = bucket.getKey();
            List<MemberRunRow> members = bucket.getValue();
            boolean thresholdsApply = !UNCLASSIFIED_KEY.equals(positionKey)
                    && !Position.OTHER.name().equals(positionKey);
            List<OrgUnitReportViewModel.PositionCriterionStats> byCriterion = new ArrayList<>();
            for (int i = 0; i < criteriaCount; i++) {
                List<Double> scores = new ArrayList<>();
                for (MemberRunRow row : members) {
                    if (row.current() != null) {
                        scores.add(row.current().criteriaScores().getOrDefault(i, 0.0));
                    }
                }
                Double threshold = thresholdsApply
                        ? rollup.criteriaThresholds().getOrDefault(i, Map.of()).get(positionKey)
                        : null;
                Integer metCount = null;
                Double metPercent = null;
                Integer nearMissCount = null;
                if (threshold != null) {
                    int met = 0;
                    int near = 0;
                    for (double score : scores) {
                        if (score >= threshold) met++;
                        else if (score >= threshold * (1 - NEAR_MISS_MARGIN)) near++;
                    }
                    metCount = met;
                    nearMissCount = near;
                    metPercent = scores.isEmpty() ? null : 100.0 * met / scores.size();
                    applicableChecks += scores.size();
                    metChecks += met;
                    nearMissTotal += near;
                }
                byCriterion.add(new OrgUnitReportViewModel.PositionCriterionStats(
                        i, scores.size(), metCount, metPercent, median(scores), nearMissCount, threshold));
            }
            aggregates.add(new OrgUnitReportViewModel.PositionAggregateRow(
                    positionKey,
                    UNCLASSIFIED_KEY.equals(positionKey) ? "Unclassified" : positionKey,
                    members.size(), byCriterion));
        }

        Map<String, Map<Integer, String>> cellHeatClass = heatClasses(rollup, criteriaCount);
        int scoredCount = (int) rollup.rows().stream().filter(r -> r.current() != null).count();
        int unclassifiedCount = byPosition.getOrDefault(UNCLASSIFIED_KEY, List.of()).size();
        OrgUnitReportViewModel.DashboardTotals totals = new OrgUnitReportViewModel.DashboardTotals(
                rollup.rows().size(), scoredCount,
                applicableChecks == 0 ? null : (int) Math.round(100.0 * metChecks / applicableChecks),
                nearMissTotal, unclassifiedCount);
        return new Dashboard(aggregates, cellHeatClass, totals,
                dashboardJson(report, rollup, aggregates, criteriaCount));
    }

    /** Members grouped by position: {@link Position} declaration order, UNCLASSIFIED last, empty buckets omitted. */
    private static Map<String, List<MemberRunRow>> bucketByPosition(List<MemberRunRow> rows) {
        Map<String, List<MemberRunRow>> byPosition = new LinkedHashMap<>();
        for (Position position : Position.values()) {
            byPosition.put(position.name(), new ArrayList<>());
        }
        byPosition.put(UNCLASSIFIED_KEY, new ArrayList<>());
        for (MemberRunRow row : rows) {
            Position position = row.user().getResearcherProfile().getPosition();
            byPosition.get(position == null ? UNCLASSIFIED_KEY : position.name()).add(row);
        }
        byPosition.values().removeIf(List::isEmpty);
        return byPosition;
    }

    private static Map<String, Map<Integer, String>> heatClasses(OrgUnitRunRollup rollup, int criteriaCount) {
        Map<String, Map<Integer, String>> byEmail = new LinkedHashMap<>();
        for (MemberRunRow row : rollup.rows()) {
            if (row.current() == null) continue;
            Position position = row.user().getResearcherProfile().getPosition();
            if (position == null || position == Position.OTHER) continue;
            Map<Integer, String> byCriterion = new LinkedHashMap<>();
            for (int i = 0; i < criteriaCount; i++) {
                Double threshold = rollup.criteriaThresholds().getOrDefault(i, Map.of()).get(position.name());
                if (threshold == null || threshold <= 0) continue;
                double ratio = row.current().criteriaScores().getOrDefault(i, 0.0) / threshold;
                byCriterion.put(i, heatClass(ratio));
            }
            if (!byCriterion.isEmpty()) {
                byEmail.put(row.user().getEmail(), byCriterion);
            }
        }
        return byEmail;
    }

    private static String heatClass(double ratio) {
        if (ratio >= 1.5) return "app-heat--high";
        if (ratio >= 1.0) return "app-heat--met";
        if (ratio >= 0.9) return "app-heat--near";
        if (ratio >= 0.5) return "app-heat--below";
        return "app-heat--far";
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /** The chart + CSV payload, serialized once server-side. */
    private String dashboardJson(IndividualReport report, OrgUnitRunRollup rollup,
                                 List<OrgUnitReportViewModel.PositionAggregateRow> aggregates,
                                 int criteriaCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> criteria = new ArrayList<>();
        for (int i = 0; i < criteriaCount; i++) {
            criteria.add(Map.of("index", i, "name", criterionName(report, i)));
        }
        payload.put("criteria", criteria);
        List<Map<String, Object>> positions = new ArrayList<>();
        Map<String, Map<Integer, Double>> metPercentByPosition = new LinkedHashMap<>();
        for (OrgUnitReportViewModel.PositionAggregateRow row : aggregates) {
            positions.add(Map.of("key", row.positionKey(), "label", row.positionLabel(),
                    "count", row.researcherCount()));
            Map<Integer, Double> metByCriterion = new LinkedHashMap<>();
            for (OrgUnitReportViewModel.PositionCriterionStats stats : row.byCriterion()) {
                if (stats.metPercent() != null) {
                    metByCriterion.put(stats.criterionIndex(), stats.metPercent());
                }
            }
            metPercentByPosition.put(row.positionKey(), metByCriterion);
        }
        payload.put("positions", positions);
        payload.put("metPercentByPosition", metPercentByPosition);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MemberRunRow row : rollup.rows()) {
            Map<String, Object> exportRow = new LinkedHashMap<>();
            exportRow.put("name", row.user().getResearcherProfile().getName().trim());
            Position position = row.user().getResearcherProfile().getPosition();
            exportRow.put("position", position == null ? "" : position.name());
            exportRow.put("department", row.departmentLabel() == null ? "" : row.departmentLabel());
            List<Double> scores = new ArrayList<>();
            for (int i = 0; i < criteriaCount; i++) {
                scores.add(row.current() == null ? null : row.current().criteriaScores().getOrDefault(i, 0.0));
            }
            exportRow.put("scores", scores);
            rows.add(exportRow);
        }
        payload.put("rows", rows);
        try {
            // '<' is escaped so a hostile criterion name cannot close the inline <script> tag.
            return objectMapper.writeValueAsString(payload).replace("<", "\\u003c");
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String criterionName(IndividualReport report, int index) {
        String name = report.getCriteria().get(index).getName();
        return name == null || name.isBlank() ? "Criterion " + (index + 1) : name.trim();
    }
}
