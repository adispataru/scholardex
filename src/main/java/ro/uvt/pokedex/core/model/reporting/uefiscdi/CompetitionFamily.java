package ro.uvt.pokedex.core.model.reporting.uefiscdi;

/** H138 — PN-IV PD/TE 2026, Anexa 2: the standard a competition domain is judged by. */
public enum CompetitionFamily {
    /** Domains 1–10: counts of Q1/Q2 works (plus the CORE route under Informatică). */
    EXACT,
    /** Domains 11–12: the points formula P = ΣA + ΣC + ΣK. */
    SOCIAL_ECONOMIC,
    /** Domain 13: the CNCS-style table. */
    HUMANITIES
}
