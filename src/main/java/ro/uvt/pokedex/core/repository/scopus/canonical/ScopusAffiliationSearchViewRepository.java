package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationSearchView;

import java.util.List;

public interface ScopusAffiliationSearchViewRepository extends MongoRepository<ScopusAffiliationSearchView, String> {
    List<ScopusAffiliationSearchView> findAllByCountry(String country);
    List<ScopusAffiliationSearchView> findAllByNameContains(String name);
}
