package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;

@Service
@RequiredArgsConstructor
public class ScopusAuthorQueryService {

    private final ReportingReadStoreSelector readStoreSelector;
    private final MongoScopusAuthorReadPort mongoScopusAuthorReadPort;
    private final ObjectProvider<PostgresScopusAuthorReadPort> postgresScopusAuthorReadPortProvider;

    public ScopusAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q) {
        return activePort().search(afid, page, size, sort, direction, q);
    }

    private ScopusAuthorReadPort activePort() {
        if (!readStoreSelector.isPostgres()) {
            return mongoScopusAuthorReadPort;
        }
        PostgresScopusAuthorReadPort postgresPort = postgresScopusAuthorReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres read-store selected but PostgresScopusAuthorReadPort is not available.");
        }
        return postgresPort;
    }
}
