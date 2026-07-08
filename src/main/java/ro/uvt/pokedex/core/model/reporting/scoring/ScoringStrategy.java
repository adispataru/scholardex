package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * Top-level scoring strategy enum. Canonical dispatch key for {@code ScoringService}
 * implementations. As of H52 slice 11d.5 this enum replaces the pre-v1
 * {@code Indicator.Strategy} nested enum outright; the {@link #name()} string is
 * what the admin form, cached-blob fingerprint, and JSON shape compat all carry.
 */
public enum ScoringStrategy {
    GENERIC_ACTIVITY,
    GENERIC_COUNT,
    CS_CONFERENCE,
    CS_JOURNAL,
    CS_SENSE,
    CS,
    /**
     * PN-IV PD/TE eligibility standard (Pachet de informații PD 2026, Anexa 2): WoS Core Collection
     * membership restricted to SCIE/SSCI/AHCI (ESCI does NOT qualify), quartile strictly by AIS within
     * a WoS category of the indicator's domain, ranking year = publication year capped at JCR-2024.
     * No Scopus/index C-floors and no CNATDCU point translation — a member scores 1 venue point and
     * exposes the AIS quartile as {@code Q} for the eligibility formulas.
     */
    PD_WOS,
    IMPACT_FACTOR,
    RIS,
    AIS,
    ECONOMICS_JOURNAL_AIS,
    UNI_RANKING,
    CNCSIS,
    ART_EVENT,
    FEAA_BOOK,
    /**
     * H67 S4a: the Hirsch (h-index) aggregate. Unlike the others this is NOT a per-item {@code ScoringService} —
     * h-index is non-additive, so it is handled inline at the combine step (like {@link #GENERIC_COUNT}); no
     * {@code ScoringService} bean claims it. Carried as a strategy only so {@code IndicatorKind.HIndex} round-trips.
     */
    HIRSCH;
}
