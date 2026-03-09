package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationTouch;

import java.util.List;
import java.util.Optional;

public interface ScopusCitationTouchRepository extends MongoRepository<ScopusCitationTouch, String> {
    Optional<ScopusCitationTouch> findBySourceAndCitedEidAndCitingEid(String source, String citedEid, String citingEid);
    List<ScopusCitationTouch> findTop1000ByOrderByTouchedAtAsc();
}
