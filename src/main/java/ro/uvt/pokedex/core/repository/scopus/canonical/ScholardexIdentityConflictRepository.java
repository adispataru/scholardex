package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Query;

public interface ScholardexIdentityConflictRepository extends MongoRepository<ScholardexIdentityConflict, String> {
    Optional<ScholardexIdentityConflict> findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
            ScholardexEntityType entityType,
            String incomingSource,
            String incomingSourceRecordId,
            String reasonCode,
            String status
    );

    Optional<ScholardexIdentityConflict> findByIdAndStatus(String id, String status);

    /** H66: bulk-load all conflicts in a state for one entity type, to preload-and-skip per-row resolve lookups. */
    java.util.List<ScholardexIdentityConflict> findByEntityTypeAndStatus(ScholardexEntityType entityType, String status);

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

    @Query("{ 'candidateCanonicalIds.1': { $exists: true }, "
            + "'incomingSource': { $regex: ?0, $options: 'i' }, "
            + "'reasonCode': { $regex: ?1, $options: 'i' }, "
            + "'status': { $regex: ?2, $options: 'i' }, "
            + "'detectedAt': { $gte: ?3, $lte: ?4 } }")
    Page<ScholardexIdentityConflict> findNeedsReviewByFilters(
            String incomingSource,
            String reasonCode,
            String status,
            Instant detectedFrom,
            Instant detectedTo,
            Pageable pageable
    );

    @Query("{ 'candidateCanonicalIds.1': { $exists: true }, "
            + "'entityType': ?0, "
            + "'incomingSource': { $regex: ?1, $options: 'i' }, "
            + "'reasonCode': { $regex: ?2, $options: 'i' }, "
            + "'status': { $regex: ?3, $options: 'i' }, "
            + "'detectedAt': { $gte: ?4, $lte: ?5 } }")
    Page<ScholardexIdentityConflict> findNeedsReviewByEntityTypeAndFilters(
            ScholardexEntityType entityType,
            String incomingSource,
            String reasonCode,
            String status,
            Instant detectedFrom,
            Instant detectedTo,
            Pageable pageable
    );

    long countByStatus(String status);
    @Query(value = "{ 'candidateCanonicalIds.1': { $exists: true }, 'status': ?0 }", count = true)
    long countNeedsReviewByStatus(String status);
    @Query(value = "{ 'candidateCanonicalIds.1': { $exists: false } }", count = true)
    long countAuditOnlyDeterministic();
    long countByIncomingSourceAndStatus(String incomingSource, String status);

    long countByEntityTypeAndStatus(ScholardexEntityType entityType, String status);

    List<ScholardexIdentityConflict> findAllByIdIn(List<String> ids);
    Page<ScholardexIdentityConflict> findByIncomingSourceOrderByDetectedAtDesc(String incomingSource, Pageable pageable);
}
