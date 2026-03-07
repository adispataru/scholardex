package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;

import java.util.Optional;
import java.util.List;

public interface WosMetricFactRepository extends MongoRepository<WosMetricFact, String> {
    List<WosMetricFact> findAllByJournalId(String journalId);
    List<WosMetricFact> findAllByJournalIdIn(List<String> journalIds);

    Optional<WosMetricFact> findByJournalIdAndYearAndMetricType(
            String journalId,
            Integer year,
            MetricType metricType
    );
}
