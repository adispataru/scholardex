package ro.uvt.pokedex.core.service.application;

import java.util.Locale;

public final class QueryNormalizationSupport {

    public static final int MAX_QUERY_LENGTH = 100;

    private QueryNormalizationSupport() {}

    /** Returns trimmed/validated query string, or null if blank/absent. */
    public static String normalizeQuery(String q) {
        if (q == null) return null;
        String normalized = q.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "Invalid q parameter. Maximum length is " + MAX_QUERY_LENGTH + ".");
        }
        return normalized;
    }

    /**
     * Validates direction and returns it lowercase ("asc" or "desc").
     * Mongo callers: wrap with {@code Sort.Direction.fromString(...)}.
     * Postgres callers: append {@code .toUpperCase(Locale.ROOT)}.
     */
    public static String normalizeDirection(String direction) {
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("asc") && !normalized.equals("desc")) {
            throw new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc.");
        }
        return normalized;
    }

    /** Trims afid; returns null if absent or blank. */
    public static String normalizeAfid(String afid) {
        if (afid == null) return null;
        String normalized = afid.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Escapes special LIKE metacharacters for a named-parameter JDBC query. */
    public static String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** Lowercase-normalises free-text for prefix search on *_norm fields. */
    public static String normalizeText(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    /** Upper-cases and strips punctuation from an ISSN for prefix search on *_norm fields. */
    public static String normalizeIssn(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "");
        return normalized.isBlank() ? null : normalized;
    }
}
