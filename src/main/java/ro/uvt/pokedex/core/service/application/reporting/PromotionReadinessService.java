package ro.uvt.pokedex.core.service.application.reporting;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService.MemberRunRow;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService.OrgUnitRunRollup;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Promotion-readiness board: re-evaluates each roster member's latest run against the thresholds of
 * the NEXT position on their career ladder (didactic ASIST→LECT→CONF→PROF, research ASIST_C→CS_III→
 * CS_II→CS_I), using whatever report the head is viewing. Only criteria for which the target position
 * defines a threshold participate; a member with no applicable criteria lands in NO_STANDARD.
 *
 * <p>The verdict is deliberately phrased as "meets the scientific minimum standards" — the report
 * thresholds cover the metric part of a promotion dossier only (no seniority, habilitation, teaching
 * requirements). Habilitation (abilitare) is a qualification rather than a ladder rung, so whenever the
 * report defines HABIL thresholds every member with a run gets the secondary habilitation check,
 * regardless of their current position.
 */
@Service
public class PromotionReadinessService {

    /** Same near-miss margin the org-unit dashboard uses (within 10% below the threshold). */
    static final double NEAR_MISS_MARGIN = OrgUnitReportViewAssembler.NEAR_MISS_MARGIN;

    private static final Map<Position, Position> NEXT_POSITION = new EnumMap<>(Map.of(
            Position.ASIST_UNIV, Position.LECT_UNIV,
            Position.LECT_UNIV, Position.CONF_UNIV,
            Position.CONF_UNIV, Position.PROF_UNIV,
            Position.ASIST_C, Position.CS_III,
            Position.CS_III, Position.CS_II,
            Position.CS_II, Position.CS_I));

    public enum Band { MEETS, BORDERLINE, BUILDING, NO_STANDARD, TOP_OF_LADDER, NO_RUN, UNCLASSIFIED }

    public record CriterionCheck(int index, String name, double score, double threshold, boolean met,
                                 boolean near, double gap) {}

    public record MemberReadiness(MemberRunRow row, Position currentPosition, Position targetPosition,
                                  Band band, List<CriterionCheck> checks, int metCount, int applicableCount,
                                  /** HABIL-threshold checks; empty when the report defines none or the member has no run. */
                                  List<CriterionCheck> habilChecks, boolean habilMet) {}

    public record PromotionBoard(List<MemberReadiness> meets, List<MemberReadiness> borderline,
                                 List<MemberReadiness> building, List<MemberReadiness> notEvaluable,
                                 int provisionalCount) {}

    public PromotionBoard build(IndividualReport report, OrgUnitRunRollup rollup) {
        List<MemberReadiness> meets = new ArrayList<>();
        List<MemberReadiness> borderline = new ArrayList<>();
        List<MemberReadiness> building = new ArrayList<>();
        List<MemberReadiness> notEvaluable = new ArrayList<>();
        int provisional = 0;
        for (MemberRunRow row : rollup.rows()) {
            MemberReadiness readiness = evaluate(report, rollup, row);
            if (readiness.row().current() != null && readiness.row().current().provisional()) provisional++;
            switch (readiness.band()) {
                case MEETS -> meets.add(readiness);
                case BORDERLINE -> borderline.add(readiness);
                case BUILDING -> building.add(readiness);
                default -> notEvaluable.add(readiness);
            }
        }
        // Within a band the closest candidates come first (most criteria met, then smallest worst gap).
        java.util.Comparator<MemberReadiness> closest = java.util.Comparator
                .comparingInt((MemberReadiness m) -> m.applicableCount() - m.metCount())
                .thenComparingDouble(PromotionReadinessService::worstRelativeGap);
        borderline.sort(closest);
        building.sort(closest);
        return new PromotionBoard(meets, borderline, building, notEvaluable, provisional);
    }

    private MemberReadiness evaluate(IndividualReport report, OrgUnitRunRollup rollup, MemberRunRow row) {
        Position current = row.user().getResearcherProfile() == null
                ? null : row.user().getResearcherProfile().getPosition();
        // Habilitation is position-independent: computed for anyone with a run, whenever the report
        // defines HABIL thresholds (checksAgainst returns empty otherwise).
        List<CriterionCheck> habilChecks = row.current() == null
                ? List.of() : checksAgainst(report, rollup, row, Position.HABIL.name());
        boolean habilMet = !habilChecks.isEmpty() && habilChecks.stream().allMatch(CriterionCheck::met);
        if (current == null || current == Position.OTHER || current == Position.HABIL) {
            return new MemberReadiness(row, current, null, Band.UNCLASSIFIED, List.of(), 0, 0, habilChecks, habilMet);
        }
        Position target = NEXT_POSITION.get(current);
        if (target == null) {
            return new MemberReadiness(row, current, null, Band.TOP_OF_LADDER, List.of(), 0, 0, habilChecks, habilMet);
        }
        if (row.current() == null) {
            return new MemberReadiness(row, current, target, Band.NO_RUN, List.of(), 0, 0, List.of(), false);
        }
        List<CriterionCheck> checks = checksAgainst(report, rollup, row, target.name());
        if (checks.isEmpty()) {
            return new MemberReadiness(row, current, target, Band.NO_STANDARD, checks, 0, 0, habilChecks, habilMet);
        }
        int met = (int) checks.stream().filter(CriterionCheck::met).count();
        Band band;
        if (met == checks.size()) {
            band = Band.MEETS;
        } else if (checks.stream().allMatch(c -> c.met() || c.near())) {
            band = Band.BORDERLINE;
        } else {
            band = Band.BUILDING;
        }
        return new MemberReadiness(row, current, target, band, checks, met, checks.size(), habilChecks, habilMet);
    }

    private List<CriterionCheck> checksAgainst(IndividualReport report, OrgUnitRunRollup rollup,
                                               MemberRunRow row, String positionKey) {
        List<CriterionCheck> checks = new ArrayList<>();
        int criteriaCount = report.getCriteria() == null ? 0 : report.getCriteria().size();
        for (int i = 0; i < criteriaCount; i++) {
            Double threshold = rollup.criteriaThresholds().getOrDefault(i, Map.of()).get(positionKey);
            if (threshold == null) {
                continue;
            }
            double score = row.current().criteriaScores().getOrDefault(i, 0.0);
            boolean met = score >= threshold;
            boolean near = !met && score >= threshold * (1 - NEAR_MISS_MARGIN);
            String name = report.getCriteria().get(i).getName();
            checks.add(new CriterionCheck(i, name == null || name.isBlank() ? "C" + (i + 1) : name,
                    score, threshold, met, near, Math.max(0, threshold - score)));
        }
        return checks;
    }

    private static double worstRelativeGap(MemberReadiness m) {
        return m.checks().stream()
                .filter(c -> !c.met())
                .mapToDouble(c -> c.threshold() <= 0 ? 0 : c.gap() / c.threshold())
                .max().orElse(0);
    }
}
