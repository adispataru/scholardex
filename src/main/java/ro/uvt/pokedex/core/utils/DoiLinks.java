package ro.uvt.pokedex.core.utils;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * H106 S4 — one place that turns whatever DOI shape the corpus holds into a resolver link.
 * <p>
 * DOIs arrive bare ({@code 10.1007/978-3-…}), as URLs ({@code https://doi.org/10.…}, {@code http://dx.doi.org/…})
 * or with a {@code doi:} prefix, occasionally with surrounding whitespace. {@link #normalize} returns the bare
 * {@code 10.xxxx/…} path (lower-cased prefix, suffix case kept — DOI suffixes are case-insensitive but some
 * resolvers echo them) or {@code null}; {@link #resolverUrl} wraps it as {@code https://doi.org/…}.
 * Also registered as the {@code doiLinks} bean so templates can call {@code ${@doiLinks.resolverUrl(...)}}.
 */
@Component("doiLinks")
public class DoiLinks {

    private static final String RESOLVER = "https://doi.org/";

    /** Bare DOI path ({@code 10.xxxx/…}) or null when the value carries no DOI. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int idx = s.toLowerCase(Locale.ROOT).indexOf("10.");
        if (idx < 0) return null;
        String path = s.substring(idx).trim();
        // A DOI is "10.<registrant>/<suffix>"; anything without the slash is not one.
        int slash = path.indexOf('/');
        if (slash <= 3 || slash == path.length() - 1) return null;
        return path;
    }

    /** {@code https://doi.org/<bare doi>} or null when the value carries no DOI. */
    public static String resolverUrl(String raw) {
        String doi = normalize(raw);
        return doi != null ? RESOLVER + doi : null;
    }

    /** Instance form for Thymeleaf ({@code @doiLinks}). */
    public String url(String raw) {
        return resolverUrl(raw);
    }

    public String bare(String raw) {
        return normalize(raw);
    }
}
