package ro.uvt.pokedex.core.service.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface PostgresMaterializedViewRefreshService {

    MaterializedViewRefreshRunSummary refreshForSlices(Set<String> changedSlices, String projectionRunId);

    MaterializedViewRefreshRunSummary refreshAllManual();

    MaterializedViewRefreshRunSummary refreshManualForSlices(Set<String> slices);

    MaterializedViewRefreshStatusSnapshot latestStatus();

    record MaterializedViewRefreshRunSummary(
            String runId,
            String triggerMode,
            String triggerReference,
            String status,
            Instant startedAt,
            Instant completedAt,
            List<MaterializedViewRefreshItemSummary> views,
            String errorSample
    ) {
    }

    record MaterializedViewRefreshItemSummary(
            String viewName,
            String status,
            String note,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    record MaterializedViewRefreshStatusSnapshot(
            MaterializedViewRefreshRunSummary latestRun
    ) {
    }
}
