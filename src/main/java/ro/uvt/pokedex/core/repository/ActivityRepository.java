package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.activities.Activity;

public interface ActivityRepository extends MongoRepository<Activity, String> {
}
