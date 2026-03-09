package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationTouch;

import java.util.List;
import java.util.Optional;

public interface ScopusPublicationTouchRepository extends MongoRepository<ScopusPublicationTouch, String> {
    Optional<ScopusPublicationTouch> findBySourceAndEid(String source, String eid);
    List<ScopusPublicationTouch> findTop1000ByOrderByTouchedAtAsc();
}
