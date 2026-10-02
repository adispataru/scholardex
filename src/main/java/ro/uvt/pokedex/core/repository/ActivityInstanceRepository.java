package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;

import java.util.List;

public interface ActivityInstanceRepository extends MongoRepository<ActivityInstance, String> {
    List<ActivityInstance> findAllByResearcherId(String researcherId);

    /** H142 — the import keys a person already has, to skip what an import already brought. */
    List<ActivityInstance> findAllByResearcherIdAndImportKeyIn(String researcherId, java.util.Collection<String> importKeys);
}
