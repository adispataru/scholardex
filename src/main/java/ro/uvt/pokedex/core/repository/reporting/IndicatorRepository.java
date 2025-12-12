package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.List;

public interface IndicatorRepository extends MongoRepository<Indicator, String> {
    // Custom query methods can be added here
    List<Indicator> findAllByOutputType(Indicator.Type type);
}
