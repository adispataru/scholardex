package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminScopusFacadeRoutingLogicTest {

    @Test
    void mongoModeBuildPublicationSearchViewUsesCanonicalProjectionSearch() {
        ReportingReadStoreSelector selector = mock(ReportingReadStoreSelector.class);
        when(selector.isPostgres()).thenReturn(false);

        MongoAdminScopusReadPort mongoPort = mock(MongoAdminScopusReadPort.class);
        PostgresAdminScopusReadPort postgresPort = mock(PostgresAdminScopusReadPort.class);
        ScholardexProjectionReadService scopusProjectionReadService = mock(ScholardexProjectionReadService.class);

        Publication p1 = publication("p1", "Beta", "2024-01-01", List.of("a1"));
        Publication p2 = publication("p2", "Alpha", "2024-01-01", List.of("a1"));
        when(scopusProjectionReadService.findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc("paper"))
                .thenReturn(List.of(p1, p2));
        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");
        when(scopusProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));

        AdminScopusFacade facade = new AdminScopusFacade(
                selector,
                mongoPort,
                provider(postgresPort),
                scopusProjectionReadService
        );

        AdminScopusPublicationSearchViewModel vm = facade.buildPublicationSearchView("paper");

        assertEquals(List.of("p2", "p1"), vm.publications().stream().map(Publication::getId).toList());
        assertEquals("Author One", vm.authorMap().get("a1").getName());
        verify(scopusProjectionReadService).findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc("paper");
    }

    @Test
    void postgresModeBuildPublicationSearchViewDelegatesToPostgresPort() {
        ReportingReadStoreSelector selector = mock(ReportingReadStoreSelector.class);
        when(selector.isPostgres()).thenReturn(true);

        MongoAdminScopusReadPort mongoPort = mock(MongoAdminScopusReadPort.class);
        PostgresAdminScopusReadPort postgresPort = mock(PostgresAdminScopusReadPort.class);
        ScholardexProjectionReadService scopusProjectionReadService = mock(ScholardexProjectionReadService.class);

        when(postgresPort.buildPublicationSearchView("paper"))
                .thenReturn(new AdminScopusPublicationSearchViewModel(List.of(), Map.of()));

        AdminScopusFacade facade = new AdminScopusFacade(
                selector,
                mongoPort,
                provider(postgresPort),
                scopusProjectionReadService
        );

        assertEquals(0, facade.buildPublicationSearchView("paper").publications().size());
        verify(postgresPort).buildPublicationSearchView("paper");
    }

    private Publication publication(String id, String title, String coverDate, List<String> authors) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setTitle(title);
        publication.setCoverDate(coverDate);
        publication.setAuthors(authors);
        return publication;
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
