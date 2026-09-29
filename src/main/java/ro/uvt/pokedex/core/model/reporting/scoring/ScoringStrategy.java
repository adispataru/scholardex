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
     * FSP (Psihologie, Anexa 28) book/chapter scoring by a 3-tier prestige-publisher list (A1/A2/B).
     * Returns the tier multiplier {@code m} as the base score {@code S} (A1→3, A2→1, B→0.5); the
     * indicator formula turns it into Pi (e.g. {@code 12*S/N} for books, {@code 3*S/N} for chapters).
     * Books/chapters not on any tier score 0 (per fișă: "publicaţiile care nu îndeplinesc criteriile
     * minime … nu se punctează"). Backed by {@code report-data/psihologie-publishers.csv}.
     */
    PSYCH_BOOK,
    /**
     * FSP (Psihologie, Anexa 28) I2/I6 fallback ladder for journal articles that do NOT qualify under
     * the strict I1/I5 path (psychology-category WoS journal with IF≥p or above-median Q1/Q2):
     * <ul>
     *   <li>WoS-ranked in ANY category (borderline-domain journals — "domenii de graniţă"): S = that IF,
     *       {@code category="WOS"};</li>
     *   <li>else indexed in ≥2 recognized non-WoS BDIs (SCOPUS/DOAJ/ERIH memberships): S = 0,
     *       {@code category="BDI2"};</li>
     *   <li>exactly 1 BDI → {@code category="BDI1"} (S=0; reserved for the I11-style indicators).</li>
     * </ul>
     * A paper that qualifies for the strict path returns 0 here with zeroReason SCORED_BY_STRICTER —
     * the fișă counts each publication under a single (most favorable) indicator.
     */
    PSYCH_BDI_JOURNAL,
    /**
     * FSP (Psihologie, Anexa 28) indicators I9/I10: an in-extenso proceedings paper indexed in WoS or
     * another recognised BDI scores a flat 1.0 (co-author variant does {@code 1/N} in the formula).
     * Reuses the CS conference detection/DBLP plumbing but ignores the CORE rank ladder. Pair with a
     * {@code PerForumCap(2)} selector for the "cel mult două contribuţii/ediţie conferinţă" cap.
     */
    INDEXED_PROCEEDINGS,
    /**
     * Psihologie 2026 (Comisia 28) I11 — "citări primite în Web of Science Core Collection": an item scores a
     * flat 1.0 when its forum was indexed in any WoS edition we track (SCIE/SSCI/AHCI/ESCI) in the item's own
     * year (year-true with carry-forward), whatever the WoS category or domain. The winning edition reaches the
     * formula as {@code category}. Indicative: it counts our citation graph restricted to WoS-indexed journals,
     * not the official WoS citation report (proceedings and book indexes are not covered by our data).
     */
    WOS_INDEXED,
    /**
     * Sociologie 2026 (Comisia 25) I.2 — journal articles outside I.1: a Scopus journal returns the full
     * 4 points as S ({@code category="SCOPUS"}), a journal in at least three recognised databases 2
     * ({@code category="BDI3"}). An article whose journal has an impact factor is left to I.1
     * (zeroReason SCORED_BY_STRICTER). Formula: {@code S * Coef_m / N}.
     */
    SOC_INDEXED_JOURNAL,
    /**
     * Sociologie 2026 (Comisia 25) I.9 — a citation is priced by the impact factor f of the CITING journal:
     * S = 0,2 + 4·f, with f = 0 when the citing source has none (a book, a volume, a journal without impact
     * factor), so every citation scores. Formula: {@code S * 2 / N}, N being the authors of the cited work.
     */
    CITING_IMPACT_FACTOR,
    /**
     * H67 S4a: the Hirsch (h-index) aggregate. Unlike the others this is NOT a per-item {@code ScoringService} —
     * h-index is non-additive, so it is handled inline at the combine step (like {@link #GENERIC_COUNT}); no
     * {@code ScoringService} bean claims it. Carried as a strategy only so {@code IndicatorKind.HIndex} round-trips.
     */
    HIRSCH;
}
