package ro.uvt.pokedex.core.model.reporting.scoring;

import ro.uvt.pokedex.core.model.reporting.Indicator;

/**
 * Sealed-hierarchy replacement for the (Indicator.Type × Indicator.Strategy) cross-product.
 *
 * <p>Every existing production indicator maps into exactly one cell of this hierarchy. The
 * {@link #fromLegacy(Indicator.Type, Indicator.Strategy)} factory encodes the compatibility
 * table from the H52 design doc and is the canonical converter during the parallel-read
 * phase of the migration.
 *
 * <p>Each variant exposes its {@link #strategy()} so dispatch (formerly
 * {@code ScoringFactoryService}'s if/else ladder) collapses to a single map lookup.
 */
public sealed interface IndicatorKind
        permits IndicatorKind.Publications,
                IndicatorKind.Citations,
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
     * Bidirectional conversion from the (Indicator.Type, Indicator.Strategy) pair to a
     * v1 {@code IndicatorKind}. Encodes the per-kind permitted-strategy table from the
     * H52 design doc; every production indicator maps cleanly.
     *
     * @throws IllegalArgumentException for combinations not represented in any current
     *         indicator (the v1 doc treats those as impossible-by-construction)
     */
    static IndicatorKind fromLegacy(Indicator.Type type, Indicator.Strategy strategy) {
        if (type == null) throw new IllegalArgumentException("Indicator.Type cannot be null");
        if (strategy == null) throw new IllegalArgumentException("Indicator.Strategy cannot be null");
        ScoringStrategy s = ScoringStrategy.fromLegacy(strategy);

        return switch (type) {
            case PUBLICATIONS              -> new Publications(AuthorRole.ALL,  s);
            case PUBLICATIONS_MAIN_AUTHOR  -> new Publications(AuthorRole.MAIN, s);
            case PUBLICATIONS_COAUTHOR     -> new Publications(AuthorRole.CO,   s);

            case CITATIONS                 -> new Citations(false, s);
            case CITATIONS_EXCLUDE_SELF    -> new Citations(true,  s);

            case GENERIC_ACTIVITIES        -> {
                if (s != ScoringStrategy.GENERIC_ACTIVITY) {
                    throw new IllegalArgumentException(
                            "GENERIC_ACTIVITIES output type requires GENERIC_ACTIVITY strategy; got " + strategy);
                }
                yield new GenericActivity();
            }

            case ACTIVITY_FORUM            -> new Activity(ActivityType.FORUM,      s);
            case ACTIVITY_UNIVERSITY       -> new Activity(ActivityType.UNIVERSITY, s);
            case ACTIVITY_EVENT            -> new Activity(ActivityType.EVENT,      s);

            // PROJECT exists in the enum but has zero indicators in production.
            // Keep it modellable as an Activity with a yet-unused subtype so commit 3
            // can drop it cleanly if the situation hasn't changed.
            case ACTIVITY_PROJECT          -> throw new IllegalArgumentException(
                    "ACTIVITY_PROJECT is unused in production data and not modeled in v1 yet");
        };
    }

    /**
     * Inverse of {@link #fromLegacy}. Returns the (Type, Strategy) pair this kind would
     * serialize to on the legacy schema — used by parallel-write code that needs to
     * populate the deprecated fields for backwards compatibility during the migration.
     */
    default LegacyShape toLegacy() {
        return switch (this) {
            case Publications p -> new LegacyShape(switch (p.role()) {
                case ALL  -> Indicator.Type.PUBLICATIONS;
                case MAIN -> Indicator.Type.PUBLICATIONS_MAIN_AUTHOR;
                case CO   -> Indicator.Type.PUBLICATIONS_COAUTHOR;
            }, p.strategy().toLegacy());
            case Citations c -> new LegacyShape(
                    c.excludeSelf() ? Indicator.Type.CITATIONS_EXCLUDE_SELF : Indicator.Type.CITATIONS,
                    c.strategy().toLegacy());
            case Activity a -> new LegacyShape(switch (a.type()) {
                case FORUM      -> Indicator.Type.ACTIVITY_FORUM;
                case UNIVERSITY -> Indicator.Type.ACTIVITY_UNIVERSITY;
                case EVENT      -> Indicator.Type.ACTIVITY_EVENT;
            }, a.strategy().toLegacy());
            case GenericCount gc    -> new LegacyShape(Indicator.Type.PUBLICATIONS, Indicator.Strategy.GENERIC_COUNT);
            case GenericActivity ga -> new LegacyShape(Indicator.Type.GENERIC_ACTIVITIES, Indicator.Strategy.GENERIC_ACTIVITY);
        };
    }

    /** Round-trip helper for parallel-write code. */
    record LegacyShape(Indicator.Type type, Indicator.Strategy strategy) {}
}
