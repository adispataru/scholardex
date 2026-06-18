package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;
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
    private final WosScholardexOnboardingService wosScholardexOnboardingService;
    private final WosProjectionBuilderService projectionBuilderService;
    private final WosParityReconciliationService parityReconciliationService;
    private final WosImportEventParserOrchestrator parserOrchestrator;
    private final WosImportEventRepository importEventRepository;
    private final WosJournalIdentityRepository journalIdentityRepository;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosIdentityConflictRepository identityConflictRepository;
    private final WosFactConflictRepository factConflictRepository;
    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;

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
            // H66B M8 — WoS forum onboarding moved to the identity-first ForumBuilder (WoS create-or-match,
            // last). The WoS rebuild now builds WoS facts (journal identity + metrics) + projections only;
            // forums + publication→WoS links are produced by the Scopus-side build after the curated sources.
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
        long rankingRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.wos_ranking_view", Long.class);
        long scoringRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.wos_scoring_view", Long.class);

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
        long rankingRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.wos_ranking_view", Long.class);
        long scoringRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.wos_scoring_view", Long.class);
        // The canonical forum layer (scholardex.forum_facts) is WoS-rooted: WoS onboarding mints the
        // canonical forum and Scopus folds in. Scopus reset only removes source=SCOPUS canonical rows, so
        // WoS-source canonical forums (and their FORUM/WOS source links) had no wipe and went stale —
        // every "from scratch" rebuild silently skipped them (WoS onboarding found the existing link and
        // took the no-overwrite update path). Mirror the Scopus reset's source-scoped removal here.
        long canonicalForums = mongoTemplate.count(wosCanonicalForumQuery(), ScholardexForumFact.class);
        long forumSourceLinks = mongoTemplate.count(wosForumSourceLinkQuery(), ScholardexSourceLink.class);

        jdbcTemplate.execute("TRUNCATE TABLE reporting_read.wos_scoring_view, reporting_read.wos_category_fact, reporting_read.wos_metric_fact, reporting_read.wos_ranking_view");
        factConflictRepository.deleteAll();
        identityConflictRepository.deleteAll();
        categoryFactRepository.deleteAll();
        metricFactRepository.deleteAll();
        journalIdentityRepository.deleteAll();
        importEventRepository.deleteAll();
        mongoTemplate.remove(wosCanonicalForumQuery(), ScholardexForumFact.class);
        mongoTemplate.remove(wosForumSourceLinkQuery(), ScholardexSourceLink.class);
        factBuilderService.resetFactBuildCheckpoint();

        return new CanonicalResetResult(
                events,
                journalIdentities,
                metricFacts,
                categoryFacts,
                identityConflicts,
                factConflicts,
                rankingRows,
                scoringRows,
                canonicalForums,
                forumSourceLinks
        );
    }

    private static Query wosCanonicalForumQuery() {
        return Query.query(Criteria.where("source").is("WOS"));
    }

    private static Query wosForumSourceLinkQuery() {
        return Query.query(Criteria.where("entityType").is(ScholardexEntityType.FORUM).and("source").is("WOS"));
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
        // H66B M8 — WoS forum onboarding moved to the ForumBuilder (WoS last); WoS rebuild builds facts only.
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
            long scoringViewRows,
            long canonicalForums,
            long forumSourceLinks
    ) {
    }

}
