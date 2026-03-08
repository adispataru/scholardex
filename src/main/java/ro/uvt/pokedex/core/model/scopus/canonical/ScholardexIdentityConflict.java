package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.identity_conflicts")
@CompoundIndex(
        name = "uniq_scholardex_open_identity_conflict",
        def = "{'entityType': 1, 'incomingSource': 1, 'incomingSourceRecordId': 1, 'reasonCode': 1, 'status': 1}",
        unique = true,
        sparse = true
)
public class ScholardexIdentityConflict {
    @Id
    private String id;
    private ScholardexEntityType entityType;
    private String incomingSource;
    private String incomingSourceRecordId;
    private List<String> candidateCanonicalIds = new ArrayList<>();
    private String reasonCode;
    private String status;
    private String sourceEventId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant detectedAt;
    private Instant resolvedAt;
    private String resolvedBy;
}
