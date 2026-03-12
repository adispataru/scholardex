package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;

@Service
@RequiredArgsConstructor
public class ScholardexAffiliationQueryService {

    private final ReportingReadStoreSelector readStoreSelector;
    private final MongoScholardexAffiliationReadPort mongoScholardexAffiliationReadPort;
    private final ObjectProvider<PostgresScholardexAffiliationReadPort> postgresScholardexAffiliationReadPortProvider;

    public ScopusAffiliationPageResponse search(int page, int size, String sort, String direction, String q) {
        return activePort().search(page, size, sort, direction, q);
    }

    private ScholardexAffiliationReadPort activePort() {
        if (!readStoreSelector.isPostgres()) {
            return mongoScholardexAffiliationReadPort;
        }
        PostgresScholardexAffiliationReadPort postgresPort = postgresScholardexAffiliationReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres read-store selected but PostgresScholardexAffiliationReadPort is not available.");
        }
        return postgresPort;
    }
}
