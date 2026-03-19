package ro.uvt.pokedex.core.service.importing.model;

import java.util.List;

public record MigrationStepResult(
        String stepName,
        boolean executed,
        int processed,
        int imported,
        int updated,
        int skipped,
        int errors,
        String note,
        List<String> samples,
        Integer startBatch,
        Integer endBatch,
        Integer batchesProcessed,
        Integer totalBatches,
        Boolean resumedFromCheckpoint,
        Integer checkpointLastCompletedBatch
) {
    /** Simple factory — reads all batch/checkpoint fields from result. */
    public static MigrationStepResult executed(String stepName, ImportProcessingResult result) {
        return new MigrationStepResult(
                stepName, true,
                result.getProcessedCount(), result.getImportedCount(),
                result.getUpdatedCount(), result.getSkippedCount(), result.getErrorCount(),
                null, result.getErrorsSample(),
                result.getStartBatch(), result.getEndBatch(), result.getBatchesProcessed(),
                result.getTotalBatches(),
                result.getResumedFromCheckpoint(), result.getCheckpointLastCompletedBatch()
        );
    }

    /** Explicit-params factory — used when batch info comes from an external source (e.g. FactBuildRunResult). */
    public static MigrationStepResult executed(
            String stepName, ImportProcessingResult result,
            Integer startBatch, Integer endBatch, Integer batchesProcessed,
            Boolean resumedFromCheckpoint, Integer checkpointLastCompletedBatch
    ) {
        return new MigrationStepResult(
                stepName, true,
                result.getProcessedCount(), result.getImportedCount(),
                result.getUpdatedCount(), result.getSkippedCount(), result.getErrorCount(),
                null, result.getErrorsSample(),
                startBatch, endBatch, batchesProcessed,
                null,
                resumedFromCheckpoint, checkpointLastCompletedBatch
        );
    }

    /** Dry-run factory — for preview/validation passes that do not mutate state. */
    public static MigrationStepResult dryRun(
            String stepName, String note, List<String> samples,
            int processed, int errors,
            Integer startBatch, Integer endBatch, Integer batchesProcessed,
            Boolean resumedFromCheckpoint, Integer checkpointLastCompletedBatch
    ) {
        return new MigrationStepResult(
                stepName, false,
                processed, 0, 0, 0, errors,
                note, samples,
                startBatch, endBatch, batchesProcessed,
                null,
                resumedFromCheckpoint, checkpointLastCompletedBatch
        );
    }
}
