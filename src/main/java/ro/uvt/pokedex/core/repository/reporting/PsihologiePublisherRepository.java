package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.PsihologiePublisher;

public interface PsihologiePublisherRepository extends MongoRepository<PsihologiePublisher, String> {
}
