package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScholardexAdminReadFacadeRoutingLogicTest {

    @Test
    void buildPublicationSearchViewDelegatesToPostgresPort() {
        PostgresScholardexAdminReadPort postgresPort = mock(PostgresScholardexAdminReadPort.class);
        when(postgresPort.buildPublicationSearchView("paper"))
                .thenReturn(new AdminScopusPublicationSearchViewModel(List.of(), Map.of()));

        ScholardexAdminReadFacade facade = new ScholardexAdminReadFacade(provider(postgresPort));

        assertEquals(0, facade.buildPublicationSearchView("paper").publications().size());
        verify(postgresPort).buildPublicationSearchView("paper");
    }

    @Test
    void throwsWhenPostgresPortIsMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresScholardexAdminReadPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ScholardexAdminReadFacade facade = new ScholardexAdminReadFacade(provider);
        assertThrows(IllegalStateException.class, () -> facade.buildPublicationSearchView("paper"));
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
