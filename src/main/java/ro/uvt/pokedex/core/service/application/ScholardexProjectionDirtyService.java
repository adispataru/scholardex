package ro.uvt.pokedex.core.service.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.importing.ScholardexProjectionDirtyMarker;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.repository.importing.ScholardexProjectionDirtyMarkerRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ScholardexProjectionDirtyService {

    public static final String STATUS_DIRTY = "DIRTY";
    public static final String STATUS_REBUILD_FAILED = "REBUILD_FAILED";
    public static final String STATUS_REBUILT = "REBUILT";

    private static final List<String> OUTSTANDING_STATUSES = List.of(STATUS_DIRTY, STATUS_REBUILD_FAILED);

    private final ScholardexProjectionDirtyMarkerRepository repository;
    private final ScholardexProjectionBuilderService projectionBuilderService;
    private final Clock clock;

    @Autowired
    public ScholardexProjectionDirtyService(
            ScholardexProjectionDirtyMarkerRepository repository,
            ScholardexProjectionBuilderService projectionBuilderService
    ) {
        this(repository, projectionBuilderService, Clock.systemUTC());
    }

    ScholardexProjectionDirtyService(
            ScholardexProjectionDirtyMarkerRepository repository,
            ScholardexProjectionBuilderService projectionBuilderService,
            Clock clock
    ) {
        this.repository = repository;
        this.projectionBuilderService = projectionBuilderService;
        this.clock = clock;
    }

    public Optional<ScholardexProjectionDirtyMarker> markDirty(
            ScholardexEntityType entityType,
            String canonicalEntityId,
            String sourceBatchId,
            String sourceEventId,
            String sourceCorrelationId,
            String reason
    ) {
        String normalizedCanonicalId = normalize(canonicalEntityId);
        if (entityType == null || normalizedCanonicalId == null) {
            return Optional.empty();
        }

        Instant now = Instant.now(clock);
        ScholardexProjectionDirtyMarker marker = repository
                .findFirstByEntityTypeAndCanonicalEntityIdAndStatusInOrderByMarkedAtDesc(
                        entityType,
                        normalizedCanonicalId,
                        OUTSTANDING_STATUSES
                )
                .orElseGet(ScholardexProjectionDirtyMarker::new);

        marker.setEntityType(entityType);
        marker.setCanonicalEntityId(normalizedCanonicalId);
        marker.setSourceBatchId(normalize(sourceBatchId));
        marker.setSourceEventId(normalize(sourceEventId));
        marker.setSourceCorrelationId(normalize(sourceCorrelationId));
        marker.setReason(normalize(reason));
        marker.setStatus(STATUS_DIRTY);
        marker.setMarkedAt(now);
        marker.setRebuiltAt(null);
        marker.setLastError(null);
        ScholardexProjectionDirtyMarker saved = repository.save(marker);
        return Optional.of(saved == null ? marker : saved);
    }

    public ProjectionDirtySummary summarizeDirtyProjections() {
        return new ProjectionDirtySummary(
                repository.countByStatus(STATUS_DIRTY),
                repository.countByStatus(STATUS_REBUILD_FAILED)
        );
    }

    public ProjectionRebuildResult rebuildDirtyProjections() {
        List<ScholardexProjectionDirtyMarker> markers = repository.findByStatusInOrderByMarkedAtAsc(OUTSTANDING_STATUSES);
        if (markers.isEmpty()) {
            return new ProjectionRebuildResult(0, 0, 0, 0, 0, 0, List.of());
        }

        if (requiresFullRebuild(markers)) {
            return rebuildAllDirtyWithFullRebuild(markers);
        }
        return rebuildDirtyByBatch(markers);
    }

    private ProjectionRebuildResult rebuildAllDirtyWithFullRebuild(List<ScholardexProjectionDirtyMarker> markers) {
        RebuildOutcome outcome = runFullRebuild();
        applyOutcome(markers, outcome);
        repository.saveAll(markers);
        long failed = outcome.failed() ? markers.size() : 0L;
        long rebuilt = outcome.failed() ? 0L : markers.size();
        return new ProjectionRebuildResult(
                markers.size(),
                rebuilt,
                failed,
                0,
                0,
                1,
                outcome.error() == null ? List.of() : List.of(outcome.error())
        );
    }

    private ProjectionRebuildResult rebuildDirtyByBatch(List<ScholardexProjectionDirtyMarker> markers) {
        Map<String, List<ScholardexProjectionDirtyMarker>> byBatch = new LinkedHashMap<>();
        for (ScholardexProjectionDirtyMarker marker : markers) {
            byBatch.computeIfAbsent(marker.getSourceBatchId(), ignored -> new ArrayList<>()).add(marker);
        }

        long rebuilt = 0L;
        long failed = 0L;
        int failedBatches = 0;
        List<String> errors = new ArrayList<>();
        List<ScholardexProjectionDirtyMarker> changed = new ArrayList<>();
        for (Map.Entry<String, List<ScholardexProjectionDirtyMarker>> entry : byBatch.entrySet()) {
            RebuildOutcome outcome = runBatchRebuild(entry.getKey());
            applyOutcome(entry.getValue(), outcome);
            changed.addAll(entry.getValue());
            if (outcome.failed()) {
                failed += entry.getValue().size();
                failedBatches++;
                if (outcome.error() != null) {
                    errors.add(outcome.error());
                }
            } else {
                rebuilt += entry.getValue().size();
            }
        }
        repository.saveAll(changed);

        return new ProjectionRebuildResult(
                markers.size(),
                rebuilt,
                failed,
                byBatch.size(),
                failedBatches,
                0,
                List.copyOf(errors)
        );
    }

    private RebuildOutcome runBatchRebuild(String sourceBatchId) {
        try {
            ImportProcessingResult result = projectionBuilderService.rebuildViewsForBatch(sourceBatchId);
            return toOutcome("batch " + sourceBatchId, result);
        } catch (RuntimeException ex) {
            return new RebuildOutcome(true, "batch " + sourceBatchId + " failed: " + ex.getMessage());
        }
    }

    private RebuildOutcome runFullRebuild() {
        try {
            ImportProcessingResult result = projectionBuilderService.rebuildViews();
            return toOutcome("full rebuild", result);
        } catch (RuntimeException ex) {
            return new RebuildOutcome(true, "full rebuild failed: " + ex.getMessage());
        }
    }

    private RebuildOutcome toOutcome(String label, ImportProcessingResult result) {
        if (result == null || result.getErrorCount() == 0) {
            return new RebuildOutcome(false, null);
        }
        String detail = result.getErrorsSample().isEmpty()
                ? "errorCount=" + result.getErrorCount()
                : String.join("; ", result.getErrorsSample());
        return new RebuildOutcome(true, label + " failed: " + detail);
    }

    private void applyOutcome(List<ScholardexProjectionDirtyMarker> markers, RebuildOutcome outcome) {
        Instant now = Instant.now(clock);
        for (ScholardexProjectionDirtyMarker marker : markers) {
            marker.setLastAttemptedAt(now);
            marker.setRebuildAttempts(marker.getRebuildAttempts() + 1);
            if (outcome.failed()) {
                marker.setStatus(STATUS_REBUILD_FAILED);
                marker.setLastError(outcome.error());
            } else {
                marker.setStatus(STATUS_REBUILT);
                marker.setRebuiltAt(now);
                marker.setLastError(null);
            }
        }
    }

    private boolean requiresFullRebuild(List<ScholardexProjectionDirtyMarker> markers) {
        for (ScholardexProjectionDirtyMarker marker : markers) {
            if (normalize(marker.getSourceBatchId()) == null) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ProjectionDirtySummary(long dirty, long rebuildFailed) {
        public long totalOutstanding() {
            return dirty + rebuildFailed;
        }
    }

    public record ProjectionRebuildResult(
            long requestedMarkers,
            long rebuiltMarkers,
            long failedMarkers,
            int batchRebuildsAttempted,
            int batchRebuildsFailed,
            int fullRebuildsAttempted,
            List<String> errors
    ) {
        public boolean hasFailures() {
            return failedMarkers > 0 || (errors != null && !errors.isEmpty());
        }
    }

    private record RebuildOutcome(boolean failed, String error) {
    }
}
