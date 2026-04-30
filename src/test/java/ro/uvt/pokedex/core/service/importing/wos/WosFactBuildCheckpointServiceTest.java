package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint;
import ro.uvt.pokedex.core.repository.reporting.WosFactBuildCheckpointRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosFactBuildCheckpointServiceTest {

    @Mock
    private WosFactBuildCheckpointRepository checkpointRepository;

    @Test
    void readCheckpointDelegatesToRepository() {
        when(checkpointRepository.findById(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY))
                .thenReturn(Optional.empty());

        WosFactBuildCheckpointService service = new WosFactBuildCheckpointService(checkpointRepository);
        Optional<WosFactBuildCheckpoint> result = service.readCheckpoint();

        assertTrue(result.isEmpty());
        verify(checkpointRepository).findById(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY);
    }

    @Test
    void upsertCheckpointCreatesNewEntryWithAllFields() {
        when(checkpointRepository.findById(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY))
                .thenReturn(Optional.empty());
        when(checkpointRepository.save(any(WosFactBuildCheckpoint.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WosFactBuildCheckpointService service = new WosFactBuildCheckpointService(checkpointRepository);
        WosFactBuildCheckpoint result = service.upsertCheckpoint(5, 100, "record-key-123", "run-abc", "v2024");

        assertEquals(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY, result.getPipelineKey());
        assertEquals(5, result.getLastCompletedBatch());
        assertEquals(100, result.getChunkSize());
        assertEquals("record-key-123", result.getLastProcessedRecordKey());
        assertEquals("run-abc", result.getRunId());
        assertEquals("v2024", result.getSourceVersion());
        assertNotNull(result.getUpdatedAt());
    }

    @Test
    void upsertCheckpointUpdatesExistingEntry() {
        WosFactBuildCheckpoint existing = new WosFactBuildCheckpoint();
        existing.setPipelineKey(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY);
        existing.setLastCompletedBatch(2);
        existing.setChunkSize(50);
        existing.setRunId("run-old");
        when(checkpointRepository.findById(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY))
                .thenReturn(Optional.of(existing));
        when(checkpointRepository.save(any(WosFactBuildCheckpoint.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WosFactBuildCheckpointService service = new WosFactBuildCheckpointService(checkpointRepository);
        WosFactBuildCheckpoint result = service.upsertCheckpoint(10, 200, "new-key", "run-new", "v2025");

        assertEquals(10, result.getLastCompletedBatch());
        assertEquals(200, result.getChunkSize());
        assertEquals("new-key", result.getLastProcessedRecordKey());
        assertEquals("run-new", result.getRunId());
        assertEquals("v2025", result.getSourceVersion());
        assertNotNull(result.getUpdatedAt());

        ArgumentCaptor<WosFactBuildCheckpoint> captor = ArgumentCaptor.forClass(WosFactBuildCheckpoint.class);
        verify(checkpointRepository).save(captor.capture());
        assertEquals(existing, captor.getValue());
    }

    @Test
    void resetCheckpointCallsDeleteById() {
        WosFactBuildCheckpointService service = new WosFactBuildCheckpointService(checkpointRepository);
        service.resetCheckpoint();

        verify(checkpointRepository).deleteById(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY);
    }
}
