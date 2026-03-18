package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.CNCSISPublisher;

import java.util.List;

public interface CNCSISPublisherRepository extends MongoRepository<CNCSISPublisher, String> {
    // Custom query methods can be added here
    List<CNCSISPublisher> findAllByName(String name);
    List<CNCSISPublisher> findAllByNameIgnoreCase(String name);

}
