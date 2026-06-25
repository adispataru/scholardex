package ro.uvt.pokedex.core.service.importing.wos;

import java.util.List;

/**
 * H76 S1: the dry-run result of matching WoS CPCI proceedings records against our forum/publication registry —
 * what we'd tag WoS-indexed, by which key, and what we'd miss. No writes; this is the number that decides whether
 * the apply step (S2) is worth running and surfaces the venues a broader export should target.
 */
public record WosCpciMatchReport(
        int totalRecords,
        int matchedByDoi,
        int matchedByIssnIsbn,
        int matchedByTitle,
        int unmatched,
        int distinctForumsMatched,
        int forumsAlreadyWos,
        int forumsNetNew,
        List<String> netNewForumIdsSample,
        List<UnmatchedVenue> topUnmatchedVenues
) {
    /** A venue (conference/source title) that matched no forum, with how many records carry it. */
    public record UnmatchedVenue(String title, int recordCount) {
    }
}
