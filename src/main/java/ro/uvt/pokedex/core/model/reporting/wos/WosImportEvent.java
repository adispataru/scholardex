package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "wos.import_events")
@CompoundIndex(name = "uniq_wos_import_event_key", def = "{'sourceType': 1, 'sourceFile': 1, 'sourceVersion': 1, 'sourceRowItem': 1}", unique = true)
public class WosImportEvent {
    @Id
    private String id;
    private WosSourceType sourceType;
    private String sourceFile;
    private String sourceVersion;
    private String checksum;
    private String payloadFormat;
    private String payload;
    private String sourceRowItem;
    /** First-write time; preserved across supersedes (H54.3b). */
    private Instant ingestedAt;
    /** Last-write time; changes only when the payload is superseded (H54.3b). */
    private Instant updatedAt;
    /** Number of distinct payloads seen for this key: 1 on first ingest, +1 each supersede (H54.3b). */
    private int version;
}
