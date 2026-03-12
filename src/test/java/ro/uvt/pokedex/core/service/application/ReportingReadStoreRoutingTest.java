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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportingReadStoreRoutingTest {

    @Test
    void switchableReportingLookupFacadeUsesPostgresAdapter() {
        PostgresReportingLookupFacade postgresFacade = mock(PostgresReportingLookupFacade.class);
        ObjectProvider<PostgresReportingLookupFacade> provider = provider(postgresFacade);

        when(postgresFacade.getTopRankings("ECONOMICS - SCIE", 2024)).thenReturn(7);

        SwitchableReportingLookupFacade facade = new SwitchableReportingLookupFacade(provider);

        assertEquals(7, facade.getTopRankings("ECONOMICS - SCIE", 2024));
    }

    @Test
    void scopusRoutersUsePostgresPorts() {
        PostgresScholardexAuthorReadPort postgresAuthor = mock(PostgresScholardexAuthorReadPort.class);
        when(postgresAuthor.search(null, 0, 10, "name", "asc", null))
                .thenReturn(new ScopusAuthorPageResponse(List.of(), 0, 10, 0, 0));

        PostgresScholardexForumReadPort postgresForum = mock(PostgresScholardexForumReadPort.class);
        when(postgresForum.search(0, 10, "issn", "asc", null))
                .thenReturn(new ScopusForumPageResponse(List.of(), 0, 10, 0, 0));

        PostgresScholardexAffiliationReadPort postgresAffiliation = mock(PostgresScholardexAffiliationReadPort.class);
        when(postgresAffiliation.search(0, 10, "name", "asc", null))
                .thenReturn(new ScopusAffiliationPageResponse(List.of(), 0, 10, 0, 0));

        ScholardexAuthorQueryService authorService = new ScholardexAuthorQueryService(provider(postgresAuthor));
        ScholardexForumQueryService forumService = new ScholardexForumQueryService(provider(postgresForum));
        ScholardexAffiliationQueryService affiliationService = new ScholardexAffiliationQueryService(provider(postgresAffiliation));

        assertEquals(0, authorService.search(null, 0, 10, "name", "asc", null).totalItems());
        assertEquals(0, forumService.search(0, 10, "issn", "asc", null).totalItems());
        assertEquals(0, affiliationService.search(0, 10, "name", "asc", null).totalItems());
    }

    @Test
    void adminFacadeUsesPostgresPort() {
        PostgresScholardexAdminReadPort postgresPort = mock(PostgresScholardexAdminReadPort.class);
        when(postgresPort.buildPublicationSearchView("paper"))
                .thenReturn(new AdminScopusPublicationSearchViewModel(List.of(), Map.of()));

        ScholardexAdminReadFacade facade = new ScholardexAdminReadFacade(provider(postgresPort));

        assertEquals(0, facade.buildPublicationSearchView("paper").publications().size());
    }

    @Test
    void postgresPortsMustBeAvailableForRuntimeRouters() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresReportingLookupFacade> missingReportingProvider = mock(ObjectProvider.class);
        when(missingReportingProvider.getIfAvailable()).thenReturn(null);
        SwitchableReportingLookupFacade reportingFacade = new SwitchableReportingLookupFacade(missingReportingProvider);
        assertThrows(IllegalStateException.class, () -> reportingFacade.getTopRankings("A - SCIE", 2024));

        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresScholardexAuthorReadPort> missingAuthorProvider = mock(ObjectProvider.class);
        when(missingAuthorProvider.getIfAvailable()).thenReturn(null);
        ScholardexAuthorQueryService authorService = new ScholardexAuthorQueryService(missingAuthorProvider);
        assertThrows(IllegalStateException.class, () -> authorService.search(null, 0, 10, "name", "asc", null));
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
