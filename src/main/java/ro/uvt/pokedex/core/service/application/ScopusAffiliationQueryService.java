package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;

@Service
@RequiredArgsConstructor
public class ScopusAffiliationQueryService {

    private final ReportingReadStoreSelector readStoreSelector;
    private final MongoScopusAffiliationReadPort mongoScopusAffiliationReadPort;
    private final ObjectProvider<PostgresScopusAffiliationReadPort> postgresScopusAffiliationReadPortProvider;

    public ScopusAffiliationPageResponse search(int page, int size, String sort, String direction, String q) {
        return activePort().search(page, size, sort, direction, q);
    }

    private ScopusAffiliationReadPort activePort() {
        if (!readStoreSelector.isPostgres()) {
            return mongoScopusAffiliationReadPort;
        }
        PostgresScopusAffiliationReadPort postgresPort = postgresScopusAffiliationReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres read-store selected but PostgresScopusAffiliationReadPort is not available.");
        }
        return postgresPort;
    }
}
