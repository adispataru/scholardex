package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexProjectFact;

import java.util.Optional;

public interface ScholardexProjectFactRepository extends MongoRepository<ScholardexProjectFact, String> {
    Optional<ScholardexProjectFact> findByEuGrantId(String euGrantId);
    Optional<ScholardexProjectFact> findByCode(String code);
}
