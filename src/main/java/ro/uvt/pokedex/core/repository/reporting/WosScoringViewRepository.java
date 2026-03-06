package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;

public interface WosScoringViewRepository extends MongoRepository<WosScoringView, String> {
}
