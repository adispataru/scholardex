package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScopusAuthorFactRepository extends MongoRepository<ScopusAuthorFact, String> {
    Optional<ScopusAuthorFact> findByAuthorId(String authorId);
    List<ScopusAuthorFact> findByAuthorIdIn(Collection<String> authorIds);
}
