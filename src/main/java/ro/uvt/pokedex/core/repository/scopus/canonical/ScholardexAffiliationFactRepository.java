package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexAffiliationFactRepository extends MongoRepository<ScholardexAffiliationFact, String> {
    Optional<ScholardexAffiliationFact> findByScopusAffiliationIdsContains(String scopusAffiliationId);
    List<ScholardexAffiliationFact> findByScopusAffiliationIdsIn(Collection<String> scopusAffiliationIds);
    List<ScholardexAffiliationFact> findByIdIn(Collection<String> ids);
    List<ScholardexAffiliationFact> findAllByCountry(String country);
    List<ScholardexAffiliationFact> findAllByNameNormalizedContaining(String nameNormalized);
}
