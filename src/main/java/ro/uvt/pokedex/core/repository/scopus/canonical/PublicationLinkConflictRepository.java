package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationLinkConflict;

public interface PublicationLinkConflictRepository extends MongoRepository<PublicationLinkConflict, String> {
    Page<PublicationLinkConflict> findAllByEnrichmentSourceContainingIgnoreCaseAndKeyTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCase(
            String enrichmentSource,
            String keyType,
            String conflictReason,
            Pageable pageable
    );
}
