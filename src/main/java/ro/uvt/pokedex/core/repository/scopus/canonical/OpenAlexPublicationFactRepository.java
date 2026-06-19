package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OpenAlexPublicationFactRepository extends MongoRepository<OpenAlexPublicationFact, String> {
    Optional<OpenAlexPublicationFact> findBySourceRecordId(String sourceRecordId);
    List<OpenAlexPublicationFact> findBySourceRecordIdIn(Collection<String> sourceRecordIds);

    /** Source-facts whose syncedResearchers reference the given canonical author (re-point target on author merge). */
    List<OpenAlexPublicationFact> findBySyncedResearchersCanonicalAuthorId(String canonicalAuthorId);
}
