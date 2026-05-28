package ro.uvt.pokedex.core.service.reporting.transfer.projection;

/**
 * Maps a {@code Score.coreRankingEquivalent} (e.g. {@code "A_STAR"}, {@code "A"}, ...) to the
 * letter form the spreadsheet templates expect in their category columns. Templates use
 * {@code "AA"} as a stand-in for A* because the formula compares strings and {@code "A*"} clashes
 * with the wildcard in some Excel functions (see the note on {@code Centralizator!B29}).
 */
public final class CategoryLetterMapper {

    private CategoryLetterMapper() {}

    /**
     * Universal mapping: only normalizes the A* representation used by ranking-bearing scorers.
     * Use this for rows where {@code NON_RANK} is a legitimate "no forum ranking" value (e.g.
     * activity-block rows for keynotes, committees, etc.) and should be passed through (or treated
     * as empty by the caller).
     */
    public static String toTemplateLetter(String coreRankingEquivalent) {
        if (coreRankingEquivalent == null) return null;
        return switch (coreRankingEquivalent) {
            case "A_STAR", "A*" -> "AA";
            default -> coreRankingEquivalent;
        };
    }

    /**
     * Publication context: in addition to the A* normalization, treat {@code NON_RANK} as D —
     * the CS-Conference SCOPUS fallback explicitly assigns D for unranked Scopus-indexed forums,
     * and other publication scoring paths can leave {@code bestCategory} at the default
     * {@code NON_RANK} while still emitting positive points.
     */
    public static String toPublicationTemplateLetter(String coreRankingEquivalent) {
        String mapped = toTemplateLetter(coreRankingEquivalent);
        return "NON_RANK".equals(mapped) ? "D" : mapped;
    }
}
