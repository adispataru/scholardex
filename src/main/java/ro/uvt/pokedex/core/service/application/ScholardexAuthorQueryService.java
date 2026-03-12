package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;

@Service
@RequiredArgsConstructor
public class ScholardexAuthorQueryService {

    private final ObjectProvider<PostgresScholardexAuthorReadPort> postgresScholardexAuthorReadPortProvider;

    public ScopusAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q) {
        PostgresScholardexAuthorReadPort postgresPort = postgresScholardexAuthorReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres author read port is not available.");
        }
        return postgresPort.search(afid, page, size, sort, direction, q);
    }
}
