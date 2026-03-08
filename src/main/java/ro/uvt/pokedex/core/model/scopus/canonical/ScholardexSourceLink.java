package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scholardex.source_links")
@CompoundIndex(
        name = "uniq_scholardex_source_link",
        def = "{'entityType': 1, 'source': 1, 'sourceRecordId': 1}",
        unique = true
)
public class ScholardexSourceLink {
    @Id
    private String id;
    private ScholardexEntityType entityType;
    private String source;
    private String sourceRecordId;
    private String canonicalEntityId;
    private String linkState;
    private String linkReason;
    private String sourceEventId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant linkedAt;
    private Instant updatedAt;
}
