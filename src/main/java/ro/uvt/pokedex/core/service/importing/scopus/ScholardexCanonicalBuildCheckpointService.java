package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCanonicalBuildCheckpointRepository;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScholardexCanonicalBuildCheckpointService {

    public static final String PUBLICATION_PIPELINE_KEY = "scholardex-publication-canonical-build";
    public static final String AUTHOR_PIPELINE_KEY = "scholardex-author-canonical-build";
    public static final String AFFILIATION_PIPELINE_KEY = "scholardex-affiliation-canonical-build";
    public static final String CITATION_PIPELINE_KEY = "scholardex-citation-canonical-build";

    private final ScholardexCanonicalBuildCheckpointRepository checkpointRepository;

    public Optional<ScholardexCanonicalBuildCheckpoint> readCheckpoint(String pipelineKey) {
        if (pipelineKey == null || pipelineKey.isBlank()) {
            return Optional.empty();
        }
        return checkpointRepository.findById(pipelineKey);
    }

    public ScholardexCanonicalBuildCheckpoint upsertCheckpoint(
            String pipelineKey,
            int lastCompletedBatch,
            int chunkSize,
            String lastProcessedRecordKey,
            String runId,
            String sourceVersion
    ) {
        ScholardexCanonicalBuildCheckpoint checkpoint = checkpointRepository
                .findById(pipelineKey)
                .orElseGet(ScholardexCanonicalBuildCheckpoint::new);
        checkpoint.setPipelineKey(pipelineKey);
        checkpoint.setLastCompletedBatch(lastCompletedBatch);
        checkpoint.setChunkSize(chunkSize);
        checkpoint.setLastProcessedRecordKey(lastProcessedRecordKey);
        checkpoint.setRunId(runId);
        checkpoint.setSourceVersion(sourceVersion);
        checkpoint.setUpdatedAt(Instant.now());
        return checkpointRepository.save(checkpoint);
    }

    public void resetCheckpoint(String pipelineKey) {
        if (pipelineKey == null || pipelineKey.isBlank()) {
            return;
        }
        checkpointRepository.deleteById(pipelineKey);
    }

    public void resetAll() {
        resetCheckpoint(PUBLICATION_PIPELINE_KEY);
        resetCheckpoint(AUTHOR_PIPELINE_KEY);
        resetCheckpoint(AFFILIATION_PIPELINE_KEY);
        resetCheckpoint(CITATION_PIPELINE_KEY);
    }
}
