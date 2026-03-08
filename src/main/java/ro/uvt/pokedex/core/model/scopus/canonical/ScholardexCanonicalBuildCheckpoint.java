package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scholardex.canonical_build_checkpoints")
public class ScholardexCanonicalBuildCheckpoint {
    @Id
    private String pipelineKey;
    private Integer lastCompletedBatch;
    private Integer chunkSize;
    private String lastProcessedRecordKey;
    private Instant updatedAt;
    private String runId;
    private String sourceVersion;
}
