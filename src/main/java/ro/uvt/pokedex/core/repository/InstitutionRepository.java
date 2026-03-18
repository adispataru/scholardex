package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.Institution;

import java.util.List;

public interface InstitutionRepository extends MongoRepository<Institution, String> {
    List<Institution> findByNameIgnoreCase(String name);
}
