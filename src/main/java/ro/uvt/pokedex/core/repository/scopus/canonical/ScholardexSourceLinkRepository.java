package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;

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
}
