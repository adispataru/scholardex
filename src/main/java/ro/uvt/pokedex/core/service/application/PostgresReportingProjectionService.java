package ro.uvt.pokedex.core.service.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface PostgresReportingProjectionService {

    ProjectionRunSummary runFullRebuild();

    ProjectionRunSummary runIncrementalSync();

    ProjectionStatusSnapshot latestRunStatus();

    void resetProjectionState();

    record ProjectionRunSummary(
            String runId,
            String mode,
            String status,
            Instant startedAt,
            Instant completedAt,
            List<SliceRunSummary> slices,
            String errorSample
    ) {
    }

    record SliceRunSummary(
            String sliceName,
            String status,
            String sourceFingerprint,
            long insertedRows,
            String note,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    record ProjectionStatusSnapshot(
            ProjectionRunSummary latestRun,
            Map<String, CheckpointSummary> checkpoints
    ) {
    }

    record CheckpointSummary(
            String sliceName,
            String sourceFingerprint,
            String lastRunId,
            Instant lastSuccessAt,
            String lastMode
    ) {
    }
}
