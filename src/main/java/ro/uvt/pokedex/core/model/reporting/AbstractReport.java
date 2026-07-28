package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;

import java.util.*;

@Data
public abstract class AbstractReport {
    @Id
    private String id;
    private String title;
    private String description;
    @DBRef
    private List<Indicator> indicators = new ArrayList<>();
    private List<Criterion> criteria;
    /** H95: section-level groupings with logical verdicts over {@link #criteria}; null on legacy reports. */
    private List<Perspective> perspectives;

    @Data
    public static class Criterion {
        private String name;
        private List<Integer> indicatorIndices = new ArrayList<>();
        private List<Threshold> thresholds = new ArrayList<>();
        /** When true this criterion's score is included in the report-level total. */
        private boolean contributesToTotal = false;
        /**
         * H65: optional per-indicator coefficients (indicatorIndex → weight) for weighted-sum criteria such as the
         * physics composite {@code T = A + P/2 + I/2 + C/20 + h/5}. Null or a missing entry means weight 1.0, so plain
         * sum-of-indicators criteria (every existing report) are unaffected.
         */
        private java.util.Map<Integer, Double> weights;
        /**
         * H68 slice 2: optional criterion-level cap (plafon). When set, the aggregated criterion score is clamped to
         * this maximum (e.g. "maximum 50 puncte for this criterion"). Null = no cap. Applied after the weighted sum,
         * so it composes with {@link #weights}. Per-indicator caps are separate ({@code Indicator.maxPoints}).
         */
        private Double maxTotal;
        /**
         * H68 slice 3: optional per-indicator percent-of-criterion caps (indicatorIndex → percent, 0–100), for
         * standards rules like OM 3019/2025 Informatică D(x)/D(xiv) "maximum 10% din punctajul total al perspectivei d".
         * Fixed-point semantics (pinned 2026-07-24): the capped contribution is {@code min(c_i, p_i·T)} where T is the
         * FINAL criterion total — the only reading whose result satisfies the rule against the shipped total, and the
         * candidate-favorable one. Applied after {@link #weights}, before {@link #maxTotal}. Null/missing = no cap.
         */
        private java.util.Map<Integer, Double> maxPercentOfTotal;
        /**
         * Stage 1 of position-aware eligibility (FEAA 2026 book cap): indicators OUTSIDE this criterion whose raw
         * total is added to the criterion score capped at {@code percent}% of a per-position threshold —
         * {@code effective(pos) = score + Σ min(rawIndicator, percent/100 · threshold(refCriterion, pos))}. The
         * referenced threshold is {@code thresholdCriterionIndex}'s (null → this criterion's own); FEAA's S=P+C
         * criterion needs the books addition capped by P's minimum, not its own. Consumed only where
         * obtained-vs-threshold is evaluated (render-time effective scores); the persisted canonical
         * {@code criteriaScores} never include these additions.
         */
        private List<ThresholdCapAddition> thresholdCapAdditions;
    }

    /** See {@link Criterion#thresholdCapAdditions}. */
    @Data
    public static class ThresholdCapAddition {
        private Integer indicatorIndex;
        /** Cap as percent (0–100) of the referenced criterion's per-position threshold value. */
        private Double percent;
        /** Criterion whose per-position threshold is the cap base; null → the declaring criterion. */
        private Integer thresholdCriterionIndex;
    }

    @Data
    public static class Threshold {
        private Position position;
        private Double value;
    }

    /**
     * H95: a report-level grouping ABOVE criteria — the standards' own section layer (Perspectiva B,
     * Punctul 4, …). Criteria stay exactly as they are; a perspective BUNDLES them and judges them with a
     * declarative AND/OR {@link CompositionNode} tree. Verdicts are derived at render time from the
     * criteria's per-position met-ness (which already includes the position-effective machinery) — a
     * perspective carries no score and nothing about it is persisted on runs.
     */
    @Data
    public static class Perspective {
        private String name;
        private CompositionNode composition;
    }

    /**
     * One node of a perspective's composition tree — exactly one of the four fields is set:
     * {@code all} (conjunction), {@code any} (disjunction), {@code criterion} (leaf: criterion index in
     * {@link #criteria}), or {@code perspective} (leaf: an EARLIER perspective's index — the ordering
     * constraint makes cycles impossible by construction; the Total verdict references B/C/D this way).
     * Deliberately not a formula engine: leaf truth is the criterion's existing threshold met-ness.
     * Per-position skip rules (pinned 2026-07-28): a leaf with no threshold for the position is
     * inapplicable — vacuously TRUE inside {@code all}, FALSE inside {@code any}; a node with no
     * applicable child is itself inapplicable.
     */
    @Data
    public static class CompositionNode {
        private List<CompositionNode> all;
        private List<CompositionNode> any;
        private Integer criterion;
        private Integer perspective;
        /**
         * Optional display name for this node when it is surfaced as an alternative route (a direct
         * child of an {@code any} root — FEAA's "Ruta a".."Ruta d"). Purely presentational: never
         * consulted by verdict evaluation, and absent labels fall back to a numbered i18n label.
         */
        private String label;
    }

}
