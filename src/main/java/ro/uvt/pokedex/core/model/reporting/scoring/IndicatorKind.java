package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * Sealed-hierarchy replacement for the (Indicator.Type × Indicator.Strategy) cross-product.
 *
 * <p>Every existing production indicator maps into exactly one cell of this hierarchy. The
 * {@link #of(String, String)} factory encodes the conversion from the legacy
 * (outputType, scoringStrategy) name pair and is what the admin form / cache-fingerprint
 * code construct kinds with after H52 slice 11d.5.
 *
 * <p>Each variant exposes its {@link #strategy()} so dispatch (formerly
 * {@code ScoringFactoryService}'s if/else ladder) collapses to a single map lookup.
 */
public sealed interface IndicatorKind
        permits IndicatorKind.Publications,
                IndicatorKind.Citations,
                IndicatorKind.HIndex,
                IndicatorKind.Activity,
                IndicatorKind.GenericCount,
                IndicatorKind.GenericActivity {

    /** The scoring strategy used to compute the {@code BaseScore} for each item. */
    ScoringStrategy strategy();

    /** Publication-based indicator, optionally filtered by the researcher's author role. */
    record Publications(AuthorRole role, ScoringStrategy strategy) implements IndicatorKind {
        public Publications {
            if (role == null) throw new IllegalArgumentException("role cannot be null");
            if (strategy == null) throw new IllegalArgumentException("strategy cannot be null");
        }
    }

    /** Citation-based indicator. {@code excludeSelf=true} filters out self-citations. */
    record Citations(boolean excludeSelf, ScoringStrategy strategy) implements IndicatorKind {
        public Citations {
            if (strategy == null) throw new IllegalArgumentException("strategy cannot be null");
        }
    }

    /**
     * H67 S4a: aggregate Hirsch (h-index) indicator — the model's first NON-additive metric. Its value is
     * {@code hIndex} over the per-publication citation counts (restricted to {@code source}, with self-citations
     * dropped when {@code excludeSelf}), computed at the combine step rather than by a per-item scorer. Strategy is
     * always {@link ScoringStrategy#HIRSCH}.
     */
    record HIndex(HIndexSource source, boolean excludeSelf) implements IndicatorKind {
        public HIndex {
            if (source == null) throw new IllegalArgumentException("source cannot be null");
        }
        @Override
        public ScoringStrategy strategy() { return ScoringStrategy.HIRSCH; }
    }

    /** Activity-based indicator scored against a forum / university / event ranking. */
    record Activity(ActivityType type, ScoringStrategy strategy) implements IndicatorKind {
        public Activity {
            if (type == null) throw new IllegalArgumentException("type cannot be null");
            if (strategy == null) throw new IllegalArgumentException("strategy cannot be null");
        }
    }

    /** Generic count indicator — every matching item contributes a constant 1.0. */
    record GenericCount() implements IndicatorKind {
        @Override
        public ScoringStrategy strategy() { return ScoringStrategy.GENERIC_COUNT; }
    }

    /** Generic activity indicator — activity instances with no ranking lookup. */
    record GenericActivity() implements IndicatorKind {
        @Override
        public ScoringStrategy strategy() { return ScoringStrategy.GENERIC_ACTIVITY; }
    }

    /**
     * H52 slice 11d.5: constructs the right kind from the legacy
     * {@code (outputType, scoringStrategy)} name pair. Names match what the admin
     * form posts and what the {@code userIndicatorResults} fingerprint stores.
     *
     * @throws IllegalArgumentException for combinations not represented in production
     *         (e.g. {@code ACTIVITY_PROJECT} which has zero indicators)
     */
    static IndicatorKind of(String outputTypeName, String strategyName) {
        if (outputTypeName == null) throw new IllegalArgumentException("outputTypeName cannot be null");
        if (strategyName == null) throw new IllegalArgumentException("strategyName cannot be null");
        ScoringStrategy s;
        try {
            s = ScoringStrategy.valueOf(strategyName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown scoring strategy: " + strategyName, ex);
        }

        return switch (outputTypeName) {
            case "PUBLICATIONS"              -> new Publications(AuthorRole.ALL,  s);
            case "PUBLICATIONS_MAIN_AUTHOR"  -> new Publications(AuthorRole.MAIN, s);
            case "PUBLICATIONS_COAUTHOR"     -> new Publications(AuthorRole.CO,   s);

            case "CITATIONS"                 -> new Citations(false, s);
            case "CITATIONS_EXCLUDE_SELF"    -> new Citations(true,  s);

            // H67 S4a: HINDEX_<source>[ _EXCLUDE_SELF ], always paired with the HIRSCH strategy.
            case "HINDEX_SCHOLARDEX", "HINDEX_GRAPH", "HINDEX_SCOPUS", "HINDEX_WOS",
                 "HINDEX_SCHOLARDEX_EXCLUDE_SELF", "HINDEX_GRAPH_EXCLUDE_SELF",
                 "HINDEX_SCOPUS_EXCLUDE_SELF", "HINDEX_WOS_EXCLUDE_SELF" -> {
                if (s != ScoringStrategy.HIRSCH) {
                    throw new IllegalArgumentException(
                            outputTypeName + " requires the HIRSCH strategy; got " + strategyName);
                }
                boolean excludeSelf = outputTypeName.endsWith("_EXCLUDE_SELF");
                String token = outputTypeName.substring("HINDEX_".length(),
                        outputTypeName.length() - (excludeSelf ? "_EXCLUDE_SELF".length() : 0));
                yield new HIndex(HIndexSource.fromToken(token), excludeSelf);
            }

            case "GENERIC_ACTIVITIES" -> {
                if (s != ScoringStrategy.GENERIC_ACTIVITY) {
                    throw new IllegalArgumentException(
                            "GENERIC_ACTIVITIES output type requires GENERIC_ACTIVITY strategy; got " + strategyName);
                }
                yield new GenericActivity();
            }

            case "ACTIVITY_FORUM"      -> new Activity(ActivityType.FORUM,      s);
            case "ACTIVITY_UNIVERSITY" -> new Activity(ActivityType.UNIVERSITY, s);
            case "ACTIVITY_EVENT"      -> new Activity(ActivityType.EVENT,      s);

            // PROJECT exists in the legacy enum but has zero indicators in production.
            case "ACTIVITY_PROJECT" -> throw new IllegalArgumentException(
                    "ACTIVITY_PROJECT is unused in production data and not modeled in v1");
            default -> throw new IllegalArgumentException("Unknown outputType: " + outputTypeName);
        };
    }

    /**
     * Inverse of {@link #of}. Returns the (outputTypeName, strategyName) pair this kind
     * serializes to on the legacy schema. Used by the JSON-shape derived getters on
     * {@code Indicator} and by the {@code userIndicatorResults} fingerprint.
     */
    default LegacyShape toLegacy() {
        return switch (this) {
            case Publications p -> new LegacyShape(switch (p.role()) {
                case ALL  -> "PUBLICATIONS";
                case MAIN -> "PUBLICATIONS_MAIN_AUTHOR";
                case CO   -> "PUBLICATIONS_COAUTHOR";
            }, p.strategy().name());
            case Citations c -> new LegacyShape(
                    c.excludeSelf() ? "CITATIONS_EXCLUDE_SELF" : "CITATIONS",
                    c.strategy().name());
            case HIndex h -> new LegacyShape(
                    "HINDEX_" + h.source().token() + (h.excludeSelf() ? "_EXCLUDE_SELF" : ""),
                    ScoringStrategy.HIRSCH.name());
            case Activity a -> new LegacyShape(switch (a.type()) {
                case FORUM      -> "ACTIVITY_FORUM";
                case UNIVERSITY -> "ACTIVITY_UNIVERSITY";
                case EVENT      -> "ACTIVITY_EVENT";
            }, a.strategy().name());
            case GenericCount gc    -> new LegacyShape("PUBLICATIONS", "GENERIC_COUNT");
            case GenericActivity ga -> new LegacyShape("GENERIC_ACTIVITIES", "GENERIC_ACTIVITY");
        };
    }

    /**
     * H52 slice 11d.5: name-based (outputType, strategy) pair carrying the same
     * strings the admin form and the cached-blob fingerprint format use. Replaces
     * the prior enum-based variant.
     */
    record LegacyShape(String outputTypeName, String strategyName) {}
}
