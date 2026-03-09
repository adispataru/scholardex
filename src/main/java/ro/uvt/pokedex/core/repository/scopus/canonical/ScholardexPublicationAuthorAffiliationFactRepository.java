package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexPublicationAuthorAffiliationFactRepository
        extends MongoRepository<ScholardexPublicationAuthorAffiliationFact, String> {

    Optional<ScholardexPublicationAuthorAffiliationFact> findByPublicationIdAndAuthorIdAndAffiliationIdAndSource(
            String publicationId,
            String authorId,
            String affiliationId,
            String source
    );

    List<ScholardexPublicationAuthorAffiliationFact> findByPublicationIdIn(Collection<String> publicationIds);
}
