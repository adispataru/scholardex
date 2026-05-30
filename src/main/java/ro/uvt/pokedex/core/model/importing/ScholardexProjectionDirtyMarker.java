package ro.uvt.pokedex.core.model.importing;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;

import java.time.Instant;

@Data
@Document(collection = "scholardex.projection_dirty_markers")
@CompoundIndex(
        name = "idx_scholardex_projection_dirty_status_key",
        def = "{'status': 1, 'entityType': 1, 'canonicalEntityId': 1, 'sourceBatchId': 1}"
)
public class ScholardexProjectionDirtyMarker {
    @Id
    private String id;
    private ScholardexEntityType entityType;
    private String canonicalEntityId;
    private String sourceBatchId;
    private String sourceEventId;
    private String sourceCorrelationId;
    private String reason;
    private String status;
    private Instant markedAt;
    private Instant lastAttemptedAt;
    private Instant rebuiltAt;
    private String lastError;
    private int rebuildAttempts;
}
