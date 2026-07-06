package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.model.reporting.ReportingDataEpoch;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.OrgUnitRosterService;
import ro.uvt.pokedex.core.service.application.ReportingDataEpochService;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rolls an org-unit roster up over each member's latest persisted {@link UserIndividualReportRun}
 * — the exact numbers the researcher sees in their own workspace. Pure reads: no scoring happens
 * on the view path; runs are created by the researcher's own refresh, a delegated refresh, or an
 * admin batch refresh. A run older than the last data-rebuild epoch bump is flagged stale.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitRunRollupService {

    private final UserIndividualReportRunRepository userIndividualReportRunRepository;
    private final ReportingDataEpochService reportingDataEpochService;

    public record RunSummary(
            String runId,
            Instant createdAt,
            UserIndividualReportRun.Status status,
            boolean provisional,
            Map<Integer, Double> criteriaScores,
            /** Sum of criterion scores flagged contributesToTotal; null when none contributes. */
            Double total,
            String triggeredByEmail,
            Integer referenceYear,
            List<String> buildErrors
    ) {}

    /**
     * One roster member; {@code current} is null when the member has no run yet. {@code baseline}
     * is the member's latest run before the compare instant — only resolved in compare mode.
     */
    public record MemberRunRow(User user, String departmentLabel, RunSummary current, boolean stale,
                               RunSummary baseline) {}

    public record OrgUnitRunRollup(
            List<MemberRunRow> rows,
            /** criterion index → position name → threshold value, from the report definition. */
            Map<Integer, Map<String, Double>> criteriaThresholds,
            int membersWithoutRun,
            int provisionalCount,
            int staleCount,
            Instant oldestRunAt,
            Instant epochUpdatedAt,
            String epochLastReason,
            /** The compare instant; null when the view is not in compare mode. */
            Instant compareTo
    ) {}

    public OrgUnitRunRollup rollup(List<OrgUnitRosterService.RosterMember> members, IndividualReport report) {
        return rollup(members, report, null);
    }

    public OrgUnitRunRollup rollup(List<OrgUnitRosterService.RosterMember> members, IndividualReport report,
                                   Instant compareTo) {
        Optional<ReportingDataEpoch> epoch = reportingDataEpochService.currentEpochInfo();
        Instant epochUpdatedAt = epoch.map(ReportingDataEpoch::getUpdatedAt).orElse(null);
        String epochLastReason = epoch.map(ReportingDataEpoch::getLastReason).orElse(null);

        List<MemberRunRow> rows = new ArrayList<>();
        int membersWithoutRun = 0;
        int provisionalCount = 0;
        int staleCount = 0;
        Instant oldestRunAt = null;
        for (OrgUnitRosterService.RosterMember member : members) {
            UserIndividualReportRun run = userIndividualReportRunRepository
                    .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(
                            member.user().getEmail(), report.getId())
                    .orElse(null);
            RunSummary baseline = compareTo == null ? null : userIndividualReportRunRepository
                    .findTopByUserEmailAndReportDefinitionIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                            member.user().getEmail(), report.getId(), compareTo)
                    .map(b -> toSummary(b, report))
                    .orElse(null);
            if (run == null) {
                membersWithoutRun++;
                rows.add(new MemberRunRow(member.user(), member.departmentLabel(), null, false, baseline));
                continue;
            }
            boolean stale = epochUpdatedAt != null && run.getCreatedAt() != null
                    && run.getCreatedAt().isBefore(epochUpdatedAt);
            if (run.isProvisional()) provisionalCount++;
            if (stale) staleCount++;
            if (run.getCreatedAt() != null && (oldestRunAt == null || run.getCreatedAt().isBefore(oldestRunAt))) {
                oldestRunAt = run.getCreatedAt();
            }
            rows.add(new MemberRunRow(member.user(), member.departmentLabel(), toSummary(run, report), stale, baseline));
        }
        return new OrgUnitRunRollup(rows, thresholdsByCriterion(report),
                membersWithoutRun, provisionalCount, staleCount, oldestRunAt, epochUpdatedAt, epochLastReason,
                compareTo);
    }

    /** Deltas smaller than the 2-decimal display precision are treated as unchanged. */
    private static final double DELTA_DISPLAY_EPSILON = 0.005;

    /** Packs a rollup into the shared org-unit view model (per-member build errors get name-prefixed). */
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
        return new OrgUnitReportViewModel(unitId, unitName, report, researchers, scoresByEmail,
                rollup.criteriaThresholds(), buildErrors, departmentLabelByResearcher, runMetaByEmail,
                rollup.membersWithoutRun(), rollup.provisionalCount(), rollup.staleCount(),
                rollup.oldestRunAt(), rollup.epochUpdatedAt(), rollup.epochLastReason(),
                toDeltaView(rollup), compareOptions == null ? List.of() : compareOptions);
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
            Set<Integer> indices = new java.util.TreeSet<>();
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

    private static RunSummary toSummary(UserIndividualReportRun run, IndividualReport report) {
        Map<Integer, Double> criteriaScores = run.getCriteriaScores() == null ? Map.of() : run.getCriteriaScores();
        return new RunSummary(run.getId(), run.getCreatedAt(), run.getStatus(), run.isProvisional(),
                criteriaScores, totalScore(report, criteriaScores), run.getTriggeredByEmail(),
                run.getReferenceYear(), run.getBuildErrors() == null ? List.of() : run.getBuildErrors());
    }

    private static Double totalScore(IndividualReport report, Map<Integer, Double> criteriaScores) {
        if (report.getCriteria() == null) return null;
        double total = 0.0;
        boolean anyContributesToTotal = false;
        for (int i = 0; i < report.getCriteria().size(); i++) {
            if (report.getCriteria().get(i).isContributesToTotal()) {
                anyContributesToTotal = true;
                total += criteriaScores.getOrDefault(i, 0.0);
            }
        }
        return anyContributesToTotal ? total : null;
    }

    private static Map<Integer, Map<String, Double>> thresholdsByCriterion(IndividualReport report) {
        Map<Integer, Map<String, Double>> criteriaThresholds = new HashMap<>();
        List<AbstractReport.Criterion> criteria = report.getCriteria() == null ? List.of() : report.getCriteria();
        for (int i = 0; i < criteria.size(); i++) {
            Map<String, Double> thresholds = new HashMap<>();
            List<AbstractReport.Threshold> declared = criteria.get(i).getThresholds() == null
                    ? List.of() : criteria.get(i).getThresholds();
            for (AbstractReport.Threshold t : declared) {
                if (t.getPosition() != null && t.getValue() != null) {
                    thresholds.put(t.getPosition().name(), t.getValue());
                }
            }
            criteriaThresholds.put(i, thresholds);
        }
        return criteriaThresholds;
    }
}
