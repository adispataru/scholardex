package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.Locale;
import java.util.Set;

/**
 * Venues the CNATDCU/Informatica-2016 standard excludes from consideration. A paper in such a venue earns NO
 * points in any indicator (it is not on the A*–D lists at all), so the CS scorers short-circuit to an empty score.
 *
 * <p>Two layers:</p>
 * <ul>
 *   <li><b>Phase 1 — named families.</b> The standard's explicit "forumuri de genul WSEAS, IAENG, DAAAM" — a tiny
 *       hard-coded set, matched as whole-word tokens in the forum name or publisher (no external data).</li>
 *   <li><b>Phase 2 — Beall's predatory list.</b> A larger data-loaded list (predatory publishers + standalone
 *       journals) supplied at startup by {@link PredatoryVenueService} through {@link #register}. Matched by
 *       EXACT normalized name (substring matching is catastrophic at that scale), minus an allowlist. Absent in
 *       plain unit tests (registry stays null) so only the named families apply there.</li>
 * </ul>
 */
public final class PredatoryVenueSupport {

    private static final Set<String> EXCLUDED_FAMILIES = Set.of("wseas", "iaeng", "daaam");

    /** The data-loaded Beall's list, registered by {@link PredatoryVenueService} at startup; null in unit tests. */
    private static volatile PredatoryList predatoryList;

    /** Exact-match predatory lookup over the forum name and publisher; supplied by the data-loaded service. */
    public interface PredatoryList {
        boolean isPredatory(String forumName, String publisher);
    }

    public static void register(PredatoryList list) {
        predatoryList = list;
    }

    private PredatoryVenueSupport() {
    }

    /** Whether the forum is standard-excluded — a named family, or (when loaded) a Beall's predatory venue. */
    public static boolean isExcludedVenue(ScholardexForumView forum) {
        if (forum == null) {
            return false;
        }
        if (matchesExcludedFamily(forum.getPublicationName()) || matchesExcludedFamily(forum.getPublisher())) {
            return true;
        }
        PredatoryList list = predatoryList;
        return list != null && list.isPredatory(forum.getPublicationName(), forum.getPublisher());
    }

    /** Lowercase, collapse non-alphanumerics to single spaces, trim — the shared key for exact-match lookups. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static boolean matchesExcludedFamily(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return false;
        }
        String padded = " " + normalized + " ";
        for (String family : EXCLUDED_FAMILIES) {
            if (padded.contains(" " + family + " ")) {
                return true;
            }
        }
        return false;
    }
}
