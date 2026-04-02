package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;

import java.util.Optional;

public interface ScholardexPublicationDblpEvidenceRepository extends MongoRepository<ScholardexPublicationDblpEvidence, String> {
    Optional<ScholardexPublicationDblpEvidence> findByPublicationId(String publicationId);
}
