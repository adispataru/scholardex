package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.Collection;
import java.util.List;

public interface ScholardexForumViewRepository extends MongoRepository<ScholardexForumView, String> {
    List<ScholardexForumView> findByIdIn(Collection<String> ids);
}
