package ro.uvt.pokedex.core.service.reporting;

/**
 * The platform's single canonical grant-budget bracket scale (EUR), from the Informatică standard's
 * praguri (D_v: 50.000 / 100.000 / 200.000 / 500.000 Euro). Every budget-aware indicator formula
 * consumes the derived {@code Interval_buget} variable (1–5, 0 = unknown) that
 * {@link ActivityReportingService} binds from this scale, so the bounds live in exactly one place —
 * a new domain that brackets budgets reuses the same variable instead of re-encoding thresholds.
 *
 * <p>The {@link #label} strings double as the {@code Interval_buget} activity-field
 * {@code allowedValues} (the researcher-facing select options when no exact budget is known), so
 * the mapping from a stored instance value back to a bracket is an exact string match — change a
 * label here and the activity definition together, never one without the other.</p>
 */
public enum GrantBudgetBracket {

    UNDER_50K(1, 0, "sub 50.000 EUR"),
    FROM_50K(2, 50_000, "50.000 – 99.999 EUR"),
    FROM_100K(3, 100_000, "100.000 – 199.999 EUR"),
    FROM_200K(4, 200_000, "200.000 – 499.999 EUR"),
    FROM_500K(5, 500_000, "500.000 EUR sau peste");

    /** Bracket index the {@code Interval_buget} formula variable carries; 0 is reserved for "unknown". */
    public final int index;
    /** Inclusive lower bound in EUR. */
    public final long lowerBoundEur;
    /** Researcher-facing select label; must stay identical to the activity field's allowedValues. */
    public final String label;

    GrantBudgetBracket(int index, long lowerBoundEur, String label) {
        this.index = index;
        this.lowerBoundEur = lowerBoundEur;
        this.label = label;
    }

    /** Bracket for an exact EUR amount (from CORDIS {@code proj_budget} or the declared exact budget). */
    public static GrantBudgetBracket fromAmount(double amountEur) {
        GrantBudgetBracket best = UNDER_50K;
        for (GrantBudgetBracket bracket : values()) {
            if (amountEur >= bracket.lowerBoundEur) {
                best = bracket;
            }
        }
        return best;
    }

    /** Bracket for an {@code Interval_buget} index (1–5), or null for 0/out-of-range. */
    public static GrantBudgetBracket byIndex(int index) {
        for (GrantBudgetBracket bracket : values()) {
            if (bracket.index == index) {
                return bracket;
            }
        }
        return null;
    }

    /** Bracket index for a stored select label, or 0 when the value is absent/unrecognized. */
    public static int indexFromLabel(String label) {
        if (label == null || label.isBlank()) {
            return 0;
        }
        String trimmed = label.trim();
        for (GrantBudgetBracket bracket : values()) {
            if (bracket.label.equals(trimmed)) {
                return bracket.index;
            }
        }
        return 0;
    }
}
