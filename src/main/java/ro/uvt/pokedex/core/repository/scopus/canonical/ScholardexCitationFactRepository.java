package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexCitationFactRepository extends MongoRepository<ScholardexCitationFact, String> {
    Optional<ScholardexCitationFact> findByCitedPublicationIdAndCitingPublicationId(
            String citedPublicationId,
            String citingPublicationId
    );

    List<ScholardexCitationFact> findBySourceBatchId(String sourceBatchId);
    List<ScholardexCitationFact> findByCitedPublicationId(String citedPublicationId);

    List<ScholardexCitationFact> findByCitedPublicationIdIn(Collection<String> citedPublicationIds);

    List<ScholardexCitationFact> findByCitingPublicationIdIn(Collection<String> citingPublicationIds);
}
