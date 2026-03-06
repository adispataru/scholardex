package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;

import java.util.Optional;

public interface ScopusAffiliationFactRepository extends MongoRepository<ScopusAffiliationFact, String> {
    Optional<ScopusAffiliationFact> findByAfid(String afid);
}

