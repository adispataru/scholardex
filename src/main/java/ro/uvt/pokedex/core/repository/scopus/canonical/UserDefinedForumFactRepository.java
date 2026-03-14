package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedForumFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserDefinedForumFactRepository extends MongoRepository<UserDefinedForumFact, String> {
    Optional<UserDefinedForumFact> findBySourceRecordId(String sourceRecordId);
    List<UserDefinedForumFact> findBySourceRecordIdIn(Collection<String> sourceRecordIds);
}
