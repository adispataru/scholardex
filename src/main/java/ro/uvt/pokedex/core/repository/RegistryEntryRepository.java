package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.registry.RegistryEntry;
import ro.uvt.pokedex.core.model.registry.RegistryKind;

import java.util.List;

public interface RegistryEntryRepository extends MongoRepository<RegistryEntry, String> {

    List<RegistryEntry> findAllByKind(RegistryKind kind);
}
