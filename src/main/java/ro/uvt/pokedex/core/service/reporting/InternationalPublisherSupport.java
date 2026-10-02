package ro.uvt.pokedex.core.service.reporting;

import java.util.List;
import java.util.Optional;

/**
 * H143 — the international lists and rankings of publishers beyond the WoS Master Book List: SENSE (ranks A and B, as
 * the Computer Science standard reads it), the UEFISCDI lists of the social sciences and of the arts and humanities, and
 * whatever further ranking {@code report-data/international-publisher-lists.csv} names. They decide where a standard
 * asks for a publisher "de prestigiu internațional" (Comisia 25 Lista A1, Comisia 28 A1) or for a foreign one equivalent
 * to CNCS A or B (Comisia 35) without listing such houses itself — before any head is asked.
 *
 * <p>Static registry, like {@link UefiscdiPublisherSupport}: {@link InternationalPublisherListService} registers at
 * startup; until then, and in tests that register nothing, no list holds anything and no name reads as Romanian.</p>
 */
public final class InternationalPublisherSupport {

    /** The list that holds a publisher: its key, the label a researcher reads, and the name as the list writes it. */
    public record Recognition(String key, String label, String listedName) {
        public String detail() {
            return label + ": " + listedName;
        }
    }

    public interface Lists {
        /** The first list that holds the publisher; always empty for a Romanian house or an excluded one. */
        Optional<Recognition> recognize(String publisher);

        /** The list with this key, if it holds the publisher (a standard that names one list reads it first). */
        Optional<Recognition> recognizeOn(String key, String publisher);

        /** A Romanian house: written «Editura …», or named as on a Romanian list (CNCS, the commissions' lists). */
        boolean isRomanian(String publisher);

        /** Every name the lists hold, as they write it — to find a publisher named inside an imported line. */
        List<String> names();
    }

    private static final Lists NONE = new Lists() {
        @Override
        public Optional<Recognition> recognize(String publisher) {
            return Optional.empty();
        }

        @Override
        public Optional<Recognition> recognizeOn(String key, String publisher) {
            return Optional.empty();
        }

        @Override
        public boolean isRomanian(String publisher) {
            return false;
        }

        @Override
        public List<String> names() {
            return List.of();
        }
    };
    private static volatile Lists lists = NONE;

    private InternationalPublisherSupport() {
    }

    public static void register(Lists newLists) {
        lists = newLists == null ? NONE : newLists;
    }

    public static void reset() {
        lists = NONE;
    }

    public static Optional<Recognition> recognize(String publisher) {
        if (publisher == null || publisher.isBlank()) {
            return Optional.empty();
        }
        return lists.recognize(publisher.trim());
    }

    public static Optional<Recognition> recognizeOn(String key, String publisher) {
        if (key == null || publisher == null || publisher.isBlank()) {
            return Optional.empty();
        }
        return lists.recognizeOn(key, publisher.trim());
    }

    public static boolean isRomanian(String publisher) {
        return publisher != null && !publisher.isBlank() && lists.isRomanian(publisher.trim());
    }

    public static List<String> names() {
        return lists.names();
    }
}
