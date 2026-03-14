package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedPublicationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserDefinedPublicationFactRepository extends MongoRepository<UserDefinedPublicationFact, String> {
    Optional<UserDefinedPublicationFact> findBySourceRecordId(String sourceRecordId);
    List<UserDefinedPublicationFact> findBySourceRecordIdIn(Collection<String> sourceRecordIds);
}
