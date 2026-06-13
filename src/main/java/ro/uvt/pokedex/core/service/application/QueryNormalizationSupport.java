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
     * Callers may append {@code .toUpperCase(Locale.ROOT)} for use in a JDBC ORDER BY clause.
     */
    public static String normalizeDirection(String direction) {
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "asc" -> "asc";
            case "desc" -> "desc";
            default -> throw new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc.");
        };
    }

    /** Validated direction in upper case ("ASC" or "DESC"); safe to splice into ORDER BY. */
    public static String normalizeDirectionUpper(String direction) {
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default -> throw new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc.");
        };
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

    /**
     * Upper-cases and strips punctuation from an ISSN for prefix search on *_norm fields.
     *
     * <p>Intentionally permissive — does NOT enforce the check digit, because callers use the result
     * for {@code LIKE 'prefix%'} matching where a partial ISSN (e.g. {@code "0036"}) is legitimate
     * input. For full-ISSN validation at ingestion, use {@link #isValidIssn(String)}.
     */
    public static String normalizeIssn(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "");
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * ISO 3297 ISSN check-digit (mod-11) validation. Accepts hyphenated or compact form; the input
     * must be a complete 8-character ISSN (7 digits + check digit, which may be {@code 'X'}).
     * Returns {@code false} for any malformed shape or wrong check digit.
     *
     * <p>Used at ingestion to reject check-digit-invalid ISSNs (real source typos) so they never
     * become forum-identity tokens. Distinct from {@link #normalizeIssn(String)}, which stays
     * permissive for prefix search.
     */
    public static boolean isValidIssn(String raw) {
        if (raw == null) return false;
        String compact = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "");
        if (compact.length() != 8) return false;
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            char c = compact.charAt(i);
            if (c < '0' || c > '9') return false;
            sum += (c - '0') * (8 - i);
        }
        int check = (11 - (sum % 11)) % 11;
        char expected = (check == 10) ? 'X' : (char) ('0' + check);
        return expected == compact.charAt(7);
    }
}
