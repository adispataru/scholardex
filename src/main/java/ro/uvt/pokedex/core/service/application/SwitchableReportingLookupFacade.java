package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.List;
import java.util.Set;

@Service
@Primary
@RequiredArgsConstructor
public class SwitchableReportingLookupFacade implements ReportingLookupPort {

    private final ReportingReadStoreSelector readStoreSelector;
    private final ProjectionBackedReportingLookupFacade mongoFacade;
    private final ObjectProvider<PostgresReportingLookupFacade> postgresFacadeProvider;

    @Override
    public Forum getForum(String forumId) {
        return activeFacade().getForum(forumId);
    }

    @Override
    public List<WoSRanking> getRankingsByIssn(String issn) {
        return activeFacade().getRankingsByIssn(issn);
    }

    @Override
    public List<CoreConferenceRanking> getConferenceRankings(String acronym) {
        return activeFacade().getConferenceRankings(acronym);
    }

    @Override
    public int getTopRankings(String categoryIndex, Integer year) {
        return activeFacade().getTopRankings(categoryIndex, year);
    }

    @Override
    public Set<String> getUniversityAuthorIds() {
        return activeFacade().getUniversityAuthorIds();
    }

    private ReportingLookupPort activeFacade() {
        if (!readStoreSelector.isPostgres()) {
            return mongoFacade;
        }
        PostgresReportingLookupFacade postgresFacade = postgresFacadeProvider.getIfAvailable();
        if (postgresFacade == null) {
            throw new IllegalStateException("Postgres read-store selected but PostgresReportingLookupFacade is not available.");
        }
        return postgresFacade;
    }
}
