package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.CanonicalObservabilityMetrics;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.ScopusDataService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScopusBigBangMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ScopusBigBangMigrationService.class);

    // Fixed (non-UUID) provenance label so FORUM/SCOPUS source-link batch ids stay rebuild-deterministic.
    private static final String SCOPUS_FORUM_CANON_BATCH = "scopus-forum-canonicalization";

    @Value("${scopus.data.file}")
    private String scopusDataFile;

    // H66B M8-B: the curated Scopus source feeds (Source List backbone, CiteScore forum stream, Book list) are
    // folded into the unified rebuild's ingest phase so a single rebuild builds the full-feed forum registry.
    // Blank path skips the feed (keeps Scopus-JSON-only rebuilds and unit tests unaffected).
    @Value("${h66.scopus.source-list-file:}")
    private String sourceListFile;
    @Value("${h66.scopus.citescore-file:}")
    private String citeScoreFile;
    @Value("${h66.scopus.book-list-file:}")
    private String bookListFile;
    @Value("${h66.scopus.book-list-as-of:2026}")
    private String bookListAsOf;

    private final ScopusDataService scopusDataService;
    private final ScopusFactBuilderService scopusFactBuilderService;
    private final ScholardexProjectionBuilderService scopusProjectionBuilderService;
    private final ScopusCanonicalIndexMaintenanceService scopusCanonicalIndexMaintenanceService;
    private final ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    private final ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    private final ro.uvt.pokedex.core.service.importing.scopus.OpenAlexCanonicalizationService openAlexCanonicalizationService;
    private final ro.uvt.pokedex.core.service.importing.scopus.OpenAlexCitationCanonicalizationService openAlexCitationCanonicalizationService;
    private final ro.uvt.pokedex.core.service.dblp.DblpConferenceResolveService dblpConferenceResolveService;
    private final ScholardexCitationCanonicalizationService citationCanonicalizationService;
    // H66B B66B.1 — forum building (dedup + Scopus canonicalization + ERIH onboard + erih-dedup) is now a
    // single forums-first step behind ScholardexForumBuilder, instead of inline across three orchestrator paths.
    private final ScholardexForumBuilder forumBuilder;
    private final WosScholardexOnboardingService wosScholardexOnboardingService;
    private final ScopusBuildSkipGateService scopusBuildSkipGateService;
    private final ScholardexCanonicalBuildCheckpointService canonicalBuildCheckpointService;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexEdgeReconciliationService edgeReconciliationService;
    private final ScopusImportEventRepository importEventRepository;
    private final ScopusPublicationFactRepository publicationFactRepository;
    private final ScopusCitationFactRepository citationFactRepository;
    private final ScopusForumFactRepository forumFactRepository;
    private final ScopusAuthorFactRepository authorFactRepository;
    private final ScopusAffiliationFactRepository affiliationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexCitationFactRepository scholardexCitationFactRepository;
    private final ScholardexSourceLinkRepository scholardexSourceLinkRepository;
    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;

    public ScopusBigBangMigrationResult runIngestStep() {
        Instant startedAt = Instant.now();
        ImportProcessingResult publicationImport = scopusDataService.importScopusDataSync(scopusDataFile, 0, false);
        ImportProcessingResult citationImport = scopusDataService.importScopusDataCitationsSync(scopusDataFile);
        MigrationStepResult ingest = mergeImportResults("ingest", publicationImport, citationImport);
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                ingest,
                null,
                null,
                null,
                buildVerificationSummary()
        );
    }

    public ScopusBigBangMigrationResult runBuildFactsStep() {
        return runBuildFactsStep(null, true, null);
    }

    public ScopusBigBangMigrationResult runBuildFactsStep(
            Integer startBatchOverride,
            boolean useCheckpoint,
            Integer chunkSizeOverride
    ) {
        return runBuildFactsStep(startBatchOverride, useCheckpoint, chunkSizeOverride, false);
    }

    public ScopusBigBangMigrationResult runBuildFactsStep(
            Integer startBatchOverride,
            boolean useCheckpoint,
            Integer chunkSizeOverride,
            boolean skipUnchanged
    ) {
        Instant startedAt = Instant.now();
        // H56: opt-in fast path — when the inputs (import-event ledger, canonical forums) and builder
        // versions are identical to the last successful run, the rebuild is a deterministic no-op.
        if (skipUnchanged && scopusBuildSkipGateService.canSkipBuildFacts()) {
            log.info("Scopus build-facts orchestration skipped: inputs unchanged since last successful run");
            return new ScopusBigBangMigrationResult(
                    scopusDataFile,
                    startedAt,
                    Instant.now(),
                    null,
                    new MigrationStepResult("build-facts", false, 0, 0, 0, 0, 0,
                            "skipped: inputs unchanged since last successful run", List.of(),
                            null, null, null, null, null, null),
                    null,
                    null,
                    buildVerificationSummary()
            );
        }
        log.info("Scopus build-facts orchestration started: startBatchOverride={} useCheckpoint={} chunkSizeOverride={}",
                startBatchOverride, useCheckpoint, chunkSizeOverride);
        ImportProcessingResult facts = runStepWithTiming("scopus-fact-builder", scopusFactBuilderService::buildFactsFromImportEvents);
        CanonicalBuildOptions options = new CanonicalBuildOptions(
                chunkSizeOverride, startBatchOverride, useCheckpoint, null, null, false, false);
        log.info("Scopus build-facts next step: scholardex-affiliation-canonicalization");
        ImportProcessingResult canonicalAffiliations = runStepWithTiming(
                "scholardex-affiliation-canonicalization",
                () -> affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(options));
        log.info("Scopus build-facts next step: scholardex-author-canonicalization");
        ImportProcessingResult canonicalAuthors = runStepWithTiming(
                "scholardex-author-canonicalization",
                () -> authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(options));
        log.info("Scopus build-facts next step: scholardex-forum-build (forums-first)");
        ScholardexForumBuilder.ScopusForumBuildResult forumBuild =
                forumBuilder.buildScopusForums(SCOPUS_FORUM_CANON_BATCH, "build-facts");
        log.info("Scopus build-facts next step: scholardex-publication-canonicalization");
        ImportProcessingResult canonicalPublications = runStepWithTiming(
                "scholardex-publication-canonicalization",
                () -> publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(options));
        // H66B M8: link publications to their WoS source ids now that the publications exist (moved out of
        // the WoS rebuild, which ran before any publications were built).
        ImportProcessingResult wosPublicationLinks =
                wosScholardexOnboardingService.linkPublicationsToWos(SCOPUS_FORUM_CANON_BATCH, "build-facts");
        log.info("Scopus build-facts next step: scholardex-citation-canonicalization");
        ImportProcessingResult canonicalCitations = runStepWithTiming(
                "scholardex-citation-canonicalization",
                () -> citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(options));
        ImportProcessingResult buildFactsCombined = combine(facts, combine(canonicalAffiliations, canonicalAuthors,
                forumBuild.dedup(), forumBuild.canonicalization(), forumBuild.erihOnboarding(),
                forumBuild.doajOnboarding(), forumBuild.wosOnboarding(), forumBuild.membershipDedup(),
                wosPublicationLinks,
                canonicalPublications, canonicalCitations));
        log.info("Scopus build-facts orchestration completed: processed={} imported={} updated={} skipped={} errors={}",
                buildFactsCombined.getProcessedCount(),
                buildFactsCombined.getImportedCount(),
                buildFactsCombined.getUpdatedCount(),
                buildFactsCombined.getSkippedCount(),
                buildFactsCombined.getErrorCount());
        if (buildFactsCombined.getErrorCount() == 0) {
            scopusBuildSkipGateService.recordBuildFactsSuccess();
        }
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                null,
                MigrationStepResult.executed("build-facts", buildFactsCombined),
                null,
                null,
                buildVerificationSummary()
            );
    }

    public ScopusBigBangMigrationResult runIncrementalUploadBuildStep(
            String sourceBatchIdFilter,
            Integer chunkSizeOverride
    ) {
        Instant startedAt = Instant.now();
        log.info("Scopus incremental upload build orchestration started: sourceBatchIdFilter={} chunkSizeOverride={}",
                sourceBatchIdFilter, chunkSizeOverride);
        ImportProcessingResult facts = runStepWithTiming(
                "scopus-fact-builder",
                () -> scopusFactBuilderService.buildFactsFromImportEvents(sourceBatchIdFilter)
        );
        CanonicalBuildOptions options = new CanonicalBuildOptions(
                chunkSizeOverride,
                null,
                false,
                sourceBatchIdFilter,
                null,
                false,
                false
        );
        log.info("Scopus incremental upload next step: scholardex-affiliation-canonicalization");
        ImportProcessingResult canonicalAffiliations = runStepWithTiming(
                "scholardex-affiliation-canonicalization",
                () -> affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(options));
        log.info("Scopus incremental upload next step: scholardex-author-canonicalization");
        ImportProcessingResult canonicalAuthors = runStepWithTiming(
                "scholardex-author-canonicalization",
                () -> authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(options));
        // H66B Phase 2 — Tier-2 resolve: bind only this upload's venues to the registry (find-or-mint), deferring
        // the global reconcile (dedup / ERIH-DOAJ onboarding / M9 / M10) instead of rebuilding the whole forum
        // registry per upload. A later reconcile cleans up + re-points any transient duplicate (no orphans).
        log.info("Scopus incremental upload next step: scholardex-forum-resolve (batch-scoped)");
        ScholardexForumBuilder.ForumResolveResult forumResolve =
                forumBuilder.resolve(sourceBatchIdFilter, "incremental");
        log.info("Scopus incremental upload next step: scholardex-publication-canonicalization");
        ImportProcessingResult canonicalPublications = runStepWithTiming(
                "scholardex-publication-canonicalization",
                () -> publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(options));
        ImportProcessingResult wosPublicationLinks =
                wosScholardexOnboardingService.linkPublicationsToWos(SCOPUS_FORUM_CANON_BATCH, "incremental");
        log.info("Scopus incremental upload next step: scholardex-citation-canonicalization");
        ImportProcessingResult canonicalCitations = runStepWithTiming(
                "scholardex-citation-canonicalization",
                () -> citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(options));
        ImportProcessingResult buildFactsCombined = combine(facts, combine(canonicalAffiliations, canonicalAuthors,
                wosPublicationLinks,
                canonicalPublications, canonicalCitations));
        log.info("Scopus incremental upload build orchestration completed: sourceBatchIdFilter={} processed={} imported={} updated={} skipped={} errors={} forumResolve[minted={} matched={} deferredConflicts={}]",
                sourceBatchIdFilter,
                buildFactsCombined.getProcessedCount(),
                buildFactsCombined.getImportedCount(),
                buildFactsCombined.getUpdatedCount(),
                buildFactsCombined.getSkippedCount(),
                buildFactsCombined.getErrorCount(),
                forumResolve.minted(),
                forumResolve.matched(),
                forumResolve.deferredConflicts());
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                null,
                MigrationStepResult.executed("build-facts", buildFactsCombined),
                null,
                null,
                buildVerificationSummary()
        );
    }

    public ScopusBigBangMigrationResult runBuildProjectionsStep() {
        Instant startedAt = Instant.now();
        ImportProcessingResult projections = runStepWithTiming(
                "scopus-projection-builder",
                scopusProjectionBuilderService::rebuildViews
        );
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                null,
                null,
                MigrationStepResult.executed("build-projections", projections),
                null,
                buildVerificationSummary()
        );
    }

    public ScopusBigBangMigrationResult runIncrementalUploadProjectionStep(String sourceBatchIdFilter) {
        Instant startedAt = Instant.now();
        ImportProcessingResult projections = runStepWithTiming(
                "scopus-projection-builder",
                () -> scopusProjectionBuilderService.rebuildViewsForBatch(sourceBatchIdFilter)
        );
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                null,
                null,
                MigrationStepResult.executed("build-projections", projections),
                null,
                buildVerificationSummary()
        );
    }

    public ScopusBigBangMigrationResult runEnsureIndexesStep() {
        Instant startedAt = Instant.now();
        ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult indexResult =
                scopusCanonicalIndexMaintenanceService.ensureIndexes();
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                null,
                null,
                null,
                new IndexStepResult(
                        true,
                        indexResult.created().size(),
                        indexResult.present().size(),
                        indexResult.invalid().size(),
                        indexResult.errors().size(),
                        indexResult.invalid(),
                        indexResult.errors()
                ),
                buildVerificationSummary()
        );
    }

    /**
     * H66B M8-B — ingest the curated Scopus source feeds during the unified rebuild. Each feed is skipped when
     * its path is unset (blank @Value default) or the file is missing, so Scopus-JSON-only rebuilds and unit
     * tests (which leave the paths null) are unaffected. Runs after the Scopus JSON import and before
     * {@code buildFactsFromImportEvents}, so all FORUM events are present when the registry canonicalizes.
     */
    private void ingestCuratedScopusFeedsIfConfigured() {
        long now = Instant.now().toEpochMilli();
        if (isReadableFile(sourceListFile)) {
            ImportProcessingResult r = scopusDataService.importSourceListXlsxFromPath(sourceListFile, "sourcelist-" + now);
            log.info("Unified rebuild Source List ingest ({}): processed={} imported={} updated={} errors={}",
                    sourceListFile, r.getProcessedCount(), r.getImportedCount(), r.getUpdatedCount(), r.getErrorCount());
        } else if (sourceListFile != null && !sourceListFile.isBlank()) {
            log.warn("Unified rebuild Source List ingest skipped: file not found ({})", sourceListFile);
        }
        if (isReadableFile(citeScoreFile)) {
            ImportProcessingResult r = scopusDataService.importCiteScoreCsvFromPath(citeScoreFile, "citescore-" + now);
            log.info("Unified rebuild CiteScore ingest ({}): processed={} imported={} skipped={} errors={}",
                    citeScoreFile, r.getProcessedCount(), r.getImportedCount(), r.getSkippedCount(), r.getErrorCount());
        } else if (citeScoreFile != null && !citeScoreFile.isBlank()) {
            log.warn("Unified rebuild CiteScore ingest skipped: file not found ({})", citeScoreFile);
        }
        if (isReadableFile(bookListFile)) {
            ImportProcessingResult r = scopusDataService.importBookListXlsxFromPath(bookListFile, "booklist-" + now, bookListAsOf);
            log.info("Unified rebuild Book List ingest ({}): processed={} imported={} errors={}",
                    bookListFile, r.getProcessedCount(), r.getImportedCount(), r.getErrorCount());
        } else if (bookListFile != null && !bookListFile.isBlank()) {
            log.warn("Unified rebuild Book List ingest skipped: file not found ({})", bookListFile);
        }
    }

    private boolean isReadableFile(String path) {
        return path != null && !path.isBlank() && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path));
    }

    public ScopusBigBangMigrationResult runFull() {
        Instant startedAt = Instant.now();
        ImportProcessingResult publicationImport = scopusDataService.importScopusDataSync(scopusDataFile, 0, false);
        ImportProcessingResult citationImport = scopusDataService.importScopusDataCitationsSync(scopusDataFile);
        // H66B M8-B: fold the curated Scopus feeds (Source List / CiteScore / Book list) into the same ingest
        // phase so buildFactsFromImportEvents canonicalizes the full-feed forum registry, not Scopus JSON alone.
        ingestCuratedScopusFeedsIfConfigured();
        ImportProcessingResult facts = scopusFactBuilderService.buildFactsFromImportEvents();
        CanonicalBuildOptions options = CanonicalBuildOptions.defaults();
        // H66B M8 — forums first, identity-first: the registry is built from curated sources then WoS
        // create-or-match LAST inside buildScopusForums, before publication/citation canonicalization resolves
        // venues against it. (WoS journal_identity comes from the WoS fact phase, which ran before this.)
        // H73 S2.2: moved ahead of the affiliation/author/publication canon block so a single early
        // OpenAlex-first canon pass can stamp forumId against a complete forum registry. buildScopusForums has
        // no dependency on canonical author/affiliation/publication facts (verified) — only the forum registry,
        // WoS journal identity, and the curated ERIH/DOAJ feeds — so the move is behavior-neutral for the
        // existing steps (pubs still resolve venues against forums; they remain after the forum build).
        ScholardexForumBuilder.ScopusForumBuildResult forumBuild =
                forumBuilder.buildScopusForums(SCOPUS_FORUM_CANON_BATCH, "run-full");
        // H73 S2.2 (OpenAlex-first): the OpenAlex canon now runs BEFORE the Scopus affiliation/author/publication
        // canon. It mints DOI-keyed canonical pubs + OpenAlex-keyed authors, stamps forumId against the just-built
        // forum registry, emits ROR-backbone affiliation edges, and writes positional (AUTHOR, SCOPUS, auid) ->
        // OpenAlex-author source-links from the same-DOI Scopus pub source-facts. The Scopus canon that follows
        // then resolves each AU-ID into the OpenAlex author via those links (no Scopus-keyed twin) and resolves
        // each shared DOI into the existing OpenAlex pub, enriching it without clobbering (defer-to-OpenAlex).
        ImportProcessingResult canonicalOpenAlex = openAlexCanonicalizationService.rebuildCanonicalFacts();
        ImportProcessingResult canonicalAffiliations = affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(options);
        ImportProcessingResult canonicalAuthors = authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(options);
        ImportProcessingResult canonicalPublications = publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(options);
        ImportProcessingResult wosPublicationLinks =
                wosScholardexOnboardingService.linkPublicationsToWos(SCOPUS_FORUM_CANON_BATCH, "run-full");
        // H66B Phase 4a Stage 2: replay DOI-keyed OpenAlex citation edges (after OpenAlex + Scopus pubs exist so endpoints resolve).
        ImportProcessingResult canonicalOpenAlexCitations = openAlexCitationCanonicalizationService.rebuildCitationFacts();
        // H66B Phase 4b: re-mint DBLP conference forums + re-link forumId from durable evidence (no API), since
        // forum_facts is wiped here. Runs after pubs + forums exist, before projections.
        ImportProcessingResult dblpConferences = dblpConferenceResolveService.rebuildFromEvidence();
        ImportProcessingResult canonicalCitations = citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(options);
        ImportProcessingResult projections = scopusProjectionBuilderService.rebuildViews();
        ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult indexResult =
                scopusCanonicalIndexMaintenanceService.ensureIndexes();
        ImportProcessingResult buildFactsCombined = combine(facts, combine(canonicalAffiliations, canonicalAuthors,
                forumBuild.dedup(), forumBuild.canonicalization(), forumBuild.erihOnboarding(),
                forumBuild.doajOnboarding(), forumBuild.wosOnboarding(), forumBuild.membershipDedup(),
                wosPublicationLinks,
                canonicalPublications, canonicalOpenAlex, canonicalOpenAlexCitations, dblpConferences, canonicalCitations));
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                mergeImportResults("ingest", publicationImport, citationImport),
                MigrationStepResult.executed("build-facts", buildFactsCombined),
                MigrationStepResult.executed("build-projections", projections),
                new IndexStepResult(
                        true,
                        indexResult.created().size(),
                        indexResult.present().size(),
                        indexResult.invalid().size(),
                        indexResult.errors().size(),
                        indexResult.invalid(),
                        indexResult.errors()
                ),
                buildVerificationSummary()
        );
    }

    private VerificationSummary buildVerificationSummary() {
        return new VerificationSummary(
                importEventRepository.count(),
                publicationFactRepository.count(),
                scholardexPublicationFactRepository.count(),
                citationFactRepository.count(),
                scholardexCitationFactRepository.count(),
                forumFactRepository.count(),
                authorFactRepository.count(),
                affiliationFactRepository.count(),
                scholardexSourceLinkRepository.count(),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.scholardex_publication_view", Long.class)
        );
    }

    private MigrationStepResult mergeImportResults(
            String stepName,
            ImportProcessingResult publicationImport,
            ImportProcessingResult citationImport
    ) {
        List<String> samples = new ArrayList<>();
        samples.addAll(publicationImport.getErrorsSample());
        samples.addAll(citationImport.getErrorsSample());
        return new MigrationStepResult(
                stepName,
                true,
                publicationImport.getProcessedCount() + citationImport.getProcessedCount(),
                publicationImport.getImportedCount() + citationImport.getImportedCount(),
                publicationImport.getUpdatedCount() + citationImport.getUpdatedCount(),
                publicationImport.getSkippedCount() + citationImport.getSkippedCount(),
                publicationImport.getErrorCount() + citationImport.getErrorCount(),
                null,
                samples,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public record ScopusBigBangMigrationResult(
            String dataFile,
            Instant startedAt,
            Instant completedAt,
            MigrationStepResult ingest,
            MigrationStepResult buildFacts,
            MigrationStepResult buildProjections,
            IndexStepResult ensureIndexes,
            VerificationSummary verification
    ) {
    }

    public record IndexStepResult(
            boolean executed,
            int created,
            int present,
            int invalid,
            int errors,
            List<String> invalidSamples,
            List<String> errorSamples
    ) {
    }

    public record VerificationSummary(
            long importEvents,
            long publicationFacts,
            long canonicalPublicationFacts,
            long citationFacts,
            long canonicalCitationFacts,
            long forumFacts,
            long authorFacts,
            long affiliationFacts,
            long publicationSourceLinks,
            long publicationViews
    ) {
    }

    public ImportProcessingResult runCitationIdentityBackfill() {
        return citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult runCanonicalBuildStep(
            String entity,
            Integer startBatchOverride,
            boolean useCheckpoint,
            Integer chunkSizeOverride,
            boolean reconcileSourceLinks,
            boolean reconcileEdges
    ) {
        long startedAtNanos = System.nanoTime();
        String runId = java.util.UUID.randomUUID().toString();
        CanonicalBuildOptions options = new CanonicalBuildOptions(
                chunkSizeOverride,
                startBatchOverride,
                useCheckpoint,
                null,
                null,
                reconcileSourceLinks,
                reconcileEdges
        );
        if (entity == null || entity.isBlank() || "all".equalsIgnoreCase(entity)) {
            log.info("Scopus canonical build orchestration started: entity=all startBatchOverride={} useCheckpoint={} chunkSizeOverride={}",
                    startBatchOverride, useCheckpoint, chunkSizeOverride);
            log.info("Scopus canonical build next step: scholardex-affiliation-canonicalization");
            ImportProcessingResult affiliations = runStepWithTiming(
                    "scholardex-affiliation-canonicalization",
                    () -> affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(options));
            log.info("Scopus canonical build next step: scholardex-author-canonicalization");
            ImportProcessingResult authors = runStepWithTiming(
                    "scholardex-author-canonicalization",
                    () -> authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(options));
            log.info("Scopus canonical build next step: scholardex-publication-canonicalization");
            ImportProcessingResult publications = runStepWithTiming(
                    "scholardex-publication-canonicalization",
                    () -> publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(options));
            log.info("Scopus canonical build next step: scholardex-citation-canonicalization");
            ImportProcessingResult citations = runStepWithTiming(
                    "scholardex-citation-canonicalization",
                    () -> citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(options));
            ImportProcessingResult combined = combine(affiliations, authors, publications, citations);
            if (reconcileSourceLinks) {
                applySourceLinkReconcileSummary(combined, sourceLinkService.reconcileLinks());
            }
            if (reconcileEdges) {
                applyEdgeReconcileSummary(combined, edgeReconciliationService.reconcileEdges());
            }
            String outcome = combined.getErrorCount() > 0 ? "failure" : "success";
            CanonicalObservabilityMetrics.recordCanonicalBuildRun("all", "SCOPUS", outcome, System.nanoTime() - startedAtNanos);
            log.info("CANONICAL_MAINTENANCE canonical_build runId={} batchId={} correlationId={} entity={} source=SCOPUS outcome={} processed={} imported={} updated={} skipped={} errors={} totalMs={}",
                    runId,
                    startBatchOverride,
                    "N/A",
                    "all",
                    outcome,
                    combined.getProcessedCount(),
                    combined.getImportedCount(),
                    combined.getUpdatedCount(),
                    combined.getSkippedCount(),
                    combined.getErrorCount(),
                    (System.nanoTime() - startedAtNanos) / 1_000_000L);
            log.info("Scopus canonical build orchestration completed: entity=all processed={} imported={} updated={} skipped={} errors={}",
                    combined.getProcessedCount(),
                    combined.getImportedCount(),
                    combined.getUpdatedCount(),
                    combined.getSkippedCount(),
                    combined.getErrorCount());
            return combined;
        }
        ImportProcessingResult result = switch (entity.trim().toLowerCase()) {
            case "affiliation" -> affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(options);
            case "author" -> authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(options);
            case "publication" -> publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(options);
            case "citation" -> citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(options);
            default -> {
                ImportProcessingResult unknownEntityResult = new ImportProcessingResult(10);
                unknownEntityResult.markSkipped("unknown canonical build entity: " + entity);
                yield unknownEntityResult;
            }
        };
        if (reconcileSourceLinks) {
            applySourceLinkReconcileSummary(result, sourceLinkService.reconcileLinks());
        }
        if (reconcileEdges) {
            applyEdgeReconcileSummary(result, edgeReconciliationService.reconcileEdges());
        }
        String outcome = result.getErrorCount() > 0 ? "failure" : "success";
        CanonicalObservabilityMetrics.recordCanonicalBuildRun(entity.trim().toLowerCase(), "SCOPUS", outcome, System.nanoTime() - startedAtNanos);
        log.info("CANONICAL_MAINTENANCE canonical_build runId={} batchId={} correlationId={} entity={} source=SCOPUS outcome={} processed={} imported={} updated={} skipped={} errors={} totalMs={}",
                runId,
                startBatchOverride,
                "N/A",
                entity.trim().toLowerCase(),
                outcome,
                result.getProcessedCount(),
                result.getImportedCount(),
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount(),
                (System.nanoTime() - startedAtNanos) / 1_000_000L);
        return result;
    }

    public ScholardexSourceLinkService.ImportRepairSummary runSourceLinkReconcileStep() {
        ScholardexSourceLinkService.ImportRepairSummary summary = sourceLinkService.reconcileLinks();
        log.info("CANONICAL_RECONCILE source_link_reconcile runId={} batchId={} correlationId={} entity=SOURCE_LINK source=ALL outcome={} updated={} skipped={} errors={}",
                java.util.UUID.randomUUID().toString(),
                "N/A",
                "N/A",
                summary.errors() > 0 ? "failure" : "success",
                summary.updated(),
                summary.skipped(),
                summary.errors());
        return summary;
    }

    public ImportProcessingResult runEdgeReconcileStep() {
        ImportProcessingResult result = edgeReconciliationService.reconcileEdges();
        log.info("CANONICAL_RECONCILE edge_reconcile runId={} batchId={} correlationId={} entity=EDGE source=CANONICAL outcome={} updated={} skipped={} errors={}",
                java.util.UUID.randomUUID().toString(),
                "N/A",
                "N/A",
                result.getErrorCount() > 0 ? "failure" : "success",
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount());
        return result;
    }

    public ImportProcessingResult runIncrementalUploadEdgeReconcileStep(String sourceBatchIdFilter) {
        ImportProcessingResult result = edgeReconciliationService.reconcileEdges(sourceBatchIdFilter);
        log.info("CANONICAL_RECONCILE edge_reconcile runId={} batchId={} correlationId={} entity=EDGE source=CANONICAL outcome={} updated={} skipped={} errors={}",
                java.util.UUID.randomUUID().toString(),
                sourceBatchIdFilter,
                "N/A",
                result.getErrorCount() > 0 ? "failure" : "success",
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount());
        return result;
    }

    public void resetCanonicalBuildCheckpoints() {
        canonicalBuildCheckpointService.resetAll();
    }

    private void applySourceLinkReconcileSummary(
            ImportProcessingResult result,
            ScholardexSourceLinkService.ImportRepairSummary repairSummary
    ) {
        if (result == null || repairSummary == null) {
            return;
        }
        for (int i = 0; i < repairSummary.updated(); i++) {
            result.markUpdated();
        }
        for (int i = 0; i < repairSummary.skipped(); i++) {
            result.markSkipped("source-link-reconcile-skipped");
        }
        for (int i = 0; i < repairSummary.errors(); i++) {
            result.markError("source-link-reconcile-error");
        }
    }

    private ImportProcessingResult runStepWithTiming(String stepName, java.util.function.Supplier<ImportProcessingResult> supplier) {
        long startedAtNanos = System.nanoTime();
        log.info("Scopus step started: {}", stepName);
        ImportProcessingResult result = supplier.get();
        log.info("Scopus step completed: {} durationMs={} processed={} imported={} updated={} skipped={} errors={}",
                stepName,
                (System.nanoTime() - startedAtNanos) / 1_000_000L,
                result.getProcessedCount(),
                result.getImportedCount(),
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount());
        return result;
    }

    private void applyEdgeReconcileSummary(
            ImportProcessingResult result,
            ImportProcessingResult reconcileResult
    ) {
        if (result == null || reconcileResult == null) {
            return;
        }
        for (int i = 0; i < reconcileResult.getUpdatedCount(); i++) {
            result.markUpdated();
        }
        for (int i = 0; i < reconcileResult.getSkippedCount(); i++) {
            result.markSkipped("edge-reconcile-skipped");
        }
        for (int i = 0; i < reconcileResult.getErrorCount(); i++) {
            result.markError("edge-reconcile-error");
        }
    }

    public CanonicalResetResult resetCanonicalState() {
        long importEvents = mongoTemplate.count(scopusSourceQuery(), "scopus.import_events");
        long publicationFacts = publicationFactRepository.count();
        long citationFacts = citationFactRepository.count();
        long forumFacts = forumFactRepository.count();
        long authorFacts = authorFactRepository.count();
        long affiliationFacts = affiliationFactRepository.count();
        List<String> canonicalPublicationIds = findCanonicalIdsBySource("scholardex.publication_facts");
        List<String> canonicalCitationIds = findCanonicalIdsBySource("scholardex.citation_facts");
        List<String> canonicalAuthorIds = findCanonicalIdsBySource("scholardex.author_facts");
        List<String> canonicalAffiliationIds = findCanonicalIdsBySource("scholardex.affiliation_facts");
        List<String> canonicalForumIds = findCanonicalIdsBySource("scholardex.forum_facts");

        long canonicalPublicationFacts = canonicalPublicationIds.size();
        long canonicalCitationFacts = canonicalCitationIds.size();
        long canonicalAuthorFacts = canonicalAuthorIds.size();
        long canonicalAffiliationFacts = canonicalAffiliationIds.size();
        long canonicalForumFacts = canonicalForumIds.size();

        long publicationViews = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.scholardex_publication_view", Long.class);
        long canonicalAuthorViews = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.scholardex_author_view", Long.class);
        long canonicalAffiliationViews = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.scholardex_affiliation_view", Long.class);
        long canonicalForumViews = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reporting_read.scholardex_forum_view", Long.class);

        long sourceLinks = mongoTemplate.count(scopusSourceLinkQuery(), "scholardex.source_links");
        long identityConflicts = mongoTemplate.count(scopusIncomingSourceQuery(), ScholardexIdentityConflict.class);
        long authorshipFacts = mongoTemplate.count(scopusSourceQuery(), ScholardexAuthorshipFact.class);
        long authorAffiliationFacts = mongoTemplate.count(scopusSourceQuery(), ScholardexAuthorAffiliationFact.class);
        long publicationAuthorAffiliationFacts = mongoTemplate.count(scopusSourceQuery(), ScholardexPublicationAuthorAffiliationFact.class);
        long canonicalBuildCheckpoints = mongoTemplate.count(canonicalCheckpointQuery(), ScholardexCanonicalBuildCheckpoint.class);

        mongoTemplate.remove(scopusSourceQuery(), "scopus.import_events");

        affiliationFactRepository.deleteAll();
        authorFactRepository.deleteAll();
        forumFactRepository.deleteAll();
        citationFactRepository.deleteAll();
        publicationFactRepository.deleteAll();

        mongoTemplate.remove(scopusSourceQuery(), ScholardexAuthorshipFact.class);
        mongoTemplate.remove(scopusSourceQuery(), ScholardexAuthorAffiliationFact.class);
        mongoTemplate.remove(scopusSourceQuery(), ScholardexPublicationAuthorAffiliationFact.class);
        mongoTemplate.remove(scopusSourceQuery(), ScholardexCitationFact.class);
        mongoTemplate.remove(scopusSourceLinkQuery(), "scholardex.source_links");
        mongoTemplate.remove(scopusIncomingSourceQuery(), ScholardexIdentityConflict.class);
        canonicalBuildCheckpointService.resetAll();

        if (!canonicalPublicationIds.isEmpty()) {
            mongoTemplate.remove(
                    Query.query(Criteria.where("_id").in(canonicalPublicationIds)),
                    ScholardexPublicationFact.class
            );
        }
        if (!canonicalAuthorIds.isEmpty()) {
            mongoTemplate.remove(
                    Query.query(Criteria.where("_id").in(canonicalAuthorIds)),
                    ScholardexAuthorFact.class
            );
        }
        if (!canonicalAffiliationIds.isEmpty()) {
            mongoTemplate.remove(
                    Query.query(Criteria.where("_id").in(canonicalAffiliationIds)),
                    ScholardexAffiliationFact.class
            );
        }
        if (!canonicalForumIds.isEmpty()) {
            mongoTemplate.remove(
                    Query.query(Criteria.where("_id").in(canonicalForumIds)),
                    ScholardexForumFact.class
            );
        }

        return new CanonicalResetResult(
                importEvents,
                publicationFacts,
                citationFacts,
                forumFacts,
                authorFacts,
                affiliationFacts,
                canonicalPublicationFacts,
                canonicalCitationFacts,
                canonicalAuthorFacts,
                canonicalAffiliationFacts,
                canonicalForumFacts,
                publicationViews,
                canonicalAuthorViews,
                canonicalAffiliationViews,
                canonicalForumViews,
                sourceLinks,
                identityConflicts,
                authorshipFacts,
                authorAffiliationFacts,
                publicationAuthorAffiliationFacts,
                canonicalBuildCheckpoints
        );
    }

    private ImportProcessingResult combine(ImportProcessingResult... results) {
        ImportProcessingResult combined = new ImportProcessingResult(100);
        if (results == null || results.length == 0) {
            return combined;
        }
        int processed = 0;
        int imported = 0;
        int updated = 0;
        int skipped = 0;
        int errors = 0;
        Integer startBatch = null;
        Integer endBatch = null;
        int totalBatches = 0;
        int batchesProcessed = 0;
        Integer checkpointLastCompletedBatch = null;
        boolean resumedFromCheckpoint = false;

        for (ImportProcessingResult result : results) {
            if (result == null) {
                continue;
            }
            processed += result.getProcessedCount();
            imported += result.getImportedCount();
            updated += result.getUpdatedCount();
            skipped += result.getSkippedCount();
            errors += result.getErrorCount();
            if (result.getStartBatch() != null) {
                startBatch = startBatch == null ? result.getStartBatch() : Math.min(startBatch, result.getStartBatch());
            }
            if (result.getEndBatch() != null) {
                endBatch = endBatch == null ? result.getEndBatch() : Math.max(endBatch, result.getEndBatch());
            }
            if (result.getTotalBatches() != null) {
                totalBatches += result.getTotalBatches();
            }
            if (result.getBatchesProcessed() != null) {
                batchesProcessed += result.getBatchesProcessed();
            }
            if (result.getCheckpointLastCompletedBatch() != null) {
                checkpointLastCompletedBatch = checkpointLastCompletedBatch == null
                        ? result.getCheckpointLastCompletedBatch()
                        : Math.max(checkpointLastCompletedBatch, result.getCheckpointLastCompletedBatch());
            }
            resumedFromCheckpoint = resumedFromCheckpoint || Boolean.TRUE.equals(result.getResumedFromCheckpoint());
        }

        for (int i = 0; i < processed; i++) {
            combined.markProcessed();
        }
        for (int i = 0; i < imported; i++) {
            combined.markImported();
        }
        for (int i = 0; i < updated; i++) {
            combined.markUpdated();
        }
        for (int i = 0; i < skipped; i++) {
            combined.markSkipped("combined");
        }
        for (int i = 0; i < errors; i++) {
            combined.markError("combined");
        }
        combined.setStartBatch(startBatch);
        combined.setEndBatch(endBatch);
        combined.setTotalBatches(totalBatches == 0 ? null : totalBatches);
        combined.setBatchesProcessed(batchesProcessed == 0 ? null : batchesProcessed);
        combined.setCheckpointLastCompletedBatch(checkpointLastCompletedBatch);
        combined.setResumedFromCheckpoint(resumedFromCheckpoint);
        return combined;
    }

    private List<String> findCanonicalIdsBySource(String collectionName) {
        Query query = Query.query(scopusSourceCriteria());
        query.fields().include("_id");
        return mongoTemplate.find(query, org.bson.Document.class, collectionName).stream()
                .map(doc -> doc.get("_id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private Query scopusSourceQuery() {
        return Query.query(scopusSourceCriteria());
    }

    private Criteria scopusSourceCriteria() {
        return Criteria.where("source").regex("^SCOPUS");
    }

    private Query scopusSourceLinkQuery() {
        return Query.query(Criteria.where("source").regex("^SCOPUS"));
    }

    private Query scopusIncomingSourceQuery() {
        return Query.query(Criteria.where("incomingSource").regex("^SCOPUS"));
    }

    private Query canonicalCheckpointQuery() {
        return Query.query(Criteria.where("pipelineKey").in(
                ScholardexCanonicalBuildCheckpointService.PUBLICATION_PIPELINE_KEY,
                ScholardexCanonicalBuildCheckpointService.AUTHOR_PIPELINE_KEY,
                ScholardexCanonicalBuildCheckpointService.AFFILIATION_PIPELINE_KEY,
                ScholardexCanonicalBuildCheckpointService.CITATION_PIPELINE_KEY
        ));
    }

    public record CanonicalResetResult(
            long importEvents,
            long publicationFacts,
            long citationFacts,
            long forumFacts,
            long authorFacts,
            long affiliationFacts,
            long canonicalPublicationFacts,
            long canonicalCitationFacts,
            long canonicalAuthorFacts,
            long canonicalAffiliationFacts,
            long canonicalForumFacts,
            long publicationViews,
            long canonicalAuthorViews,
            long canonicalAffiliationViews,
            long canonicalForumViews,
            long sourceLinks,
            long identityConflicts,
            long authorshipFacts,
            long authorAffiliationFacts,
            long publicationAuthorAffiliationFacts,
            long canonicalBuildCheckpoints
    ) {
    }
}
