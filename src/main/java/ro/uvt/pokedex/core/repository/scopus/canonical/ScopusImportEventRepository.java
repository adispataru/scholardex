package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;

import java.util.List;
import java.util.Optional;

public interface ScopusImportEventRepository extends MongoRepository<ScopusImportEvent, String> {
    List<ScopusImportEvent> findByBatchId(String batchId);

    /** Look up the single current event for a source-record identity (the H54.3a unique key). */
    Optional<ScopusImportEvent> findBySourceAndEntityTypeAndSourceRecordId(
            String source, ScopusImportEntityType entityType, String sourceRecordId);
}
