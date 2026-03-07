package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class RankingMaintenanceFacade {
    private final WosProjectionBuilderService wosProjectionBuilderService;
    private final WosIndexMaintenanceService wosIndexMaintenanceService;
    private final WosBigBangMigrationService wosBigBangMigrationService;
    private final AtomicReference<WosEnrichmentRunSummaryDto> lastWosEnrichmentSummary =
            new AtomicReference<>(WosEnrichmentRunSummaryDto.notRun());

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

    public WosBigBangMigrationService.MigrationStepResult enrichWosCategoryRankings() {
        return executeAndTrackWosCategoryEnrichment().step();
    }

    public WosEnrichmentRunSummaryDto runWosCategoryRankingEnrichmentWithSummary() {
        return executeAndTrackWosCategoryEnrichment().summary();
    }

    public WosEnrichmentRunSummaryDto latestWosCategoryRankingEnrichmentSummary() {
        return lastWosEnrichmentSummary.get();
    }

    public WosBigBangMigrationService.CanonicalResetResult resetWosCanonicalState() {
        return wosBigBangMigrationService.resetCanonicalState();
    }

    private synchronized EnrichmentExecution executeAndTrackWosCategoryEnrichment() {
        Instant startedAt = Instant.now();
        WosBigBangMigrationService.MigrationStepResult step = wosBigBangMigrationService.runEnrichCategoryRankingsStep();
        Instant completedAt = Instant.now();
        WosEnrichmentRunSummaryDto summary = WosEnrichmentRunSummaryDto.fromStep(step, startedAt, completedAt);
        lastWosEnrichmentSummary.set(summary);
        return new EnrichmentExecution(step, summary);
    }

    private record EnrichmentExecution(
            WosBigBangMigrationService.MigrationStepResult step,
            WosEnrichmentRunSummaryDto summary
    ) {
    }
}
