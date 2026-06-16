package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosCoverageFact;

import java.util.List;

public interface WosCoverageFactRepository extends MongoRepository<WosCoverageFact, String> {
    List<WosCoverageFact> findAllByJournalIdIn(List<String> journalIds);
}
