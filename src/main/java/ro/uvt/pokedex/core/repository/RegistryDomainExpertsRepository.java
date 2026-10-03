package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.registry.RegistryDomainExperts;

public interface RegistryDomainExpertsRepository extends MongoRepository<RegistryDomainExperts, String> {
}
