package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorSearchView;

import java.util.Collection;
import java.util.List;

public interface ScopusAuthorSearchViewRepository extends MongoRepository<ScopusAuthorSearchView, String> {
    List<ScopusAuthorSearchView> findByIdIn(Collection<String> ids);
    List<ScopusAuthorSearchView> findAllByNameContainingIgnoreCase(String authorName);
    List<ScopusAuthorSearchView> findAllByAffiliationIdsContaining(String affiliationId);
}
