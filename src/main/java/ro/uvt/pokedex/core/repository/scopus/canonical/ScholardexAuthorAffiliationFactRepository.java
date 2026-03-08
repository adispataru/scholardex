package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexAuthorAffiliationFactRepository extends MongoRepository<ScholardexAuthorAffiliationFact, String> {
    Optional<ScholardexAuthorAffiliationFact> findByAuthorIdAndAffiliationIdAndSource(
            String authorId,
            String affiliationId,
            String source
    );
    List<ScholardexAuthorAffiliationFact> findByAuthorId(String authorId);
    List<ScholardexAuthorAffiliationFact> findByAuthorIdIn(Collection<String> authorIds);
    List<ScholardexAuthorAffiliationFact> findByAffiliationId(String affiliationId);
}
