package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
@Primary
@RequiredArgsConstructor
public class ReportingLookupFacade implements ReportingLookupPort {

    private final PostgresReportingLookupFacade postgresFacade;
    private final ScholardexBookFactRepository bookFactRepository;

    @Override
    public ScholardexForumView getForum(String forumId) {
        return postgresFacade.getForum(forumId);
    }

    @Override
    public boolean isForumInScopus(String forumId) {
        return postgresFacade.isForumInScopus(forumId);
    }

    @Override
    public boolean isForumInEsci(String forumId, int year) {
        return postgresFacade.isForumInEsci(forumId, year);
    }

    @Override
    public boolean isForumInAhci(String forumId, int year) {
        return postgresFacade.isForumInAhci(forumId, year);
    }

    @Override
    public ScholardexBookFact getBook(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return null;
        }
        return bookFactRepository.findById(bookId).orElse(null);
    }

    @Override
    public List<WoSRanking> getRankingsByIssn(String issn) {
        return postgresFacade.getRankingsByIssn(issn);
    }

    @Override
    public List<WoSRanking> getRankingsByForum(ScholardexForumView forum) {
        return postgresFacade.getRankingsByForum(forum);
    }

    @Override
    public List<WoSRanking> getForumRankings(
            ScholardexForumView forum, Collection<Integer> years, Collection<String> categories) {
        return postgresFacade.getForumRankings(forum, years, categories);
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
