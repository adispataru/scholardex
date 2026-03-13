package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WosRankingQueryServiceTest {

    @Test
    void usesPostgresPortWhenAvailable() {
        PostgresWosRankingReadPort postgresPort = mock(PostgresWosRankingReadPort.class);
        ObjectProvider<PostgresWosRankingReadPort> postgresProvider = provider(postgresPort);
        WosRankingPageResponse response = new WosRankingPageResponse(List.of(), 0, 25, 0, 0);
        when(postgresPort.search(0, 25, "name", "asc", null)).thenReturn(response);

        WosRankingQueryService service = new WosRankingQueryService(postgresProvider);

        WosRankingPageResponse result = service.search(0, 25, "name", "asc", null);

        assertSame(response, result);
        verify(postgresPort).search(0, 25, "name", "asc", null);
    }

    @Test
    void throwsWhenPostgresReadPortIsUnavailable() {
        WosRankingQueryService service = new WosRankingQueryService(provider(null));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.search(0, 25, "name", "asc", null)
        );

        org.junit.jupiter.api.Assertions.assertEquals("Postgres WoS ranking read port is not available.", error.getMessage());
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
