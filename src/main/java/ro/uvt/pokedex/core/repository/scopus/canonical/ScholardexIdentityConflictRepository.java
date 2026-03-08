package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ScholardexIdentityConflictRepository extends MongoRepository<ScholardexIdentityConflict, String> {
    Optional<ScholardexIdentityConflict> findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
            ScholardexEntityType entityType,
            String incomingSource,
            String incomingSourceRecordId,
            String reasonCode,
            String status
    );

    Optional<ScholardexIdentityConflict> findByIdAndStatus(String id, String status);

    Page<ScholardexIdentityConflict> findAllByIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
            String incomingSource,
            String reasonCode,
            String status,
            Instant detectedFrom,
            Instant detectedTo,
            Pageable pageable
    );

    Page<ScholardexIdentityConflict> findAllByEntityTypeAndIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
            ScholardexEntityType entityType,
            String incomingSource,
            String reasonCode,
            String status,
            Instant detectedFrom,
            Instant detectedTo,
            Pageable pageable
    );

    long countByStatus(String status);

    long countByEntityTypeAndStatus(ScholardexEntityType entityType, String status);

    List<ScholardexIdentityConflict> findAllByIdIn(List<String> ids);
}
