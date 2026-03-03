package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;

import java.util.List;
import java.util.Set;

public interface ReportingLookupPort {
    Forum getForum(String forumId);

    List<WoSRanking> getRankingsByIssn(String issn);

    List<CoreConferenceRanking> getConferenceRankings(String acronym);

    int getTopRankings(String categoryIndex, Integer year);

    Set<String> getUniversityAuthorIds();
}
