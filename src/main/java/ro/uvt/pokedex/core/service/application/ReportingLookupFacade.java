package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.List;
import java.util.Set;

@Service
@Primary
@RequiredArgsConstructor
public class ReportingLookupFacade implements ReportingLookupPort {

    private final PostgresReportingLookupFacade postgresFacade;

    @Override
    public ScholardexForumView getForum(String forumId) {
        return postgresFacade.getForum(forumId);
    }

    @Override
    public List<WoSRanking> getRankingsByIssn(String issn) {
        return postgresFacade.getRankingsByIssn(issn);
    }

    @Override
    public List<CoreConferenceRanking> getConferenceRankings(String acronym) {
        return postgresFacade.getConferenceRankings(acronym);
    }

    @Override
    public List<CoreConferenceRanking> getConferenceRankingsByNormalizedTitle(String normalizedTitle) {
        return postgresFacade.getConferenceRankingsByNormalizedTitle(normalizedTitle);
    }

    @Override
    public int getTopRankings(String categoryIndex, Integer year) {
        return postgresFacade.getTopRankings(categoryIndex, year);
    }

    @Override
    public Set<String> getUniversityAuthorIds() {
        return postgresFacade.getUniversityAuthorIds();
    }
}
