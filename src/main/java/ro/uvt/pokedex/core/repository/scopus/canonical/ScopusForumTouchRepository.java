package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumTouch;

import java.util.List;
import java.util.Optional;

public interface ScopusForumTouchRepository extends MongoRepository<ScopusForumTouch, String> {
    Optional<ScopusForumTouch> findBySourceAndSourceId(String source, String sourceId);
    List<ScopusForumTouch> findTop1000ByOrderByTouchedAtAsc();
}
