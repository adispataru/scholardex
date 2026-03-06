package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;

public interface WosFactConflictRepository extends MongoRepository<WosFactConflict, String> {
}
