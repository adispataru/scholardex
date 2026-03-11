package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;

@Service
@RequiredArgsConstructor
public class ScopusForumQueryService {

    private final ReportingReadStoreSelector readStoreSelector;
    private final MongoScopusForumReadPort mongoScopusForumReadPort;
    private final ObjectProvider<PostgresScopusForumReadPort> postgresScopusForumReadPortProvider;

    public ScopusForumPageResponse search(int page, int size, String sort, String direction, String q) {
        return activePort().search(page, size, sort, direction, q);
    }

    private ScopusForumReadPort activePort() {
        if (!readStoreSelector.isPostgres()) {
            return mongoScopusForumReadPort;
        }
        PostgresScopusForumReadPort postgresPort = postgresScopusForumReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres read-store selected but PostgresScopusForumReadPort is not available.");
        }
        return postgresPort;
    }
}
