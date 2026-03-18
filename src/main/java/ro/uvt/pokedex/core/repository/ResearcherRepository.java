package ro.uvt.pokedex.core.repository;

import ro.uvt.pokedex.core.model.Researcher;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ResearcherRepository extends MongoRepository<Researcher, String> {
    List<Researcher> findByNameIgnoreCase(String authorName);

    List<Researcher> findByNameContainingIgnoreCase(String normalizedAuthorName);
}
