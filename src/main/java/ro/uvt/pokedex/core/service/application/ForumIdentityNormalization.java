package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * H66B M1 — forum identity normalization + token extraction, lifted verbatim out of the 1177-line
 * {@code WosScholardexOnboardingService} so the forum index and the (forthcoming) {@code ForumMergeEngine}
 * share one source of truth instead of duplicating it across the WoS and Scopus onboarding paths.
 *
 * <p>Pure functions only — no state, no I/O — so they are safe to call per record at scale (the merge engine
 * runs these on every source row). Behavior is identical to the extracted originals; this is a move, not a
 * rewrite.
 */
public final class ForumIdentityNormalization {

    private ForumIdentityNormalization() {
    }

    public static final String FORUM_DEFAULT_AGG = "Journal";

    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    // H55: the one journal pair where Scopus mislabels an eISSN (SIAM J. Computing's eISSN appears on SIAM
    // J. Mathematical Analysis). normalizeIssn returns the hyphenated form, so these hyphenated constants
    // match its output.
    private static final String SIAM_MATH_ANALYSIS_PRINT_ISSN = "0036-1410";
    private static final String SIAM_COMPUTING_EISSN = "1095-7111";
    private static final String SIAM_MATH_ANALYSIS_EISSN = "1095-7154";

    public static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Compact + check-digit-validate, then return the hyphenated {@code XXXX-XXXX} form; null if invalid. */
    public static String normalizeIssn(String rawIssn) {
        String value = normalizeBlank(rawIssn);
        if (value == null) {
            return null;
        }
        String compact = ISSN_NON_ALNUM.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        if (compact.length() != 8) {
            return null;
        }
        if (!QueryNormalizationSupport.isValidIssn(compact)) {
            // H55: reject check-digit-invalid ISSNs (source typos) — treated as absent so the forum resolves
            // by name instead of carrying a malformed identity token.
            return null;
        }
        return compact.substring(0, 4) + "-" + compact.substring(4);
    }

    public static String normalizeName(String rawName) {
        String value = normalizeBlank(rawName);
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeToken(String rawValue) {
        String value = normalizeBlank(rawValue);
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean containsToken(Collection<String> tokens, String value) {
        return value != null && tokens.contains(value);
    }

    public static void addIssnToken(List<String> tokens, String token) {
        if (token != null && !token.isBlank()) {
            tokens.add(token);
        }
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalizeBlank(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    public static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    public static String correctedScopusEIssn(ScopusForumFact scopusForum) {
        String rawEIssn = scopusForum.getEIssn();
        if (SIAM_MATH_ANALYSIS_PRINT_ISSN.equals(normalizeIssn(scopusForum.getIssn()))
                && SIAM_COMPUTING_EISSN.equals(normalizeIssn(rawEIssn))) {
            return SIAM_MATH_ANALYSIS_EISSN;
        }
        return rawEIssn;
    }

    public static boolean matchesIssn(ScopusForumFact scopusForum, Collection<String> issnTokens) {
        if (issnTokens == null || issnTokens.isEmpty()) {
            return false;
        }
        return containsToken(issnTokens, normalizeIssn(scopusForum.getIssn()))
                || containsToken(issnTokens, normalizeIssn(correctedScopusEIssn(scopusForum)));
    }

    public static boolean matchesIssn(ScholardexForumFact forum, Collection<String> issnTokens) {
        if (issnTokens == null || issnTokens.isEmpty()) {
            return false;
        }
        if (containsToken(issnTokens, normalizeIssn(forum.getIssn()))
                || containsToken(issnTokens, normalizeIssn(forum.getEIssn()))) {
            return true;
        }
        for (String alias : safeList(forum.getAliasIssns())) {
            if (containsToken(issnTokens, normalizeIssn(alias))) {
                return true;
            }
        }
        return false;
    }

    public static List<String> issnTokensOf(ScholardexForumFact forum) {
        List<String> tokens = new ArrayList<>();
        addIssnToken(tokens, normalizeIssn(forum.getIssn()));
        addIssnToken(tokens, normalizeIssn(forum.getEIssn()));
        for (String alias : safeList(forum.getAliasIssns())) {
            addIssnToken(tokens, normalizeIssn(alias));
        }
        return tokens;
    }

    public static List<String> scopusIssnTokens(ScopusForumFact scopusForum) {
        List<String> tokens = new ArrayList<>();
        addIssnToken(tokens, normalizeIssn(scopusForum.getIssn()));
        addIssnToken(tokens, normalizeIssn(correctedScopusEIssn(scopusForum)));
        return tokens;
    }

    public static String nameAggKeyOf(ScholardexForumFact forum) {
        return normalizeName(forum.getName()) + "|" + normalizeToken(forum.getAggregationType());
    }

    public static String scopusNameAggKey(ScopusForumFact scopusForum) {
        return normalizeName(scopusForum.getPublicationName()) + "|" + normalizeToken(scopusForum.getAggregationType());
    }
}
