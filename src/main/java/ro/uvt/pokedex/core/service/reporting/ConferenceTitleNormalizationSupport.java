package ro.uvt.pokedex.core.service.reporting;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ConferenceTitleNormalizationSupport {

    private static final Pattern ORDINAL_PREFIX = Pattern.compile("^\\d+(st|nd|rd|th)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_TOKEN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Set<String> BOILERPLATE_TOKENS = Set.of(
            "proceedings", "of", "the", "international", "conference", "symposium", "workshop",
            "on", "for", "and", "in", "annual", "ieee", "acm", "ifip", "euromicro", "joint"
    );

    private ConferenceTitleNormalizationSupport() {
    }

    /**
     * Strip CORE's trailing history qualifier from a RANKING name. CORE stores AAMAS as "International Joint
     * Conference on Autonomous Agents and Multiagent Systems (previously the International Conference on
     * Multiagent Systems, ICMAS, changed in 2000)" — ~14 tokens of pure noise that sink every similarity
     * rule, which is how AAMAS papers scored D despite an exact acronym match (fixed at MATCH time in
     * H89/`eb1b882d`).
     *
     * <p>H92 moved it here because the same pollution existed at INDEX time: the conference-title lookup
     * behind {@link ReportingLookupPort#getConferenceRankingsByNormalizedTitle} was keyed on the raw name,
     * so "…Applications (was ICOIN)" indexed as "advanced information networking applications was icoin"
     * and a clean volume title could never match it. One definition, used by both the index build and the
     * match — the same lesson as {@link LectureNotesSeriesSupport}.
     *
     * <p>RANKING names only. Venue names carry meaningful parentheses (DBLP's "AINA (5)" volume markers)
     * that must survive.
     */
    public static String stripHistoryQualifier(String rawRankingName) {
        if (rawRankingName == null) {
            return null;
        }
        String stripped = rawRankingName.replaceAll("\\([^)]*\\)", " ").trim();
        return stripped.isBlank() ? rawRankingName : stripped;
    }

    public static String normalizeVenueName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value
                .replace('\u00A0', ' ')
                .replaceAll("[()/:\\-]", " ")
                .replaceAll("[,.;]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        normalized = ORDINAL_PREFIX.matcher(normalized).replaceFirst("");
        normalized = YEAR_TOKEN.matcher(normalized).replaceAll(" ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    public static String normalizeWithoutBoilerplate(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return "";
        }
        return Arrays.stream(normalizedValue.split("\\s+"))
                .filter(token -> !BOILERPLATE_TOKENS.contains(token))
                .collect(Collectors.joining(" "))
                .trim();
    }
}
