package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalBuildOptionsTest {

    @Test
    void defaultsReturnsCheckpointEnabledDefaults() {
        CanonicalBuildOptions options = CanonicalBuildOptions.defaults();

        assertNull(options.chunkSizeOverride());
        assertNull(options.startBatchOverride());
        assertTrue(options.useCheckpoint());
        assertNull(options.sourceBatchIdFilter());
        assertNull(options.sourceVersionOverride());
        assertFalse(options.reconcileSourceLinks());
        assertFalse(options.reconcileEdges());
    }

    @Test
    void noCheckpointDisablesCheckpointByDefault() {
        CanonicalBuildOptions options = CanonicalBuildOptions.noCheckpoint();

        assertNull(options.chunkSizeOverride());
        assertNull(options.startBatchOverride());
        assertFalse(options.useCheckpoint());
        assertNull(options.sourceBatchIdFilter());
        assertNull(options.sourceVersionOverride());
        assertFalse(options.reconcileSourceLinks());
        assertFalse(options.reconcileEdges());
    }
}

