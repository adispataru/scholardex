package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CacheBackedReportingLookupFacade implements ReportingLookupPort {

    private final CacheService cacheService;

    @Override
    public Forum getForum(String forumId) {
        return cacheService.getCachedForums(forumId);
    }

    @Override
    public List<WoSRanking> getRankingsByIssn(String issn) {
        return cacheService.getCachedRankingsByIssn(issn);
    }

    @Override
    public List<CoreConferenceRanking> getConferenceRankings(String acronym) {
        return cacheService.getCachedConfRankings(acronym);
    }

    @Override
    public int getTopRankings(String categoryIndex, Integer year) {
        return cacheService.getCachedTopRankings(categoryIndex, year);
    }

    @Override
    public Set<String> getUniversityAuthorIds() {
        return cacheService.getUniversityAuthorIds();
    }
}
