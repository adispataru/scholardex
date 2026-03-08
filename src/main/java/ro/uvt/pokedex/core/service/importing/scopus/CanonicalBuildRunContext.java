package ro.uvt.pokedex.core.service.importing.scopus;

import java.time.Instant;

public record CanonicalBuildRunContext(
        String runId,
        int chunkSize,
        int totalRecords,
        int totalBatches,
        int startBatch,
        boolean resumedFromCheckpoint,
        int checkpointLastCompletedBatch,
        String sourceVersion,
        Instant startedAt
) {
}
