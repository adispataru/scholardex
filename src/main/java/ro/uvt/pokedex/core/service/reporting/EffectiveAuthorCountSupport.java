package ro.uvt.pokedex.core.service.reporting;

/**
 * H65 (physics / Fizică): the effective author count {@code Nef} — a bracketed transform of the raw author count used
 * as the divisor in the physics indicators (I, A1–A8, C) instead of the raw {@code N}, to dampen very large author
 * lists. Ordin 6129/2016, Anexa 1 (Fizică):
 *
 * <pre>
 *   n ≤ 5            → n
 *   5  &lt; n ≤ 15   → (n + 5) / 2
 *   15 &lt; n ≤ 75   → (n + 15) / 3
 *   n &gt; 75        → (n + 45) / 4
 * </pre>
 *
 * The HEPP (High Energy Particle Physics) internal-note exception (Nef from the note's author count) is out of scope —
 * a manual fišă adjustment. Bound as the {@code Nef} formula variable by {@code ScientificProductionService} (and the
 * activity path for patents), so physics indicators are config formulas like {@code S/Nef}.
 */
public final class EffectiveAuthorCountSupport {

    private EffectiveAuthorCountSupport() {
    }

    /** The effective author count for a raw author count {@code n}. Real-valued division per the standard's brackets. */
    public static double computeNef(int n) {
        if (n <= 5) {
            return n;
        }
        if (n <= 15) {
            return (n + 5) / 2.0;
        }
        if (n <= 75) {
            return (n + 15) / 3.0;
        }
        return (n + 45) / 4.0;
    }
}
