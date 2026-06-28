package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact;

import java.util.Optional;

public interface UserDefinedProjectFactRepository extends MongoRepository<UserDefinedProjectFact, String> {
    Optional<UserDefinedProjectFact> findByEuGrantId(String euGrantId);
    Optional<UserDefinedProjectFact> findByCode(String code);
}
