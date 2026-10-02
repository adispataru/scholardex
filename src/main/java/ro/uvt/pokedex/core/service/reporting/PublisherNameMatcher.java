package ro.uvt.pokedex.core.service.reporting;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * H143 — matches a publisher name as a researcher types it against a name on an official list. Both are reduced to
 * their distinctive words: no diacritics, no case, no punctuation, without the words every publisher shares
 * ("Editura", "SC", "SRL") and without linking words ("de", "din"). Then the names match when they are equal, when
 * the listed name sits inside the typed one ("Editura Polirom, Iași"), or when the typed name — at least two
 * distinctive words — sits inside the listed one ("Editura Universității de Vest" in "… de Vest din Timișoara").
 */
public final class PublisherNameMatcher {

    private static final Set<String> GENERIC = Set.of("editura", "ed", "sc", "srl", "sa", "ra", "publishing", "publishers",
            "publisher", "verlag", "editions", "edizioni", "editorial", "ltd", "inc", "gmbh", "co");
    private static final Set<String> LINKING = Set.of("de", "din", "a", "al", "ale", "la", "si", "pentru", "the", "of",
            "and", "und", "et", "y", "e");

    private PublisherNameMatcher() {
    }

    /** The distinctive words of a publisher name, in order. */
    public static List<String> words(String name) {
        if (name == null) {
            return List.of();
        }
        String n = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{Alnum}]+", " ").trim();
        List<String> out = new ArrayList<>();
        for (String token : n.split(" ")) {
            if (token.length() >= 2 && !GENERIC.contains(token) && !LINKING.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * How well a typed name matches a listed one: 3 equal, 2 the listed name inside the typed one, 1 the typed name
     * inside the listed one, 0 no match. Among several listed names the best and then the longest wins.
     */
    public static int match(List<String> listed, List<String> typed) {
        if (listed.isEmpty() || typed.isEmpty()) {
            return 0;
        }
        if (listed.equals(typed)) {
            return 3;
        }
        if (containsRun(typed, listed)) {
            return 2;
        }
        if (typed.size() >= 2 && containsRun(listed, typed)) {
            return 1;
        }
        return 0;
    }

    private static boolean containsRun(List<String> haystack, List<String> needle) {
        if (needle.size() > haystack.size()) {
            return false;
        }
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            if (haystack.subList(i, i + needle.size()).equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
