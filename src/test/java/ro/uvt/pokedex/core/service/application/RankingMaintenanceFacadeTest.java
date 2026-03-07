package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RankingMaintenanceFacadeTest {

    @Mock
    private WosProjectionBuilderService wosProjectionBuilderService;
    @Mock
    private WosIndexMaintenanceService wosIndexMaintenanceService;
    @Mock
    private WosBigBangMigrationService wosBigBangMigrationService;

    @InjectMocks
    private RankingMaintenanceFacade facade;

    @Test
    void legacyComputePositionsOperationIsDisabled() {
        assertThrows(IllegalStateException.class, facade::computePositionsForKnownQuarters);
        verifyNoInteractions(wosProjectionBuilderService, wosIndexMaintenanceService, wosBigBangMigrationService);
    }

    @Test
    void legacyComputeQuartersOperationIsDisabled() {
        assertThrows(IllegalStateException.class, facade::computeQuartersAndRankingsWhereMissing);
        verifyNoInteractions(wosProjectionBuilderService, wosIndexMaintenanceService, wosBigBangMigrationService);
    }

    @Test
    void legacyMergeOperationIsDisabled() {
        assertThrows(IllegalStateException.class, facade::mergeDuplicateRankings);
        verifyNoInteractions(wosProjectionBuilderService, wosIndexMaintenanceService, wosBigBangMigrationService);
    }

    @Test
    void rebuildWosProjectionsDelegatesToBuilder() {
        ImportProcessingResult expected = new ImportProcessingResult(5);
        when(wosProjectionBuilderService.rebuildWosProjections()).thenReturn(expected);

        ImportProcessingResult result = facade.rebuildWosProjections();

        verify(wosProjectionBuilderService).rebuildWosProjections();
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
    }

    @Test
    void ensureWosIndexesDelegatesToIndexMaintenanceService() {
        WosIndexMaintenanceService.WosIndexEnsureResult expected =
                new WosIndexMaintenanceService.WosIndexEnsureResult(List.of("a"), List.of("b"), List.of(), List.of());
        when(wosIndexMaintenanceService.ensureWosIndexes()).thenReturn(expected);

        WosIndexMaintenanceService.WosIndexEnsureResult result = facade.ensureWosIndexes();

        verify(wosIndexMaintenanceService).ensureWosIndexes();
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
    }

    @Test
    void runWosBigBangMigrationDelegatesToMigrationService() {
        WosBigBangMigrationService.WosBigBangMigrationResult expected =
                new WosBigBangMigrationService.WosBigBangMigrationResult(
                        true,
                        "data/loaded",
                        "v2026",
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        new WosBigBangMigrationService.MigrationStepResult("ingest", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null),
                        new WosBigBangMigrationService.MigrationStepResult("facts", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null),
                        new WosBigBangMigrationService.MigrationStepResult("proj", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null),
                        new WosBigBangMigrationService.VerificationSummary(
                                0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, List.of(),
                                true, true,
                                false, 0, 0, List.of(), List.of("eligibility")
                        )
                );
        when(wosBigBangMigrationService.run(true, "v2026", null, true)).thenReturn(expected);

        WosBigBangMigrationService.WosBigBangMigrationResult result = facade.runWosBigBangMigration(true, "v2026");

        verify(wosBigBangMigrationService).run(true, "v2026", null, true);
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
    }

    @Test
    void resetWosFactBuildCheckpointDelegatesToMigrationService() {
        facade.resetWosFactBuildCheckpoint();

        verify(wosBigBangMigrationService).resetFactBuildCheckpoint();
    }

    @Test
    void buildWosFactsFromEventsDelegatesToMigrationService() {
        WosBigBangMigrationService.MigrationStepResult expected =
                new WosBigBangMigrationService.MigrationStepResult(
                        "build-facts",
                        true,
                        10,
                        6,
                        2,
                        2,
                        0,
                        null,
                        List.of(),
                        200,
                        204,
                        5,
                        true,
                        199
                );
        when(wosBigBangMigrationService.runBuildFactsStep(200, "v2026", true)).thenReturn(expected);

        WosBigBangMigrationService.MigrationStepResult result =
                facade.buildWosFactsFromEvents(200, "v2026", true);

        verify(wosBigBangMigrationService).runBuildFactsStep(200, "v2026", true);
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
    }

    @Test
    void ingestWosEventsDelegatesToMigrationService() {
        WosBigBangMigrationService.MigrationStepResult expected =
                new WosBigBangMigrationService.MigrationStepResult(
                        "ingest",
                        true,
                        10,
                        8,
                        0,
                        2,
                        0,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null
                );
        when(wosBigBangMigrationService.runIngestStep("v2026")).thenReturn(expected);

        WosBigBangMigrationService.MigrationStepResult result = facade.ingestWosEvents("v2026");

        verify(wosBigBangMigrationService).runIngestStep("v2026");
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
    }

    @Test
    void resetWosCanonicalStateDelegatesToMigrationService() {
        WosBigBangMigrationService.CanonicalResetResult expected =
                new WosBigBangMigrationService.CanonicalResetResult(10, 7, 5, 5, 1, 2, 7, 7);
        when(wosBigBangMigrationService.resetCanonicalState()).thenReturn(expected);

        WosBigBangMigrationService.CanonicalResetResult result = facade.resetWosCanonicalState();

        verify(wosBigBangMigrationService).resetCanonicalState();
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
    }
}
