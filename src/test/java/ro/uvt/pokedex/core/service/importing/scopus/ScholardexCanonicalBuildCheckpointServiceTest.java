package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCanonicalBuildCheckpointRepository;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexCanonicalBuildCheckpointServiceTest {

    @Mock
    private ScholardexCanonicalBuildCheckpointRepository checkpointRepository;

    private ScholardexCanonicalBuildCheckpointService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexCanonicalBuildCheckpointService(checkpointRepository);
    }

    @Test
    void readCheckpointHandlesBlankAndDelegatesLookup() {
        assertTrue(service.readCheckpoint(" ").isEmpty());
        verify(checkpointRepository, never()).findById(any());

        ScholardexCanonicalBuildCheckpoint checkpoint = new ScholardexCanonicalBuildCheckpoint();
        checkpoint.setPipelineKey("p");
        when(checkpointRepository.findById("p")).thenReturn(Optional.of(checkpoint));

        Optional<ScholardexCanonicalBuildCheckpoint> result = service.readCheckpoint("p");
        assertTrue(result.isPresent());
        assertEquals("p", result.get().getPipelineKey());
    }

    @Test
    void upsertCheckpointCreatesAndUpdatesFields() {
        when(checkpointRepository.findById("pipeline")).thenReturn(Optional.empty());
        when(checkpointRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ScholardexCanonicalBuildCheckpoint saved = service.upsertCheckpoint(
                "pipeline", 7, 250, "record-1", "run-1", "v1"
        );

        assertEquals("pipeline", saved.getPipelineKey());
        assertEquals(7, saved.getLastCompletedBatch());
        assertEquals(250, saved.getChunkSize());
        assertEquals("record-1", saved.getLastProcessedRecordKey());
        assertEquals("run-1", saved.getRunId());
        assertEquals("v1", saved.getSourceVersion());
        assertTrue(saved.getUpdatedAt() != null);
    }

    @Test
    void resetCheckpointAndResetAllRespectBlankAndKnownKeys() {
        service.resetCheckpoint(" ");
        verify(checkpointRepository, never()).deleteById(any());

        service.resetCheckpoint("custom");
        verify(checkpointRepository).deleteById("custom");

        service.resetAll();
        verify(checkpointRepository).deleteById(ScholardexCanonicalBuildCheckpointService.PUBLICATION_PIPELINE_KEY);
        verify(checkpointRepository).deleteById(ScholardexCanonicalBuildCheckpointService.AUTHOR_PIPELINE_KEY);
        verify(checkpointRepository).deleteById(ScholardexCanonicalBuildCheckpointService.AFFILIATION_PIPELINE_KEY);
        verify(checkpointRepository).deleteById(ScholardexCanonicalBuildCheckpointService.CITATION_PIPELINE_KEY);
    }
}

