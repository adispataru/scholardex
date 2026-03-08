package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;

public interface ScholardexCanonicalBuildCheckpointRepository extends MongoRepository<ScholardexCanonicalBuildCheckpoint, String> {
}
