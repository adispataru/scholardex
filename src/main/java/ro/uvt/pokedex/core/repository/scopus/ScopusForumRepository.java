package ro.uvt.pokedex.core.repository.scopus;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.Forum;

import java.util.Collection;
import java.util.List;

public interface ScopusForumRepository extends MongoRepository<Forum, String> {
    List<Forum> findByIdIn(Collection<String> forumKeys);
    List<Forum> findByScopusIdIn(Collection<String> forumKeys);

    List<Forum> findAllByAggregationTypeIn(List<String> agg);
}

