package ro.uvt.pokedex.core.model.reporting.scoring;

import ro.uvt.pokedex.core.model.reporting.Indicator;

/**
 * Top-level scoring strategy enum. Replaces {@link Indicator.Strategy} as the canonical
 * dispatch key for {@code ScoringService} implementations.
 *
 * <p>Bidirectional mapping with the legacy enum is provided via {@link #fromLegacy} and
 * {@link #toLegacy} so the parallel-read phase of the H52 migration can convert in either
 * direction without losing information.
 */
public enum ScoringStrategy {
    GENERIC_ACTIVITY,
    GENERIC_COUNT,
    CS_CONFERENCE,
    CS_JOURNAL,
    CS_SENSE,
    CS,
    IMPACT_FACTOR,
    RIS,
    AIS,
    ECONOMICS_JOURNAL_AIS,
    UNI_RANKING,
    CNCSIS,
    ART_EVENT;

    /** Convert from the legacy nested enum. Throws if {@code legacy} is null. */
    public static ScoringStrategy fromLegacy(Indicator.Strategy legacy) {
        if (legacy == null) throw new IllegalArgumentException("Strategy cannot be null");
        return ScoringStrategy.valueOf(legacy.name());
    }

    /** Convert to the legacy nested enum. Bijective with {@link #fromLegacy}. */
    public Indicator.Strategy toLegacy() {
        return Indicator.Strategy.valueOf(name());
    }
}
