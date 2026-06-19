package ro.uvt.pokedex.core.repository.tasks;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;

import java.util.List;

public interface OpenAlexAuthorUpdateRepository extends MongoRepository<OpenAlexAuthorUpdate, String> {
    List<OpenAlexAuthorUpdate> findByOrcid(String orcid);
    List<OpenAlexAuthorUpdate> findByInitiator(String initiator);
    List<OpenAlexAuthorUpdate> findByStatusOrderByInitiatedDate(Status status);
}
