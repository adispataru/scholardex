package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScopusPublicationFactRepository extends MongoRepository<ScopusPublicationFact, String> {
    Optional<ScopusPublicationFact> findByEid(String eid);
    List<ScopusPublicationFact> findByEidIn(Collection<String> eids);
}
