package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;

@Service
@RequiredArgsConstructor
public class ScholardexForumQueryService {

    private final ReportingReadStoreSelector readStoreSelector;
    private final MongoScholardexForumReadPort mongoScholardexForumReadPort;
    private final ObjectProvider<PostgresScholardexForumReadPort> postgresScholardexForumReadPortProvider;

    public ScopusForumPageResponse search(int page, int size, String sort, String direction, String q) {
        return activePort().search(page, size, sort, direction, q);
    }

    private ScholardexForumReadPort activePort() {
        if (!readStoreSelector.isPostgres()) {
            return mongoScholardexForumReadPort;
        }
        PostgresScholardexForumReadPort postgresPort = postgresScholardexForumReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres read-store selected but PostgresScholardexForumReadPort is not available.");
        }
        return postgresPort;
    }
}
