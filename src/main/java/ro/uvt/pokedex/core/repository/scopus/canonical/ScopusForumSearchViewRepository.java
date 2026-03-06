package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumSearchView;

import java.util.Collection;
import java.util.List;

public interface ScopusForumSearchViewRepository extends MongoRepository<ScopusForumSearchView, String> {
    List<ScopusForumSearchView> findByIdIn(Collection<String> ids);
}
