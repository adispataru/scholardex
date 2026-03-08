package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;

import java.util.Optional;

public interface ScholardexIdentityConflictRepository extends MongoRepository<ScholardexIdentityConflict, String> {
    Optional<ScholardexIdentityConflict> findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
            ScholardexEntityType entityType,
            String incomingSource,
            String incomingSourceRecordId,
            String reasonCode,
            String status
    );
}
