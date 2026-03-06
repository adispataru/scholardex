package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;

import java.util.Optional;

public interface WosMetricFactRepository extends MongoRepository<WosMetricFact, String> {
    Optional<WosMetricFact> findByJournalIdAndYearAndMetricTypeAndEditionNormalized(
            String journalId,
            Integer year,
            MetricType metricType,
            EditionNormalized editionNormalized
    );
}
