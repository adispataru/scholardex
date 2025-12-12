package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.ActivityIndicator;

public interface ActivityIndicatorRepository extends MongoRepository<ActivityIndicator, String> {
}
