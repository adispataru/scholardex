package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.import_events")
@CompoundIndex(
        name = "uniq_scopus_import_event_idempotence",
        def = "{'entityType': 1, 'source': 1, 'sourceRecordId': 1, 'payloadHash': 1}",
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
}

