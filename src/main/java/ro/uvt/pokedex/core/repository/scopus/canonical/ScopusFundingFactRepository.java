package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusFundingFact;

import java.util.Optional;

public interface ScopusFundingFactRepository extends MongoRepository<ScopusFundingFact, String> {
    Optional<ScopusFundingFact> findByFundingKey(String fundingKey);
}

