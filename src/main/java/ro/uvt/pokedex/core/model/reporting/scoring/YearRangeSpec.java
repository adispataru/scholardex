package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * Typed replacement for the {@code Indicator.yearRange} free-text DSL.
 *
 * <p>The production-data audit (H52 design doc) showed only two real shapes in use:
 * <ul>
 *   <li>{@code "*"} → {@link AllYears} (37 / 42 indicators)</li>
 *   <li>{@code "from->to"} or {@code "from-to"} → {@link Absolute} (5 / 42)</li>
 * </ul>
 *
 * The legacy grammar permitted {@code IY}, {@code IY+n}, comma-separated unions, and a
 * wildcard. None of those forms is used as a {@code yearRange} (they only appear in
 * {@code scoreYearRange}). v1 drops them at this level; see {@link ScoreYearRangeSpec}
 * for the relative-window semantics.
 */
public sealed interface YearRangeSpec
        permits YearRangeSpec.AllYears, YearRangeSpec.Absolute, YearRangeSpec.PreviousNYears {

    /** All available years. The literal {@code "*"} in the legacy DSL. */
    record AllYears() implements YearRangeSpec {}

    /** Inclusive range [from, to]. */
    record Absolute(int from, int to) implements YearRangeSpec {
        public Absolute {
            if (to < from) throw new IllegalArgumentException("to (" + to + ") < from (" + from + ")");
        }
    }

    /**
     * H60: a self-rolling recent window — the {@code n} years immediately before the run's referenceYear {@code t},
     * i.e. {@code [t-n .. t-1]} (the reference year itself is excluded). Matches the math standard's {@code A_recent}
     * (t-1…t-7). Resolves against the referenceYear, so it never goes stale.
     */
    record PreviousNYears(int n) implements YearRangeSpec {
        public PreviousNYears {
            if (n < 1) throw new IllegalArgumentException("n must be ≥ 1, got " + n);
        }
    }

    /**
     * H60: whether a publication of {@code pubYear} is included by this spec, given the run's {@code referenceYear}.
     * {@link AllYears} includes everything; {@link Absolute} is the fixed inclusive range; {@link PreviousNYears}
     * resolves to {@code [referenceYear-n .. referenceYear-1]}.
     */
    default boolean includes(int pubYear, int referenceYear) {
        if (this instanceof AllYears) return true;
        if (this instanceof Absolute a) return pubYear >= a.from() && pubYear <= a.to();
        if (this instanceof PreviousNYears p) return pubYear >= referenceYear - p.n() && pubYear <= referenceYear - 1;
        throw new IllegalStateException("Unhandled YearRangeSpec: " + this);
    }

    /**
     * Parse a legacy {@code yearRange} string. Accepts:
     * <ul>
     *   <li>{@code null} / blank → {@link AllYears} (defensive: legacy code treated this as "no constraint")</li>
     *   <li>{@code "*"} → {@link AllYears}</li>
     *   <li>{@code "from->to"} or {@code "from-to"} → {@link Absolute}</li>
     * </ul>
     * Anything else throws {@link IllegalArgumentException}.
     */
    static YearRangeSpec parse(String raw) {
        if (raw == null || raw.isBlank()) return new AllYears();
        String trimmed = raw.trim();
        if ("*".equals(trimmed)) return new AllYears();
        if (trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("PREV:")) {
            try {
                return new PreviousNYears(Integer.parseInt(trimmed.substring("PREV:".length()).trim()));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("PREV:n requires an integer n: " + raw, ex);
            }
        }
        String[] parts;
        if (trimmed.contains("->")) {
            parts = trimmed.split("->");
        } else if (trimmed.contains("-")) {
            parts = trimmed.split("-");
        } else {
            throw new IllegalArgumentException("Unrecognised yearRange: " + raw);
        }
        if (parts.length != 2) {
            throw new IllegalArgumentException("yearRange must be from->to: " + raw);
        }
        try {
            return new Absolute(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("yearRange parts must be integers: " + raw, ex);
        }
    }
}
