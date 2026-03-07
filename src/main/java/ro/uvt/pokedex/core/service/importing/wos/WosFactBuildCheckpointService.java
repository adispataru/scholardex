package ro.uvt.pokedex.core.service.importing.wos;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint;
import ro.uvt.pokedex.core.repository.reporting.WosFactBuildCheckpointRepository;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WosFactBuildCheckpointService {
    public static final String WOS_FACT_BUILD_PIPELINE_KEY = "wos-canonical-fact-build";

    private final WosFactBuildCheckpointRepository checkpointRepository;

    public Optional<WosFactBuildCheckpoint> readCheckpoint() {
        return checkpointRepository.findById(WOS_FACT_BUILD_PIPELINE_KEY);
    }

    public WosFactBuildCheckpoint upsertCheckpoint(
            int lastCompletedBatch,
            int chunkSize,
            String lastProcessedRecordKey,
            String runId,
            String sourceVersion
    ) {
        WosFactBuildCheckpoint checkpoint = checkpointRepository
                .findById(WOS_FACT_BUILD_PIPELINE_KEY)
                .orElseGet(WosFactBuildCheckpoint::new);
        checkpoint.setPipelineKey(WOS_FACT_BUILD_PIPELINE_KEY);
        checkpoint.setLastCompletedBatch(lastCompletedBatch);
        checkpoint.setChunkSize(chunkSize);
        checkpoint.setLastProcessedRecordKey(lastProcessedRecordKey);
        checkpoint.setRunId(runId);
        checkpoint.setSourceVersion(sourceVersion);
        checkpoint.setUpdatedAt(Instant.now());
        return checkpointRepository.save(checkpoint);
    }

    public void resetCheckpoint() {
        checkpointRepository.deleteById(WOS_FACT_BUILD_PIPELINE_KEY);
    }
}
