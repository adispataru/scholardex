package ro.uvt.pokedex.core.service.reporting;

import java.util.List;
import java.util.Optional;

/**
 * H129 — one CNFIS reporting and the rules that come with its year. CNFIS reports every two years over the
 * four calendar years before the reference date (1 January of the reporting year), so the windows of two
 * consecutive editions overlap by two years.
 * <p>
 * The timing rule (CNFIS guide, IC2.3): an article is classified by the list of its publication year, except
 * for the LAST year of the window, whose list is not public yet when the reporting happens — that year takes
 * the list of the year before ("pentru articolele publicate în anul 2024 se va avea ca referință lista JCR
 * din 2023"). It is a rule of the edition, not of what the platform has loaded: the same 2024 article is
 * classified by the 2023 list in edition 2025 and by its own 2024 list in edition 2027.
 *
 * @param reportingYear the year of the reference date, 1 January
 * @param windowStart   first publication year reported
 * @param windowEnd     last publication year reported
 * @param provisional   true while CNFIS has not published the guide of this edition: the rules of the last
 *                      published one are applied to the new window
 */
public record CnfisEdition(int reportingYear, int windowStart, int windowEnd, boolean provisional) {

    public static final CnfisEdition EDITION_2025 = new CnfisEdition(2025, 2021, 2024, false);
    public static final CnfisEdition EDITION_2027 = new CnfisEdition(2027, 2023, 2026, true);

    private static final List<CnfisEdition> KNOWN = List.of(EDITION_2025, EDITION_2027);

    public static List<CnfisEdition> known() {
        return KNOWN;
    }

    public static Optional<CnfisEdition> ofReportingYear(int reportingYear) {
        return KNOWN.stream().filter(e -> e.reportingYear() == reportingYear).findFirst();
    }

    /**
     * The edition reporting exactly this window, or — for a window nobody published rules for — an edition made
     * of the window itself, provisional, with the same timing rule.
     */
    public static CnfisEdition forWindow(int windowStart, int windowEnd) {
        return KNOWN.stream()
                .filter(e -> e.windowStart() == windowStart && e.windowEnd() == windowEnd)
                .findFirst()
                .orElseGet(() -> new CnfisEdition(windowEnd + 1, windowStart, windowEnd, true));
    }

    /** The last list that is public when this edition is reported. */
    public int lastListYear() {
        return windowEnd - 1;
    }

    /** The year of the classification list an article published in {@code publicationYear} is reported by. */
    public int listYearFor(int publicationYear) {
        return Math.min(publicationYear, lastListYear());
    }

    public boolean covers(int publicationYear) {
        return publicationYear >= windowStart && publicationYear <= windowEnd;
    }
}
