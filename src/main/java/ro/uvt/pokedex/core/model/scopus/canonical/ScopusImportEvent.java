package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.import_events")
// H54.3a: the unique key is the source-record identity (source, entityType, sourceRecordId),
// NOT including payloadHash. Including the hash made a re-import with a corrected payload create
// a NEW row instead of superseding the old one. The ledger now holds exactly one current event
// per source record; ScopusImportEventIngestionService supersedes in place when the payload
// changes and skips when it is identical.
@CompoundIndex(
        name = "uniq_scopus_import_event_idempotence",
        def = "{'source': 1, 'entityType': 1, 'sourceRecordId': 1}",
        unique = true
)
public class ScopusImportEvent {
    @Id
    private String id;
    private ScopusImportEntityType entityType;
    private String source;
    private String sourceRecordId;
    private String batchId;
    private String correlationId;
    private String payloadFormat;
    private String payload;
    private String payloadHash;
    private Instant ingestedAt;
    /** First-write time is {@link #ingestedAt}; this is the last-write time (changes only on supersede). */
    private Instant updatedAt;
    /** Number of distinct payloads seen for this key: 1 on first ingest, +1 each supersede. */
    private int version;
}

