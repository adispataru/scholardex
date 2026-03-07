package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosScoringViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventParserOrchestrator;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunSummary;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WosBigBangMigrationService {

    private final WosImportEventIngestionService ingestionService;
    private final WosFactBuilderService factBuilderService;
    private final WosProjectionBuilderService projectionBuilderService;
    private final WosParityReconciliationService parityReconciliationService;
    private final WosImportEventParserOrchestrator parserOrchestrator;
    private final WosImportEventRepository importEventRepository;
    private final WosJournalIdentityRepository journalIdentityRepository;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosIdentityConflictRepository identityConflictRepository;
    private final WosFactConflictRepository factConflictRepository;
    private final WosRankingViewRepository rankingViewRepository;
    private final WosScoringViewRepository scoringViewRepository;

    @Value("${h14.wos.migration.data-dir:data/loaded}")
    private String migrationDataDirectory;

    public WosBigBangMigrationResult run(boolean dryRun, String sourceVersionOverride) {
        return run(dryRun, sourceVersionOverride, null, true);
    }

    public WosBigBangMigrationResult run(
            boolean dryRun,
            String sourceVersionOverride,
            Integer startBatchOverride,
            boolean useCheckpoint
    ) {
        Instant startedAt = Instant.now();
        MigrationStepResult ingestStep;
        MigrationStepResult factStep;
        MigrationStepResult enrichmentStep;
        MigrationStepResult projectionStep;
        String normalizedSourceVersion = normalizeSourceVersion(sourceVersionOverride);

        if (dryRun) {
            WosImportEventIngestionService.WosIngestionPreview preview =
                    ingestionService.previewDirectory(migrationDataDirectory, normalizedSourceVersion);
            var checkpoint = factBuilderService.readFactBuildCheckpoint().orElse(null);
            int checkpointLastBatch = checkpoint == null || checkpoint.getLastCompletedBatch() == null
                    ? -1
                    : checkpoint.getLastCompletedBatch();
            int effectiveStartBatch = startBatchOverride == null
                    ? Math.max(0, checkpointLastBatch + 1)
                    : Math.max(0, startBatchOverride);
            ingestStep = MigrationStepResult.dryRun(
                    "ingest",
                    "dry-run preview: files=" + preview.filesScanned()
                            + ", plannedEvents=" + preview.plannedEvents()
                            + ", errors=" + preview.errorCount(),
                    preview.samples(),
                    preview.plannedEvents(),
                    preview.errorCount(),
                    null,
                    null,
                    null,
                    null,
                    null
            );
            factStep = MigrationStepResult.dryRun(
                    "build-facts",
                    "dry-run: canonical fact build skipped"
                            + ", checkpointLastCompletedBatch=" + checkpointLastBatch
                            + ", effectiveStartBatch=" + effectiveStartBatch
                            + ", mode=" + (startBatchOverride == null ? "checkpoint" : "manual-override"),
                    List.of(),
                    0,
                    0,
                    effectiveStartBatch,
                    null,
                    0,
                    startBatchOverride == null && checkpointLastBatch >= 0,
                    checkpointLastBatch
            );
            enrichmentStep = MigrationStepResult.dryRun(
                    "enrich-category-rankings",
                    "dry-run: category ranking enrichment skipped",
                    List.of(),
                    0,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            projectionStep = MigrationStepResult.dryRun("build-projections", "dry-run: projection rebuild skipped", List.of(), 0, 0,
                    null, null, null, null, null);
        } else {
            ImportProcessingResult ingestionResult = ingestionService.ingestDirectory(
                    migrationDataDirectory,
                    normalizedSourceVersion
            );
            String runId = "wos-fact-build-" + startedAt.toEpochMilli();
            WosFactBuilderService.FactBuildRunResult factRun = factBuilderService.buildFactsFromImportEventsWithCheckpoint(
                    startBatchOverride,
                    useCheckpoint,
                    runId,
                    normalizedSourceVersion
            );
            ImportProcessingResult factResult = factRun.result();
            ImportProcessingResult enrichmentResult = factBuilderService.enrichMissingCategoryRankingFields();
            ImportProcessingResult projectionResult = projectionBuilderService.rebuildWosProjections();

            ingestStep = MigrationStepResult.executed("ingest", ingestionResult);
            factStep = MigrationStepResult.executed(
                    "build-facts",
                    factResult,
                    factRun.startBatch(),
                    factRun.endBatch(),
                    factRun.batchesProcessed(),
                    factRun.resumedFromCheckpoint(),
                    factRun.checkpointLastCompletedBatch()
            );
            enrichmentStep = MigrationStepResult.executed("enrich-category-rankings", enrichmentResult);
            projectionStep = MigrationStepResult.executed("build-projections", projectionResult);
        }

        WosParserRunResult parserRun = parserOrchestrator.parseAllEvents();
        WosParityReconciliationService.ParityReconciliationResult parityResult =
                dryRun ? parityReconciliationService.runEligibilityCheck() : parityReconciliationService.runFullParity();
        VerificationSummary verificationSummary = buildVerificationSummary(parserRun.summary(), dryRun, parityResult);

        return new WosBigBangMigrationResult(
                dryRun,
                migrationDataDirectory,
                normalizedSourceVersion,
                startedAt,
                Instant.now(),
                ingestStep,
                factStep,
                enrichmentStep,
                projectionStep,
                verificationSummary
        );
    }

    private String normalizeSourceVersion(String sourceVersionOverride) {
        if (sourceVersionOverride == null || sourceVersionOverride.isBlank()) {
            return null;
        }
        return sourceVersionOverride.trim();
    }

    private VerificationSummary buildVerificationSummary(
            WosParserRunSummary parserSummary,
            boolean dryRun,
            WosParityReconciliationService.ParityReconciliationResult parityResult
    ) {
        long importEvents = importEventRepository.count();
        long journalIdentities = journalIdentityRepository.count();
        long metricFacts = metricFactRepository.count();
        long categoryFacts = categoryFactRepository.count();
        long rankingRows = rankingViewRepository.count();
        long scoringRows = scoringViewRepository.count();

        boolean rankingAligned = rankingRows <= journalIdentities;
        boolean scoringAligned = scoringRows <= metricFacts;
        return new VerificationSummary(
                importEvents,
                journalIdentities,
                metricFacts,
                categoryFacts,
                rankingRows,
                scoringRows,
                parserSummary.getProcessedCount(),
                parserSummary.getParsedCount(),
                parserSummary.getSkippedCount(),
                parserSummary.getErrorCount(),
                parserSummary.getSamples(),
                rankingAligned,
                scoringAligned,
                !dryRun && parityResult.passed(),
                parityResult.mismatchCount(),
                parityResult.allowlistedMismatchCount(),
                parityResult.mismatches(),
                parityResult.executedChecks()
        );
    }

    public void resetFactBuildCheckpoint() {
        factBuilderService.resetFactBuildCheckpoint();
    }

    public CanonicalResetResult resetCanonicalState() {
        long events = importEventRepository.count();
        long journalIdentities = journalIdentityRepository.count();
        long metricFacts = metricFactRepository.count();
        long categoryFacts = categoryFactRepository.count();
        long identityConflicts = identityConflictRepository.count();
        long factConflicts = factConflictRepository.count();
        long rankingRows = rankingViewRepository.count();
        long scoringRows = scoringViewRepository.count();

        scoringViewRepository.deleteAll();
        rankingViewRepository.deleteAll();
        factConflictRepository.deleteAll();
        identityConflictRepository.deleteAll();
        categoryFactRepository.deleteAll();
        metricFactRepository.deleteAll();
        journalIdentityRepository.deleteAll();
        importEventRepository.deleteAll();
        factBuilderService.resetFactBuildCheckpoint();

        return new CanonicalResetResult(
                events,
                journalIdentities,
                metricFacts,
                categoryFacts,
                identityConflicts,
                factConflicts,
                rankingRows,
                scoringRows
        );
    }

    public MigrationStepResult runIngestStep(String sourceVersionOverride) {
        String normalizedSourceVersion = normalizeSourceVersion(sourceVersionOverride);
        ImportProcessingResult ingestResult = ingestionService.ingestDirectory(migrationDataDirectory, normalizedSourceVersion);
        return MigrationStepResult.executed("ingest", ingestResult);
    }

    public MigrationStepResult runBuildFactsStep(
            Integer startBatchOverride,
            String sourceVersionOverride,
            boolean useCheckpoint
    ) {
        String normalizedSourceVersion = normalizeSourceVersion(sourceVersionOverride);
        String runId = "wos-fact-build-step-" + Instant.now().toEpochMilli();
        WosFactBuilderService.FactBuildRunResult run = factBuilderService.buildFactsFromImportEventsWithCheckpoint(
                startBatchOverride,
                useCheckpoint,
                runId,
                normalizedSourceVersion
        );
        return MigrationStepResult.executed(
                "build-facts",
                run.result(),
                run.startBatch(),
                run.endBatch(),
                run.batchesProcessed(),
                run.resumedFromCheckpoint(),
                run.checkpointLastCompletedBatch()
        );
    }

    public MigrationStepResult runEnrichCategoryRankingsStep() {
        ImportProcessingResult result = factBuilderService.enrichMissingCategoryRankingFields();
        return MigrationStepResult.executed("enrich-category-rankings", result);
    }

    public record WosBigBangMigrationResult(
            boolean dryRun,
            String dataDirectory,
            String sourceVersion,
            Instant startedAt,
            Instant completedAt,
            MigrationStepResult ingest,
            MigrationStepResult buildFacts,
            MigrationStepResult enrichCategoryRankings,
            MigrationStepResult buildProjections,
            VerificationSummary verification
    ) {
    }

    public record MigrationStepResult(
            String stepName,
            boolean executed,
            int processed,
            int imported,
            int updated,
            int skipped,
            int errors,
            String note,
            List<String> samples,
            Integer startBatch,
            Integer endBatch,
            Integer batchesProcessed,
            Boolean resumedFromCheckpoint,
            Integer checkpointLastCompletedBatch
    ) {
        static MigrationStepResult executed(String stepName, ImportProcessingResult result) {
            return executed(stepName, result, null, null, null, null, null);
        }

        static MigrationStepResult executed(
                String stepName,
                ImportProcessingResult result,
                Integer startBatch,
                Integer endBatch,
                Integer batchesProcessed,
                Boolean resumedFromCheckpoint,
                Integer checkpointLastCompletedBatch
        ) {
            return new MigrationStepResult(
                    stepName,
                    true,
                    result.getProcessedCount(),
                    result.getImportedCount(),
                    result.getUpdatedCount(),
                    result.getSkippedCount(),
                    result.getErrorCount(),
                    null,
                    result.getErrorsSample(),
                    startBatch,
                    endBatch,
                    batchesProcessed,
                    resumedFromCheckpoint,
                    checkpointLastCompletedBatch
            );
        }

        static MigrationStepResult dryRun(
                String stepName,
                String note,
                List<String> samples,
                int processed,
                int errors,
                Integer startBatch,
                Integer endBatch,
                Integer batchesProcessed,
                Boolean resumedFromCheckpoint,
                Integer checkpointLastCompletedBatch
        ) {
            return new MigrationStepResult(
                    stepName,
                    false,
                    processed,
                    0,
                    0,
                    0,
                    errors,
                    note,
                    samples,
                    startBatch,
                    endBatch,
                    batchesProcessed,
                    resumedFromCheckpoint,
                    checkpointLastCompletedBatch
            );
        }
    }

    public record VerificationSummary(
            long importEvents,
            long journalIdentities,
            long metricFacts,
            long categoryFacts,
            long rankingViewRows,
            long scoringViewRows,
            int parserProcessed,
            int parserParsed,
            int parserSkipped,
            int parserErrors,
            List<String> parserSamples,
            boolean rankingViewAlignedWithIdentity,
            boolean scoringViewAlignedWithCategoryFacts,
            boolean parityPassed,
            int parityMismatchCount,
            int parityAllowlistedMismatchCount,
            List<String> paritySamples,
            List<String> parityExecutedChecks
    ) {
    }

    public record CanonicalResetResult(
            long importEvents,
            long journalIdentities,
            long metricFacts,
            long categoryFacts,
            long identityConflicts,
            long factConflicts,
            long rankingViewRows,
            long scoringViewRows
    ) {
    }

}
