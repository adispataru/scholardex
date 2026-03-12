package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;

@Service
@RequiredArgsConstructor
public class ScholardexAuthorQueryService {

    private final ReportingReadStoreSelector readStoreSelector;
    private final MongoScholardexAuthorReadPort mongoScholardexAuthorReadPort;
    private final ObjectProvider<PostgresScholardexAuthorReadPort> postgresScholardexAuthorReadPortProvider;

    public ScopusAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q) {
        return activePort().search(afid, page, size, sort, direction, q);
    }

    private ScholardexAuthorReadPort activePort() {
        if (!readStoreSelector.isPostgres()) {
            return mongoScholardexAuthorReadPort;
        }
        PostgresScholardexAuthorReadPort postgresPort = postgresScholardexAuthorReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres read-store selected but PostgresScholardexAuthorReadPort is not available.");
        }
        return postgresPort;
    }
}
