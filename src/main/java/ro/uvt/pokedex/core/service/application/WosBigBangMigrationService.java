package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosScoringViewRepository;
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
    private final WosImportEventParserOrchestrator parserOrchestrator;
    private final WosImportEventRepository importEventRepository;
    private final WosJournalIdentityRepository journalIdentityRepository;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosRankingViewRepository rankingViewRepository;
    private final WosScoringViewRepository scoringViewRepository;

    @Value("${h14.wos.migration.data-dir:data/loaded}")
    private String migrationDataDirectory;

    public WosBigBangMigrationResult run(boolean dryRun, String sourceVersionOverride) {
        Instant startedAt = Instant.now();
        MigrationStepResult ingestStep;
        MigrationStepResult factStep;
        MigrationStepResult projectionStep;

        if (dryRun) {
            WosImportEventIngestionService.WosIngestionPreview preview =
                    ingestionService.previewDirectory(migrationDataDirectory, normalizeSourceVersion(sourceVersionOverride));
            ingestStep = MigrationStepResult.dryRun(
                    "ingest",
                    "dry-run preview: files=" + preview.filesScanned()
                            + ", plannedEvents=" + preview.plannedEvents()
                            + ", errors=" + preview.errorCount(),
                    preview.samples(),
                    preview.plannedEvents(),
                    preview.errorCount()
            );
            factStep = MigrationStepResult.dryRun("build-facts", "dry-run: canonical fact build skipped", List.of(), 0, 0);
            projectionStep = MigrationStepResult.dryRun("build-projections", "dry-run: projection rebuild skipped", List.of(), 0, 0);
        } else {
            ImportProcessingResult ingestionResult = ingestionService.ingestDirectory(
                    migrationDataDirectory,
                    normalizeSourceVersion(sourceVersionOverride)
            );
            ImportProcessingResult factResult = factBuilderService.buildFactsFromImportEvents();
            ImportProcessingResult projectionResult = projectionBuilderService.rebuildWosProjections();
            ingestStep = MigrationStepResult.executed("ingest", ingestionResult);
            factStep = MigrationStepResult.executed("build-facts", factResult);
            projectionStep = MigrationStepResult.executed("build-projections", projectionResult);
        }

        WosParserRunResult parserRun = parserOrchestrator.parseAllEvents();
        VerificationSummary verificationSummary = buildVerificationSummary(parserRun.summary());

        return new WosBigBangMigrationResult(
                dryRun,
                migrationDataDirectory,
                normalizeSourceVersion(sourceVersionOverride),
                startedAt,
                Instant.now(),
                ingestStep,
                factStep,
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

    private VerificationSummary buildVerificationSummary(WosParserRunSummary parserSummary) {
        long importEvents = importEventRepository.count();
        long journalIdentities = journalIdentityRepository.count();
        long metricFacts = metricFactRepository.count();
        long categoryFacts = categoryFactRepository.count();
        long rankingRows = rankingViewRepository.count();
        long scoringRows = scoringViewRepository.count();

        boolean rankingAligned = rankingRows <= journalIdentities;
        boolean scoringAligned = scoringRows <= categoryFacts;
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
                scoringAligned
        );
    }

    public record WosBigBangMigrationResult(
            boolean dryRun,
            String dataDirectory,
            String sourceVersion,
            Instant startedAt,
            Instant completedAt,
            MigrationStepResult ingest,
            MigrationStepResult buildFacts,
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
            List<String> samples
    ) {
        static MigrationStepResult executed(String stepName, ImportProcessingResult result) {
            return new MigrationStepResult(
                    stepName,
                    true,
                    result.getProcessedCount(),
                    result.getImportedCount(),
                    result.getUpdatedCount(),
                    result.getSkippedCount(),
                    result.getErrorCount(),
                    null,
                    result.getErrorsSample()
            );
        }

        static MigrationStepResult dryRun(String stepName, String note, List<String> samples, int processed, int errors) {
            return new MigrationStepResult(stepName, false, processed, 0, 0, 0, errors, note, samples);
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
            boolean scoringViewAlignedWithCategoryFacts
    ) {
    }
}
