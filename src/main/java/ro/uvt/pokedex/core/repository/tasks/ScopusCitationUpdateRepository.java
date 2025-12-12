package ro.uvt.pokedex.core.repository.tasks;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;

import java.util.List;

public interface ScopusCitationUpdateRepository extends MongoRepository<ScopusCitationsUpdate, String> {
    public List<ScopusCitationsUpdate> findByScopusId(String scopusId);
    public List<ScopusCitationsUpdate> findByInitiator(String initiator);
    public List<ScopusCitationsUpdate> findByStatusOrderByInitiatedDate(Status status);

}
