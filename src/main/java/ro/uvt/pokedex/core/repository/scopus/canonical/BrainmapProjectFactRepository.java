package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.BrainmapProjectFact;

import java.util.List;

public interface BrainmapProjectFactRepository extends MongoRepository<BrainmapProjectFact, String> {
    List<BrainmapProjectFact> findBySourceBatchId(String sourceBatchId);
}
