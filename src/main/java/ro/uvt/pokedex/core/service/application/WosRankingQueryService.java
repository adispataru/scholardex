package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;

@Service
@RequiredArgsConstructor
public class WosRankingQueryService {

    private final ObjectProvider<PostgresWosRankingReadPort> postgresWosRankingReadPortProvider;

    public WosRankingPageResponse search(int page, int size, String sort, String direction, String q) {
        PostgresWosRankingReadPort postgresPort = postgresWosRankingReadPortProvider.getIfAvailable();
        if (postgresPort != null) {
            return postgresPort.search(page, size, sort, direction, q);
        }

        throw new IllegalStateException("Postgres WoS ranking read port is not available.");
    }
}
