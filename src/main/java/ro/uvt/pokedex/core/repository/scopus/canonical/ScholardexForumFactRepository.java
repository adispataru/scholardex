package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.List;

public interface ScholardexForumFactRepository extends MongoRepository<ScholardexForumFact, String> {
    List<ScholardexForumFact> findByWosForumIdsContaining(String wosForumId);
}
