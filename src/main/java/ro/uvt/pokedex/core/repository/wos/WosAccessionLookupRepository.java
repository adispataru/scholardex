package ro.uvt.pokedex.core.repository.wos;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.wos.WosAccessionLookup;

public interface WosAccessionLookupRepository extends MongoRepository<WosAccessionLookup, String> {
}
