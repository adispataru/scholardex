package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorTouch;

import java.util.List;
import java.util.Optional;

public interface ScopusAuthorTouchRepository extends MongoRepository<ScopusAuthorTouch, String> {
    Optional<ScopusAuthorTouch> findBySourceAndAuthorId(String source, String authorId);
    List<ScopusAuthorTouch> findTop1000ByOrderByTouchedAtAsc();
}
