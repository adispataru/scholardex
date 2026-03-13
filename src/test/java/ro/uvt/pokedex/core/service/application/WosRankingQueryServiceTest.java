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
        MongoWosRankingReadPort mongoPort = mock(MongoWosRankingReadPort.class);
        ObjectProvider<PostgresWosRankingReadPort> postgresProvider = provider(postgresPort);
        ObjectProvider<MongoWosRankingReadPort> mongoProvider = provider(mongoPort);
        WosRankingPageResponse response = new WosRankingPageResponse(List.of(), 0, 25, 0, 0);
        when(postgresPort.search(0, 25, "name", "asc", null)).thenReturn(response);

        WosRankingQueryService service = new WosRankingQueryService(postgresProvider, mongoProvider);

        WosRankingPageResponse result = service.search(0, 25, "name", "asc", null);

        assertSame(response, result);
        verify(postgresPort).search(0, 25, "name", "asc", null);
    }

    @Test
    void fallsBackToMongoWhenPostgresIsUnavailable() {
        MongoWosRankingReadPort mongoPort = mock(MongoWosRankingReadPort.class);
        ObjectProvider<PostgresWosRankingReadPort> postgresProvider = provider(null);
        ObjectProvider<MongoWosRankingReadPort> mongoProvider = provider(mongoPort);
        WosRankingPageResponse response = new WosRankingPageResponse(List.of(), 1, 50, 4, 1);
        when(mongoPort.search(1, 50, "issn", "desc", "ab")).thenReturn(response);

        WosRankingQueryService service = new WosRankingQueryService(postgresProvider, mongoProvider);

        WosRankingPageResponse result = service.search(1, 50, "issn", "desc", "ab");

        assertSame(response, result);
        verify(mongoPort).search(1, 50, "issn", "desc", "ab");
    }

    @Test
    void throwsWhenNoReadPortIsAvailable() {
        WosRankingQueryService service = new WosRankingQueryService(provider(null), provider(null));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.search(0, 25, "name", "asc", null)
        );

        org.junit.jupiter.api.Assertions.assertEquals("No WoS ranking read port is available.", error.getMessage());
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
