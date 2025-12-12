package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;

import java.util.List;

public interface ActivityInstanceRepository extends MongoRepository<ActivityInstance, String> {
    List<ActivityInstance> findAllByResearcherId(String researcherId);
}
