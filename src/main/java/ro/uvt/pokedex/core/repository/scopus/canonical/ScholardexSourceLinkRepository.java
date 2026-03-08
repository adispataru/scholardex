package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScholardexSourceLinkRepository extends MongoRepository<ScholardexSourceLink, String> {
    Optional<ScholardexSourceLink> findByEntityTypeAndSourceAndSourceRecordId(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId
    );

    List<ScholardexSourceLink> findByEntityTypeAndSourceRecordId(
            ScholardexEntityType entityType,
            String sourceRecordId
    );

    List<ScholardexSourceLink> findByEntityTypeAndCanonicalEntityId(
            ScholardexEntityType entityType,
            String canonicalEntityId
    );

    Page<ScholardexSourceLink> findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
            String source,
            String linkState,
            Instant updatedFrom,
            Instant updatedTo,
            Pageable pageable
    );

    Page<ScholardexSourceLink> findAllByEntityTypeAndSourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
            ScholardexEntityType entityType,
            String source,
            String linkState,
            Instant updatedFrom,
            Instant updatedTo,
            Pageable pageable
    );

    long countByLinkState(String linkState);
}
