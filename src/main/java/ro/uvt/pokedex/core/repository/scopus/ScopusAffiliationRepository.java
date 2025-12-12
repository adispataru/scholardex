package ro.uvt.pokedex.core.repository.scopus;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.Affiliation;

import java.util.List;

public interface ScopusAffiliationRepository extends MongoRepository<Affiliation, String> {

    List<Affiliation> findAllByCountry(String country);
    List<Affiliation> findAllByNameContains(String name);

}

