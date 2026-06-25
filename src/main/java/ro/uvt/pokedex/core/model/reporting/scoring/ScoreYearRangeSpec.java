package ro.uvt.pokedex.core.model.reporting.scoring;

import java.util.ArrayList;
import java.util.List;

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
        permits ScoreYearRangeSpec.AllYears, ScoreYearRangeSpec.ItemYear, ScoreYearRangeSpec.Absolute,
                ScoreYearRangeSpec.LatestNRankings {

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
     * H60: score against the {@code n} most recent ranking list-years <b>present in our DB and ≤ the run's
     * referenceYear</b> (not the journal's own available years). A journal dropped from the latest list is therefore
     * <i>not found → excluded</i> instead of silently scored against an older year where it was still ranked.
     * Requires a {@link ResolutionContext} carrying {@code referenceYear + availableRankingYears}; the legacy
     * no-context {@link #allowedYears(int)} cannot resolve it and returns empty.
     */
    record LatestNRankings(int n) implements ScoreYearRangeSpec {
        public LatestNRankings {
            if (n < 1) throw new IllegalArgumentException("n must be ≥ 1, got " + n);
        }
    }

    /**
     * H60: the context a relative {@link ScoreYearRangeSpec} resolves against. {@code referenceYear} (the run's
     * evaluation anchor) and {@code availableRankingYears} (distinct ranking list-years in the DB) are null/empty on
     * the legacy path; {@link LatestNRankings} only resolves when both are supplied.
     */
    record ResolutionContext(int itemYear, Integer referenceYear, List<Integer> availableRankingYears) {
        public static ResolutionContext ofItemYear(int itemYear) {
            return new ResolutionContext(itemYear, null, List.of());
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
    /**
     * H52 slice 11d.1: returns the concrete year list to score against, given the
     * publication or activity's own year. Mirrors what the legacy
     * {@code Indicator.parseYearRange(scoreYearRange, itemYear)} produced, but without
     * the open-text grammar.
     *
     * <ul>
     *   <li>{@link ItemYear} → {@code [itemYear]}</li>
     *   <li>{@link AllYears} → {@code [1990 .. currentYear]} (matches legacy {@code "*"} behavior)</li>
     *   <li>{@link Absolute} → {@code [from, from+1, ..., to]}</li>
     * </ul>
     */
    default List<Integer> allowedYears(int itemYear) {
        return allowedYears(ResolutionContext.ofItemYear(itemYear));
    }

    /**
     * H60: context-aware resolution. Identical to {@link #allowedYears(int)} for the absolute shapes; {@link AllYears}
     * caps at {@code referenceYear} when supplied (else {@code now()}, preserving the legacy path); {@link
     * LatestNRankings} returns the {@code n} most recent {@code availableRankingYears} that are ≤ {@code referenceYear}
     * (empty when the context lacks either input — the legacy no-context path).
     */
    default List<Integer> allowedYears(ResolutionContext ctx) {
        if (this instanceof ItemYear) {
            List<Integer> single = new ArrayList<>(1);
            single.add(ctx.itemYear());
            return single;
        }
        if (this instanceof AllYears) {
            int cap = ctx.referenceYear() != null ? ctx.referenceYear() : java.time.LocalDate.now().getYear();
            List<Integer> years = new ArrayList<>(Math.max(0, cap - 1990 + 1));
            for (int y = 1990; y <= cap; y++) years.add(y);
            return years;
        }
        if (this instanceof Absolute a) {
            List<Integer> years = new ArrayList<>(a.to() - a.from() + 1);
            for (int y = a.from(); y <= a.to(); y++) years.add(y);
            return years;
        }
        if (this instanceof LatestNRankings latest) {
            if (ctx.referenceYear() == null || ctx.availableRankingYears() == null
                    || ctx.availableRankingYears().isEmpty()) {
                return List.of();
            }
            return ctx.availableRankingYears().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(y -> y <= ctx.referenceYear())
                    .distinct()
                    .sorted(java.util.Comparator.reverseOrder())
                    .limit(latest.n())
                    .toList();
        }
        throw new IllegalStateException("Unhandled ScoreYearRangeSpec: " + this);
    }

    static ScoreYearRangeSpec parse(String raw) {
        if (raw == null || raw.isBlank()) return new ItemYear();
        String trimmed = raw.trim();
        if ("IY".equals(trimmed)) return new ItemYear();
        if ("*".equals(trimmed)) return new AllYears();
        if (trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("LATEST:")) {
            try {
                return new LatestNRankings(Integer.parseInt(trimmed.substring("LATEST:".length()).trim()));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("LATEST:n requires an integer n: " + raw, ex);
            }
        }
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
