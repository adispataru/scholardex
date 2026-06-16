package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Set;

public interface ReportingLookupPort {
    ScholardexForumView getForum(String forumId);

    List<WoSRanking> getRankingsByIssn(String issn);

    /**
     * Resolves WoS rankings for a forum the way the rest of the app does (see
     * {@code WosForumResolutionService}): by ISSN candidates and then by normalized journal name.
     * The default here is ISSN-only (issn, then e-issn) for back-compat; the Postgres facade overrides
     * it to add the name fallback so scoring resolves the same journal the {@code /forums/{id}} view
     * shows AIS for (forums with a missing/mismatched ISSN but a matching name).
     */
    default List<WoSRanking> getRankingsByForum(ScholardexForumView forum) {
        if (forum == null) {
            return List.of();
        }
        List<WoSRanking> rankings = getRankingsByIssn(forum.getIssn());
        if (rankings.isEmpty()) {
            rankings = getRankingsByIssn(forum.getEIssn());
        }
        return rankings;
    }

    List<CoreConferenceRanking> getConferenceRankings(String acronym);

    List<CoreConferenceRanking> getConferenceRankingsByNormalizedTitle(String normalizedTitle);

    int getTopRankings(String categoryIndex, Integer year);

    Set<String> getUniversityAuthorIds();

    /**
     * H52 slice 8a: the latest year for which the WoS / Scopus / CORE reference data
     * is considered authoritative. Replaces the {@code Integer LAST_YEAR = 2023}
     * interface constant that pre-v1 was hanging off {@link ScoringService}. Callers
     * cap forward-dated lookups to this year so an out-of-window publication doesn't
     * produce a nonsense {@code Q?} ranking against missing data.
     *
     * <p>Default implementation returns {@code 2023} — same value as the removed
     * interface constant. A live implementation should query the actual rankings
     * collections; bumping a property is the migration path until that lands.</p>
     */
    default int maxAvailableYear() {
        return 2023;
    }
}
