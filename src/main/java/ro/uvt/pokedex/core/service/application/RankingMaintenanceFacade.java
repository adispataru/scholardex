package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

@Service
@RequiredArgsConstructor
public class RankingMaintenanceFacade {
    private final WosProjectionBuilderService wosProjectionBuilderService;
    private final WosIndexMaintenanceService wosIndexMaintenanceService;
    private final WosBigBangMigrationService wosBigBangMigrationService;

    public void computePositionsForKnownQuarters() {
        throw new IllegalStateException("Legacy WoS maintenance operation is disabled. Use canonical WoS ingestion/facts/projections pipeline.");
    }

    public void computeQuartersAndRankingsWhereMissing() {
        throw new IllegalStateException("Legacy WoS maintenance operation is disabled. Use canonical WoS ingestion/facts/projections pipeline.");
    }

    public void mergeDuplicateRankings() {
        throw new IllegalStateException("Legacy WoS maintenance operation is disabled. Use canonical WoS ingestion/facts/projections pipeline.");
    }

    public ImportProcessingResult rebuildWosProjections() {
        return wosProjectionBuilderService.rebuildWosProjections();
    }

    public WosIndexMaintenanceService.WosIndexEnsureResult ensureWosIndexes() {
        return wosIndexMaintenanceService.ensureWosIndexes();
    }

    public WosBigBangMigrationService.WosBigBangMigrationResult runWosBigBangMigration(boolean dryRun, String sourceVersionOverride) {
        return runWosBigBangMigration(dryRun, sourceVersionOverride, null, true);
    }

    public WosBigBangMigrationService.WosBigBangMigrationResult runWosBigBangMigration(
            boolean dryRun,
            String sourceVersionOverride,
            Integer startBatchOverride,
            boolean useCheckpoint
    ) {
        return wosBigBangMigrationService.run(dryRun, sourceVersionOverride, startBatchOverride, useCheckpoint);
    }

    public void resetWosFactBuildCheckpoint() {
        wosBigBangMigrationService.resetFactBuildCheckpoint();
    }

    public WosBigBangMigrationService.MigrationStepResult ingestWosEvents(String sourceVersionOverride) {
        return wosBigBangMigrationService.runIngestStep(sourceVersionOverride);
    }

    public WosBigBangMigrationService.MigrationStepResult buildWosFactsFromEvents(
            Integer startBatchOverride,
            String sourceVersionOverride,
            boolean useCheckpoint
    ) {
        return wosBigBangMigrationService.runBuildFactsStep(startBatchOverride, sourceVersionOverride, useCheckpoint);
    }

    public WosBigBangMigrationService.CanonicalResetResult resetWosCanonicalState() {
        return wosBigBangMigrationService.resetCanonicalState();
    }
}
