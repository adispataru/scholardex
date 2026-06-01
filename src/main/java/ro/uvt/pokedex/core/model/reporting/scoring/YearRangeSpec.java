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
public sealed interface YearRangeSpec permits YearRangeSpec.AllYears, YearRangeSpec.Absolute {

    /** All available years. The literal {@code "*"} in the legacy DSL. */
    record AllYears() implements YearRangeSpec {}

    /** Inclusive range [from, to]. */
    record Absolute(int from, int to) implements YearRangeSpec {
        public Absolute {
            if (to < from) throw new IllegalArgumentException("to (" + to + ") < from (" + from + ")");
        }
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
