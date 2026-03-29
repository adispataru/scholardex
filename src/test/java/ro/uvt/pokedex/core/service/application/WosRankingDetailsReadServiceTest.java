package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosRankingDetailsReadServiceTest {

    @Mock private PostgresWosRankingDetailsReadPort postgresWosRankingDetailsReadPort;

    private WosRankingDetailsReadService service;

    @BeforeEach
    void setUp() {
        service = new WosRankingDetailsReadService(postgresWosRankingDetailsReadPort);
    }

    @Test
    void returnsEmptyWhenProjectionMissing() {
        when(postgresWosRankingDetailsReadPort.findByJournalId("j1")).thenReturn(Optional.empty());
        assertTrue(service.findByJournalId("j1").isEmpty());
    }

    @Test
    void delegatesToPostgresPort() {
        WoSRanking ranking = new WoSRanking();
        ranking.setId("j1");
        when(postgresWosRankingDetailsReadPort.findByJournalId("j1")).thenReturn(Optional.of(ranking));

        Optional<WoSRanking> result = service.findByJournalId("j1");

        assertTrue(result.isPresent());
        assertEquals("j1", result.get().getId());
    }
}
