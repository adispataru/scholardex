package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationTouch;

import java.util.List;
import java.util.Optional;

public interface ScopusAffiliationTouchRepository extends MongoRepository<ScopusAffiliationTouch, String> {
    Optional<ScopusAffiliationTouch> findBySourceAndAfid(String source, String afid);
    List<ScopusAffiliationTouch> findTop1000ByOrderByTouchedAtAsc();
}
