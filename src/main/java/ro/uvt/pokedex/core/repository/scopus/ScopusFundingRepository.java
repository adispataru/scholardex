package ro.uvt.pokedex.core.repository.scopus;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.Funding;

import java.util.Optional;

public interface ScopusFundingRepository extends MongoRepository<Funding, String> {
    Optional<Funding> findByAcronymAndNumberAndSponsor(String acr, String num, String sp);
}
