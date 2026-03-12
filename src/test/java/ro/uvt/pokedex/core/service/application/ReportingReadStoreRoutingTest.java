package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportingReadStoreRoutingTest {

    @Test
    void switchableReportingLookupFacadeUsesMongoWhenSelectorIsMongo() {
        ReportingReadStoreSelector selector = mock(ReportingReadStoreSelector.class);
        ProjectionBackedReportingLookupFacade mongoFacade = mock(ProjectionBackedReportingLookupFacade.class);
        PostgresReportingLookupFacade postgresFacade = mock(PostgresReportingLookupFacade.class);
        ObjectProvider<PostgresReportingLookupFacade> provider = provider(postgresFacade);

        when(selector.isPostgres()).thenReturn(false);
        when(mongoFacade.getTopRankings("ECONOMICS - SCIE", 2024)).thenReturn(7);

        SwitchableReportingLookupFacade facade = new SwitchableReportingLookupFacade(selector, mongoFacade, provider);

        assertEquals(7, facade.getTopRankings("ECONOMICS - SCIE", 2024));
    }

    @Test
    void switchableReportingLookupFacadeUsesPostgresWhenSelectorIsPostgres() {
        ReportingReadStoreSelector selector = mock(ReportingReadStoreSelector.class);
        ProjectionBackedReportingLookupFacade mongoFacade = mock(ProjectionBackedReportingLookupFacade.class);
        PostgresReportingLookupFacade postgresFacade = mock(PostgresReportingLookupFacade.class);
        ObjectProvider<PostgresReportingLookupFacade> provider = provider(postgresFacade);

        when(selector.isPostgres()).thenReturn(true);
        when(postgresFacade.getRankingsByIssn("1234-5678")).thenReturn(List.of(new WoSRanking()));

        SwitchableReportingLookupFacade facade = new SwitchableReportingLookupFacade(selector, mongoFacade, provider);

        assertEquals(1, facade.getRankingsByIssn("1234-5678").size());
    }

    @Test
    void scopusRoutersUsePostgresWhenSelectorIsPostgres() {
        ReportingReadStoreSelector selector = mock(ReportingReadStoreSelector.class);
        when(selector.isPostgres()).thenReturn(true);

        MongoScholardexAuthorReadPort mongoAuthor = mock(MongoScholardexAuthorReadPort.class);
        PostgresScholardexAuthorReadPort postgresAuthor = mock(PostgresScholardexAuthorReadPort.class);
        when(postgresAuthor.search(null, 0, 10, "name", "asc", null))
                .thenReturn(new ScopusAuthorPageResponse(List.of(), 0, 10, 0, 0));

        MongoScholardexForumReadPort mongoForum = mock(MongoScholardexForumReadPort.class);
        PostgresScholardexForumReadPort postgresForum = mock(PostgresScholardexForumReadPort.class);
        when(postgresForum.search(0, 10, "issn", "asc", null))
                .thenReturn(new ScopusForumPageResponse(List.of(), 0, 10, 0, 0));

        MongoScholardexAffiliationReadPort mongoAffiliation = mock(MongoScholardexAffiliationReadPort.class);
        PostgresScholardexAffiliationReadPort postgresAffiliation = mock(PostgresScholardexAffiliationReadPort.class);
        when(postgresAffiliation.search(0, 10, "name", "asc", null))
                .thenReturn(new ScopusAffiliationPageResponse(List.of(), 0, 10, 0, 0));

        ScholardexAuthorQueryService authorService = new ScholardexAuthorQueryService(selector, mongoAuthor, provider(postgresAuthor));
        ScholardexForumQueryService forumService = new ScholardexForumQueryService(selector, mongoForum, provider(postgresForum));
        ScholardexAffiliationQueryService affiliationService = new ScholardexAffiliationQueryService(selector, mongoAffiliation, provider(postgresAffiliation));

        assertEquals(0, authorService.search(null, 0, 10, "name", "asc", null).totalItems());
        assertEquals(0, forumService.search(0, 10, "issn", "asc", null).totalItems());
        assertEquals(0, affiliationService.search(0, 10, "name", "asc", null).totalItems());
    }

    @Test
    void adminFacadeUsesPostgresWhenSelectorIsPostgres() {
        ReportingReadStoreSelector selector = mock(ReportingReadStoreSelector.class);
        when(selector.isPostgres()).thenReturn(true);

        MongoScholardexAdminReadPort mongoPort = mock(MongoScholardexAdminReadPort.class);
        PostgresScholardexAdminReadPort postgresPort = mock(PostgresScholardexAdminReadPort.class);
        ScholardexProjectionReadService scholardexProjectionReadService = mock(ScholardexProjectionReadService.class);
        when(postgresPort.buildPublicationSearchView("paper"))
                .thenReturn(new AdminScopusPublicationSearchViewModel(List.of(), Map.of()));

        ScholardexAdminReadFacade facade = new ScholardexAdminReadFacade(selector, mongoPort, provider(postgresPort), scholardexProjectionReadService);

        assertEquals(0, facade.buildPublicationSearchView("paper").publications().size());
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
