package ro.uvt.pokedex.core.model.importing;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scholardex.import_run_metrics")
@CompoundIndex(
        name = "idx_import_run_metric_key",
        def = "{'runId': 1, 'source': 1, 'entityType': 1, 'reason': 1}"
)
public class ImportRunMetric {
    @Id
    private String id;
    private String runId;
    private String source;
    private String entityType;
    private String reason;
    private long count;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
}
