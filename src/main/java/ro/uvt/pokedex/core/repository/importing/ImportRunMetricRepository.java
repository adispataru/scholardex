package ro.uvt.pokedex.core.repository.importing;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.importing.ImportRunMetric;

import java.util.Optional;

public interface ImportRunMetricRepository extends MongoRepository<ImportRunMetric, String> {

    Optional<ImportRunMetric> findByRunIdAndSourceAndEntityTypeAndReason(
            String runId,
            String source,
            String entityType,
            String reason
    );
}
