package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;

import java.util.Optional;
import java.util.List;
import java.util.Set;

public interface WosCategoryFactRepository extends MongoRepository<WosCategoryFact, String> {
    List<WosCategoryFact> findAllByJournalId(String journalId);
    List<WosCategoryFact> findAllByJournalIdInAndEditionNormalizedIn(List<String> journalIds, Set<EditionNormalized> editions);

    Optional<WosCategoryFact> findByJournalIdAndYearAndCategoryNameCanonicalAndEditionNormalizedAndMetricType(
            String journalId,
            Integer year,
            String categoryNameCanonical,
            EditionNormalized editionNormalized,
            MetricType metricType
    );
}
