package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "wos.fact_build_checkpoints")
public class WosFactBuildCheckpoint {
    @Id
    private String pipelineKey;
    private Integer lastCompletedBatch;
    private Integer chunkSize;
    private String lastProcessedRecordKey;
    private Instant updatedAt;
    private String runId;
    private String sourceVersion;
}
