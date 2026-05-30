package ro.uvt.pokedex.core.repository.importing;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.importing.ScholardexProjectionDirtyMarker;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexProjectionDirtyMarkerRepository extends MongoRepository<ScholardexProjectionDirtyMarker, String> {

    Optional<ScholardexProjectionDirtyMarker> findFirstByEntityTypeAndCanonicalEntityIdAndStatusInOrderByMarkedAtDesc(
            ScholardexEntityType entityType,
            String canonicalEntityId,
            Collection<String> statuses
    );

    List<ScholardexProjectionDirtyMarker> findByStatusInOrderByMarkedAtAsc(Collection<String> statuses);

    long countByStatus(String status);
}
