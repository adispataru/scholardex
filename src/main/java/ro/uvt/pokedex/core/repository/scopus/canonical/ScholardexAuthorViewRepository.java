package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;

import java.util.Collection;
import java.util.List;

public interface ScholardexAuthorViewRepository extends MongoRepository<ScholardexAuthorView, String> {
    List<ScholardexAuthorView> findByIdIn(Collection<String> ids);
    List<ScholardexAuthorView> findAllByNameContainingIgnoreCase(String authorName);
    List<ScholardexAuthorView> findAllByAffiliationIdsContaining(String affiliationId);
}
