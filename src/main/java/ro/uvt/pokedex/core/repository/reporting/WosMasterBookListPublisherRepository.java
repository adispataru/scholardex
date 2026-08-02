package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.WosMasterBookListPublisher;

public interface WosMasterBookListPublisherRepository extends MongoRepository<WosMasterBookListPublisher, String> {
}
