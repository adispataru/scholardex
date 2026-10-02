package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.uvt.pokedex.core.model.ArtisticEvent;

import java.text.Normalizer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The rank of an artistic event (national, international, top international) by its name, from the registry
 * of artistic events — the CNFIS list of festivals and competitions per domain (H129).
 *
 * <p>Static registry, like {@link UefiscdiPublisherSupport}: {@link ArtisticEventRankRegistrar} hands it a
 * loader at startup and the activity scoring reads it without a constructor dependency; the registry is
 * loaded on first use, so a database that was unreachable at startup heals itself. Unregistered (unit tests)
 * → nothing is ranked.</p>
 *
 * <p>Matching is by the NORMALISED name: diacritics, case, punctuation and repeated spaces ignored, so
 * "Festivalul „George Enescu” (România)" and "festivalul george enescu romania" are the same event. An
 * event listed in several domains takes its best rank.</p>
 */
public final class ArtisticEventRankSupport {

    private static final Logger log = LoggerFactory.getLogger(ArtisticEventRankSupport.class);
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private static volatile Map<String, ArtisticEvent.Rank> ranks = Map.of();
    /** {normalised name without its parenthesised place, the name as listed}, for {@link #findIn}. */
    private static volatile java.util.List<String[]> phrases = java.util.List.of();
    private static volatile Supplier<? extends Collection<ArtisticEvent>> loader;
    private static volatile boolean loaded;

    private ArtisticEventRankSupport() {
    }

    /** Replaces the registry with these events. */
    public static synchronized void register(Collection<ArtisticEvent> events) {
        Map<String, ArtisticEvent.Rank> index = new HashMap<>();
        for (ArtisticEvent event : events) {
            String key = normalize(event.getName());
            if (key.isEmpty() || event.getRank() == null) {
                continue;
            }
            index.merge(key, event.getRank(), ArtisticEventRankSupport::better);
        }
        ranks = Map.copyOf(index);
        java.util.List<String[]> found = new java.util.ArrayList<>();
        for (ArtisticEvent event : events) {
            if (event.getName() == null || event.getRank() == null) {
                continue;
            }
            String core = normalize(event.getName().replaceAll("\\([^)]*\\)", " "));
            if (core.split(" ").length >= 2) {
                found.add(new String[]{core, event.getName()});
            }
        }
        phrases = java.util.List.copyOf(found);
        loaded = true;
    }

    /**
     * H142 — the listed event a free text names, word for word (its name without the parenthesised place, at least
     * two words), the longest when several do; empty otherwise. Cautious on purpose: "Festivalul Internațional
     * George Enescu" does not match "Festivalul George Enescu", so an item imported from a grid keeps the
     * visibility of its row rather than borrowing a rank it may not have.
     */
    public static Optional<String> findIn(String text) {
        String t = " " + normalize(text) + " ";
        if (t.isBlank()) {
            return Optional.empty();
        }
        ensureLoaded();
        String best = null;
        int bestLength = 0;
        for (String[] phrase : phrases) {
            if (phrase[0].length() > bestLength && t.contains(" " + phrase[0] + " ")) {
                best = phrase[1];
                bestLength = phrase[0].length();
            }
        }
        return Optional.ofNullable(best);
    }

    /** The source the registry is (re)loaded from on first use. */
    public static synchronized void registerLoader(Supplier<? extends Collection<ArtisticEvent>> source) {
        loader = source;
        loaded = false;
    }

    /** Back to the unregistered state (tests). */
    public static synchronized void reset() {
        ranks = Map.of();
        phrases = java.util.List.of();
        loader = null;
        loaded = false;
    }

    /** The event's rank, or empty when the name is blank or the registry does not list it. */
    public static Optional<ArtisticEvent.Rank> rankOf(String eventName) {
        String key = normalize(eventName);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        ensureLoaded();
        return Optional.ofNullable(ranks.get(key));
    }

    private static void ensureLoaded() {
        if (loaded || loader == null) {
            return;
        }
        synchronized (ArtisticEventRankSupport.class) {
            if (loaded || loader == null) {
                return;
            }
            try {
                register(loader.get());
            } catch (RuntimeException e) {
                log.warn("Registry of artistic events not loadable: {} — events stay unranked until the next try",
                        e.getMessage());
            }
        }
    }

    private static ArtisticEvent.Rank better(ArtisticEvent.Rank a, ArtisticEvent.Rank b) {
        return a.ordinal() <= b.ordinal() ? a : b; // INTERNATIONAL_TOP < INTERNATIONAL < NATIONAL
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String n = MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFKD)).replaceAll("");
        n = NON_ALNUM.matcher(n.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return n.trim().replaceAll("\\s+", " ");
    }
}
