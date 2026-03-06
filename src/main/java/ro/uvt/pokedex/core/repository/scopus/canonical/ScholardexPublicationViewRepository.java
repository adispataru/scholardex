package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexPublicationViewRepository extends MongoRepository<ScholardexPublicationView, String> {
    Optional<ScholardexPublicationView> findByEid(String eid);
    Optional<ScholardexPublicationView> findByWosId(String wosId);
    Optional<ScholardexPublicationView> findByGoogleScholarId(String googleScholarId);
    List<ScholardexPublicationView> findAllByIdIn(Collection<String> ids);
    List<ScholardexPublicationView> findAllByEidIn(Collection<String> eids);
    List<ScholardexPublicationView> findAllByAuthorIdsIn(Collection<String> authorIds);
    List<ScholardexPublicationView> findAllByAffiliationIdsContaining(String affiliationId);
    List<ScholardexPublicationView> findByTitleContainingIgnoreCaseOrderByCoverDateDesc(String title);
}
