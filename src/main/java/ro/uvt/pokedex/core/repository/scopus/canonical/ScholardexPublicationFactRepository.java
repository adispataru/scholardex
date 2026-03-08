package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexPublicationFactRepository extends MongoRepository<ScholardexPublicationFact, String> {
    Optional<ScholardexPublicationFact> findByEid(String eid);
    Optional<ScholardexPublicationFact> findByWosId(String wosId);
    Optional<ScholardexPublicationFact> findByGoogleScholarId(String googleScholarId);
    Optional<ScholardexPublicationFact> findByUserSourceId(String userSourceId);
    List<ScholardexPublicationFact> findAllByDoiNormalized(String doiNormalized);
    List<ScholardexPublicationFact> findAllByEidIn(Collection<String> eids);
}
