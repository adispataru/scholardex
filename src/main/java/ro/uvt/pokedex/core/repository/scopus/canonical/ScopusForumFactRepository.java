package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;

import java.util.Optional;

public interface ScopusForumFactRepository extends MongoRepository<ScopusForumFact, String> {
    Optional<ScopusForumFact> findBySourceId(String sourceId);
}

