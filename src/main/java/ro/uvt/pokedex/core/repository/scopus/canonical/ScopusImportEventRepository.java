package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;

public interface ScopusImportEventRepository extends MongoRepository<ScopusImportEvent, String> {
}
