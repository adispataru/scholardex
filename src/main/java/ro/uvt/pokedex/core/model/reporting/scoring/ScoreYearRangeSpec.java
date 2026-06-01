package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * Typed replacement for {@code Indicator.scoreYearRange}. Three real shapes in production
 * (per the H52 audit):
 * <ul>
 *   <li>{@code "IY"} → {@link ItemYear} (31 / 42) — score in the publication's own year</li>
 *   <li>{@code "*"} → {@link AllYears} (7 / 42) — best score across all available years</li>
 *   <li>{@code "from->to"} or {@code "from-to"} → {@link Absolute} (4 / 42)</li>
 * </ul>
 *
 * The richer relative-window grammar from {@link ro.uvt.pokedex.core.model.reporting.Indicator#parseYearRange}
 * (e.g. {@code IY-1->IY+2}, comma-separated unions) is unused in production data and is NOT
 * carried into v1.
 */
public sealed interface ScoreYearRangeSpec
        permits ScoreYearRangeSpec.AllYears, ScoreYearRangeSpec.ItemYear, ScoreYearRangeSpec.Absolute {

    record AllYears() implements ScoreYearRangeSpec {}

    /** The publication's own year. */
    record ItemYear() implements ScoreYearRangeSpec {}

    /** Inclusive range [from, to]. */
    record Absolute(int from, int to) implements ScoreYearRangeSpec {
        public Absolute {
            if (to < from) throw new IllegalArgumentException("to (" + to + ") < from (" + from + ")");
        }
    }

    /**
     * Parse a legacy {@code scoreYearRange} string. Accepts:
     * <ul>
     *   <li>{@code null} / blank → {@link ItemYear} (matches legacy default behaviour)</li>
     *   <li>{@code "IY"} → {@link ItemYear}</li>
     *   <li>{@code "*"} → {@link AllYears}</li>
     *   <li>{@code "from->to"} or {@code "from-to"} → {@link Absolute}</li>
     * </ul>
     */
    static ScoreYearRangeSpec parse(String raw) {
        if (raw == null || raw.isBlank()) return new ItemYear();
        String trimmed = raw.trim();
        if ("IY".equals(trimmed)) return new ItemYear();
        if ("*".equals(trimmed)) return new AllYears();
        String[] parts;
        if (trimmed.contains("->")) {
            parts = trimmed.split("->");
        } else if (trimmed.contains("-")) {
            parts = trimmed.split("-");
        } else {
            throw new IllegalArgumentException("Unrecognised scoreYearRange: " + raw);
        }
        if (parts.length != 2) {
            throw new IllegalArgumentException("scoreYearRange must be from->to: " + raw);
        }
        try {
            return new Absolute(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("scoreYearRange parts must be integers: " + raw, ex);
        }
    }
}
