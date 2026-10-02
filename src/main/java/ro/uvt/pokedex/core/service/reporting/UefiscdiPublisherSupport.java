package ro.uvt.pokedex.core.service.reporting;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * H136 — the publishers of Anexa 7c of the PN-IV PD/TE 2026 packages ("Lista editurilor luate în considerare
 * pentru cărțile și volumele colective din domeniul științelor sociale", 253 names, the same list in both
 * packages). A declared book or chapter of the social/economic eligibility counts only at one of them.
 *
 * <p>Static registry, like {@link PredatoryVenueSupport}: {@link UefiscdiPublisherListService} loads the bundled
 * CSV at startup and registers it; the activity scoring binds {@code Editura_7c} from here without a constructor
 * dependency. Unregistered (unit tests) → nothing matches.</p>
 *
 * <p>Matching is by EXACT normalized name: lower-case, punctuation to spaces, and the list's own abbreviations
 * folded so that what a researcher types ("Cambridge University Press", "Harper and Row") meets the list's
 * "CAMBRIDGE UNIV. PRESS" / "HARPER & ROW". Substring matching would let "Press" match everything.</p>
 */
public final class UefiscdiPublisherSupport {

    private static volatile Set<String> anexa7c = Set.of();

    private UefiscdiPublisherSupport() {
    }

    public static void register(Collection<String> publisherNames) {
        Set<String> normalized = new HashSet<>();
        for (String name : publisherNames) {
            String n = normalize(name);
            if (!n.isEmpty()) {
                normalized.add(n);
            }
        }
        anexa7c = Set.copyOf(normalized);
    }

    /** True when the publisher, as typed, is on Anexa 7c. */
    public static boolean isOnAnexa7c(String publisherName) {
        String normalized = normalize(publisherName);
        return !normalized.isEmpty() && anexa7c.contains(normalized);
    }

    public static int size() {
        return anexa7c.size();
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String n = value.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        // the list abbreviates "University" and writes the German/Dutch forms in full elsewhere; fold both ways
        n = (" " + n + " ")
                .replace(" univ ", " university ")
                .replace(" universitaet ", " universitat ")
                .trim();
        return n.replaceAll("\\s+", " ");
    }
}
