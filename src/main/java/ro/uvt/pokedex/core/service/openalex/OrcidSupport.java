package ro.uvt.pokedex.core.service.openalex;

import java.util.regex.Pattern;

/**
 * ORCID iD normalization (H66B Phase 4a). An ORCID is 16 digits in four hyphenated groups, the final
 * character optionally an uppercase {@code X} checksum (e.g. {@code 0000-0002-1825-0097}). We accept the
 * bare form, a URL form ({@code https://orcid.org/...}), or a digits-only form and normalize to the bare
 * hyphenated form so the profile field and the OpenAlex {@code author.orcid:} filter agree.
 */
public final class OrcidSupport {

    private static final Pattern URL_PREFIX = Pattern.compile("^https?://orcid\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE = Pattern.compile("^\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]$");
    private static final Pattern DIGITS16 = Pattern.compile("^\\d{15}[\\dX]$");

    private OrcidSupport() {
    }

    /** Returns the normalized bare ORCID, or {@code null} when blank/unparseable. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = URL_PREFIX.matcher(raw.trim()).replaceFirst("").trim().toUpperCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (BARE.matcher(value).matches()) {
            return value;
        }
        String digits = value.replace("-", "");
        if (DIGITS16.matcher(digits).matches()) {
            return digits.substring(0, 4) + "-" + digits.substring(4, 8) + "-"
                    + digits.substring(8, 12) + "-" + digits.substring(12, 16);
        }
        return null;
    }

    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }
}
