package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexAuthorshipFactRepository extends MongoRepository<ScholardexAuthorshipFact, String> {
    Optional<ScholardexAuthorshipFact> findByPublicationIdAndAuthorIdAndSource(
            String publicationId,
            String authorId,
            String source
    );
    List<ScholardexAuthorshipFact> findByPublicationId(String publicationId);
    List<ScholardexAuthorshipFact> findByAuthorIdIn(Collection<String> authorIds);
}
