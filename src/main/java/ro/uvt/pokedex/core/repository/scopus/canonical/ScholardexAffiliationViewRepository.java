package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;

import java.util.Collection;
import java.util.List;

public interface ScholardexAffiliationViewRepository extends MongoRepository<ScholardexAffiliationView, String> {
    List<ScholardexAffiliationView> findByIdIn(Collection<String> ids);
    List<ScholardexAffiliationView> findAllByCountry(String country);
    List<ScholardexAffiliationView> findAllByNameContains(String name);
}
