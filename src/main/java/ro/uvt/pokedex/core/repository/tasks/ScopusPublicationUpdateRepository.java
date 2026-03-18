package ro.uvt.pokedex.core.repository.tasks;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;

import java.util.List;

public interface ScopusPublicationUpdateRepository extends MongoRepository<ScopusPublicationUpdate, String> {
    public List<ScopusPublicationUpdate> findByScopusId(String scopusId);
    public List<ScopusPublicationUpdate> findByInitiator(String initiator);
    public List<ScopusPublicationUpdate> findByStatusOrderByInitiatedDate(Status status);

}
