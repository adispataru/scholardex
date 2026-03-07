package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;

import java.util.List;

public interface ScopusImportEventRepository extends MongoRepository<ScopusImportEvent, String> {
    List<ScopusImportEvent> findByBatchId(String batchId);
}
