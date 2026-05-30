package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.importing.ScholardexProjectionDirtyMarker;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.repository.importing.ScholardexProjectionDirtyMarkerRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScholardexProjectionDirtyServiceTest {

    private final ScholardexProjectionDirtyMarkerRepository repository = mock(ScholardexProjectionDirtyMarkerRepository.class);
    private final ScholardexProjectionBuilderService projectionBuilderService = mock(ScholardexProjectionBuilderService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-30T10:15:30Z"), ZoneOffset.UTC);
    private final ScholardexProjectionDirtyService service =
            new ScholardexProjectionDirtyService(repository, projectionBuilderService, clock);

    @Test
    void markDirtyCreatesMarkerForCanonicalProjection() {
        when(repository.findFirstByEntityTypeAndCanonicalEntityIdAndStatusInOrderByMarkedAtDesc(
                ScholardexEntityType.PUBLICATION,
                "spub_1",
                List.of(ScholardexProjectionDirtyService.STATUS_DIRTY, ScholardexProjectionDirtyService.STATUS_REBUILD_FAILED)
        )).thenReturn(Optional.empty());

        service.markDirty(
                ScholardexEntityType.PUBLICATION,
                " spub_1 ",
                " batch-1 ",
                " event-1 ",
                " corr-1 ",
                "auto-relinked-identity-link"
        );

        verify(repository).save(argThat(marker ->
                marker.getEntityType() == ScholardexEntityType.PUBLICATION
                        && "spub_1".equals(marker.getCanonicalEntityId())
                        && "batch-1".equals(marker.getSourceBatchId())
                        && "event-1".equals(marker.getSourceEventId())
                        && "corr-1".equals(marker.getSourceCorrelationId())
                        && "auto-relinked-identity-link".equals(marker.getReason())
                        && ScholardexProjectionDirtyService.STATUS_DIRTY.equals(marker.getStatus())
                        && Instant.parse("2026-05-30T10:15:30Z").equals(marker.getMarkedAt())
        ));
    }

    @Test
    void markDirtyReopensFailedMarkerForRetry() {
        ScholardexProjectionDirtyMarker marker = new ScholardexProjectionDirtyMarker();
        marker.setEntityType(ScholardexEntityType.AUTHOR);
        marker.setCanonicalEntityId("sauth_1");
        marker.setStatus(ScholardexProjectionDirtyService.STATUS_REBUILD_FAILED);
        marker.setLastError("old failure");
        marker.setRebuiltAt(Instant.parse("2026-05-29T10:00:00Z"));
        when(repository.findFirstByEntityTypeAndCanonicalEntityIdAndStatusInOrderByMarkedAtDesc(
                ScholardexEntityType.AUTHOR,
                "sauth_1",
                List.of(ScholardexProjectionDirtyService.STATUS_DIRTY, ScholardexProjectionDirtyService.STATUS_REBUILD_FAILED)
        )).thenReturn(Optional.of(marker));

        service.markDirty(ScholardexEntityType.AUTHOR, "sauth_1", "batch-2", "event-2", "corr-2", "auto-relinked-identity-link");

        verify(repository).save(argThat(saved ->
                saved == marker
                        && ScholardexProjectionDirtyService.STATUS_DIRTY.equals(saved.getStatus())
                        && saved.getLastError() == null
                        && saved.getRebuiltAt() == null
        ));
    }

    @Test
    void markDirtyRejectsMissingIdentity() {
        assertTrue(service.markDirty(null, "sauth_1", "batch", "event", "corr", "reason").isEmpty());
        assertTrue(service.markDirty(ScholardexEntityType.AUTHOR, " ", "batch", "event", "corr", "reason").isEmpty());

        verify(repository, never()).save(any());
    }

    @Test
    void rebuildDirtyProjectionsRunsBatchRebuildsAndMarksSuccessfulMarkersRebuilt() {
        ScholardexProjectionDirtyMarker first = marker("m1", ScholardexEntityType.AUTHOR, "sauth_1", "batch-1");
        ScholardexProjectionDirtyMarker second = marker("m2", ScholardexEntityType.PUBLICATION, "spub_1", "batch-2");
        when(repository.findByStatusInOrderByMarkedAtAsc(List.of(
                ScholardexProjectionDirtyService.STATUS_DIRTY,
                ScholardexProjectionDirtyService.STATUS_REBUILD_FAILED
        ))).thenReturn(List.of(first, second));
        when(projectionBuilderService.rebuildViewsForBatch("batch-1")).thenReturn(new ImportProcessingResult(20));
        when(projectionBuilderService.rebuildViewsForBatch("batch-2")).thenReturn(new ImportProcessingResult(20));

        ScholardexProjectionDirtyService.ProjectionRebuildResult result = service.rebuildDirtyProjections();

        assertEquals(2L, result.requestedMarkers());
        assertEquals(2L, result.rebuiltMarkers());
        assertEquals(0L, result.failedMarkers());
        assertEquals(2, result.batchRebuildsAttempted());
        verify(projectionBuilderService).rebuildViewsForBatch("batch-1");
        verify(projectionBuilderService).rebuildViewsForBatch("batch-2");
        verify(repository).saveAll(argThat(markers -> allStatus(markers, ScholardexProjectionDirtyService.STATUS_REBUILT)));
    }

    @Test
    void rebuildDirtyProjectionsMarksFailedBatchRetryableWithoutThrowing() {
        ScholardexProjectionDirtyMarker marker = marker("m1", ScholardexEntityType.AUTHOR, "sauth_1", "batch-1");
        ImportProcessingResult failure = new ImportProcessingResult(20);
        failure.markError("scopus-projection-batch-rebuild-error=boom");
        when(repository.findByStatusInOrderByMarkedAtAsc(List.of(
                ScholardexProjectionDirtyService.STATUS_DIRTY,
                ScholardexProjectionDirtyService.STATUS_REBUILD_FAILED
        ))).thenReturn(List.of(marker));
        when(projectionBuilderService.rebuildViewsForBatch("batch-1")).thenReturn(failure);

        ScholardexProjectionDirtyService.ProjectionRebuildResult result = service.rebuildDirtyProjections();

        assertEquals(1L, result.failedMarkers());
        assertEquals(1, result.batchRebuildsFailed());
        verify(repository).saveAll(argThat(markers -> allStatus(markers, ScholardexProjectionDirtyService.STATUS_REBUILD_FAILED)));
    }

    @Test
    void rebuildDirtyProjectionWithMissingBatchFallsBackToFullRebuild() {
        ScholardexProjectionDirtyMarker marker = marker("m1", ScholardexEntityType.AUTHOR, "sauth_1", null);
        when(repository.findByStatusInOrderByMarkedAtAsc(List.of(
                ScholardexProjectionDirtyService.STATUS_DIRTY,
                ScholardexProjectionDirtyService.STATUS_REBUILD_FAILED
        ))).thenReturn(List.of(marker));
        when(projectionBuilderService.rebuildViews()).thenReturn(new ImportProcessingResult(20));

        ScholardexProjectionDirtyService.ProjectionRebuildResult result = service.rebuildDirtyProjections();

        assertEquals(1L, result.rebuiltMarkers());
        assertEquals(1, result.fullRebuildsAttempted());
        verify(projectionBuilderService).rebuildViews();
        verify(projectionBuilderService, never()).rebuildViewsForBatch(any());
    }

    private ScholardexProjectionDirtyMarker marker(String id, ScholardexEntityType entityType, String canonicalId, String batchId) {
        ScholardexProjectionDirtyMarker marker = new ScholardexProjectionDirtyMarker();
        marker.setId(id);
        marker.setEntityType(entityType);
        marker.setCanonicalEntityId(canonicalId);
        marker.setSourceBatchId(batchId);
        marker.setStatus(ScholardexProjectionDirtyService.STATUS_DIRTY);
        marker.setMarkedAt(Instant.parse("2026-05-30T09:00:00Z"));
        return marker;
    }

    private boolean allStatus(Iterable<ScholardexProjectionDirtyMarker> markers, String status) {
        int count = 0;
        for (ScholardexProjectionDirtyMarker marker : markers) {
            count++;
            if (!status.equals(marker.getStatus())) {
                return false;
            }
        }
        return count > 0;
    }
}
