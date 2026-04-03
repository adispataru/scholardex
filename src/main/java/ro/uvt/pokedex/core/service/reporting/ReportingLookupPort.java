package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Set;

public interface ReportingLookupPort {
    ScholardexForumView getForum(String forumId);

    List<WoSRanking> getRankingsByIssn(String issn);

    List<CoreConferenceRanking> getConferenceRankings(String acronym);

    List<CoreConferenceRanking> getConferenceRankingsByNormalizedTitle(String normalizedTitle);

    int getTopRankings(String categoryIndex, Integer year);

    Set<String> getUniversityAuthorIds();
}
