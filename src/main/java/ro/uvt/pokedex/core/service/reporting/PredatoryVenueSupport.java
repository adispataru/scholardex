package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.Locale;
import java.util.Set;

/**
 * Venues the CNATDCU/Informatica-2016 standard explicitly excludes from consideration (perspective b, page 1):
 * "nu și forumuri de genul WSEAS, IAENG, DAAAM …". A paper published in such a venue earns NO points in any
 * indicator (it is not on the A*–D lists at all), so the CS scorers short-circuit to an empty score.
 *
 * <p>This is Phase 1 — the small, unambiguous, standard-named families (matched as whole-word tokens in the forum
 * name or publisher, to avoid substring false positives). The broader Beall's predatory-publisher list is a
 * separate, data-loaded follow-up that needs a corpus dry-run before enabling.</p>
 */
public final class PredatoryVenueSupport {

    private static final Set<String> EXCLUDED_FAMILIES = Set.of("wseas", "iaeng", "daaam");

    private PredatoryVenueSupport() {
    }

    /** Whether the forum is one of the standard's named-excluded venue families (by name or publisher). */
    public static boolean isExcludedVenue(ScholardexForumView forum) {
        if (forum == null) {
            return false;
        }
        return matchesExcludedFamily(forum.getPublicationName())
                || matchesExcludedFamily(forum.getPublisher());
    }

    private static boolean matchesExcludedFamily(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        // Tokenise (non-alphanumerics -> spaces) and match as a whole word, so "WSEAS Transactions …" and
        // "Proceedings of the 9th WSEAS/IASME …" both hit while unrelated substrings do not.
        String padded = " " + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        for (String family : EXCLUDED_FAMILIES) {
            if (padded.contains(" " + family + " ")) {
                return true;
            }
        }
        return false;
    }
}
