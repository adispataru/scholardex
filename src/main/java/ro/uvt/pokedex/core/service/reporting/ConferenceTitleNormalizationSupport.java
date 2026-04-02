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
