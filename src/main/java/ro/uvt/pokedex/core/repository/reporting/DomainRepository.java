package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.Domain;

import java.util.Optional;

public interface DomainRepository extends MongoRepository<Domain, String> {

    Optional<Domain> findByName(String name);
}
