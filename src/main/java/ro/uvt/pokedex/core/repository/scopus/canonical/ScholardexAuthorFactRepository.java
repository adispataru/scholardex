package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexAuthorFactRepository extends MongoRepository<ScholardexAuthorFact, String> {
    Optional<ScholardexAuthorFact> findByScopusAuthorIdsContains(String scopusAuthorId);
    List<ScholardexAuthorFact> findByIdIn(Collection<String> ids);
    List<ScholardexAuthorFact> findAllByNameNormalizedContaining(String nameNormalized);
    List<ScholardexAuthorFact> findAllByAffiliationIdsContaining(String affiliationId);
}
