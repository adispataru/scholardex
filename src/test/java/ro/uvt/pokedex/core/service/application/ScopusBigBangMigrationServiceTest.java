package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
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
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.util.List;
import org.bson.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusBigBangMigrationServiceTest {

    @Mock private ScopusDataService scopusDataService;
    @Mock private ScopusFactBuilderService scopusFactBuilderService;
    @Mock private ScholardexProjectionBuilderService scopusProjectionBuilderService;
    @Mock private ScopusCanonicalIndexMaintenanceService indexMaintenanceService;
    @Mock private ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    @Mock private ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    @Mock private ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    @Mock private ScholardexCitationCanonicalizationService citationCanonicalizationService;
    @Mock private ScholardexForumBuilder forumBuilder;
    @Mock private WosScholardexOnboardingService wosScholardexOnboardingService;
    @Mock private ScopusBuildSkipGateService scopusBuildSkipGateService;
    @Mock private ScholardexCanonicalBuildCheckpointService canonicalBuildCheckpointService;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexEdgeReconciliationService edgeReconciliationService;
    @Mock private ScopusImportEventRepository importEventRepository;
    @Mock private ScopusPublicationFactRepository publicationFactRepository;
    @Mock private ScopusCitationFactRepository citationFactRepository;
    @Mock private ScopusForumFactRepository forumFactRepository;
    @Mock private ScopusAuthorFactRepository authorFactRepository;
    @Mock private ScopusAffiliationFactRepository affiliationFactRepository;
    @Mock private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock private ScholardexCitationFactRepository scholardexCitationFactRepository;
    @Mock private ScholardexSourceLinkRepository scholardexSourceLinkRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private MongoTemplate mongoTemplate;

    private ScopusBigBangMigrationService service;

    @BeforeEach
    void setUp() {
        service = new ScopusBigBangMigrationService(
                scopusDataService,
                scopusFactBuilderService,
                scopusProjectionBuilderService,
                indexMaintenanceService,
                affiliationCanonicalizationService,
                authorCanonicalizationService,
                publicationCanonicalizationService,
                citationCanonicalizationService,
                forumBuilder,
                wosScholardexOnboardingService,
                scopusBuildSkipGateService,
                canonicalBuildCheckpointService,
                sourceLinkService,
                edgeReconciliationService,
                importEventRepository,
                publicationFactRepository,
                citationFactRepository,
                forumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                scholardexSourceLinkRepository,
                jdbcTemplate,
                mongoTemplate
        );
        ReflectionTestUtils.setField(service, "scopusDataFile", "/tmp/scopus.json");
        // H66B M8: publication→WoS links run after publication canon in the Scopus paths; empty by default.
        lenient().when(wosScholardexOnboardingService.linkPublicationsToWos(any(), any()))
                .thenReturn(result(0, 0, 0, 0, 0));
    }

    @Test
    void runBuildFactsStepSkipsWholePipelineWhenGateReportsInputsUnchanged() {
        when(scopusBuildSkipGateService.canSkipBuildFacts()).thenReturn(true);
        stubVerificationSummary();

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult out =
                service.runBuildFactsStep(null, true, null, true);

        assertEquals(false, out.buildFacts().executed());
        assertTrue(out.buildFacts().note().contains("inputs unchanged"));
        verify(scopusFactBuilderService, never()).buildFactsFromImportEvents();
        verify(affiliationCanonicalizationService, never()).rebuildCanonicalAffiliationFactsFromScopusFacts(any());
        verify(publicationCanonicalizationService, never()).rebuildCanonicalPublicationFactsFromScopusFacts(any());
        verify(scopusBuildSkipGateService, never()).recordBuildFactsSuccess();
    }

    @Test
    void runBuildFactsStepDoesNotConsultGateWithoutOptInAndRecordsSuccessOnCleanRun() {
        when(scopusFactBuilderService.buildFactsFromImportEvents()).thenReturn(result(1, 0, 1, 0, 0));
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 0));
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 0));
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 0));
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 0));
        when(forumBuilder.buildScopusForums(any(), any())).thenReturn(emptyForumBuild());
        stubVerificationSummary();

        service.runBuildFactsStep(null, true, null, false);

        verify(scopusBuildSkipGateService, never()).canSkipBuildFacts();
        verify(scopusBuildSkipGateService).recordBuildFactsSuccess();
    }

    @Test
    void runFullAggregatesStepAndVerificationSummaries() {
        ImportProcessingResult publications = result(10, 4, 0, 6, 0);
        ImportProcessingResult citations = result(8, 3, 0, 5, 0);
        ImportProcessingResult facts = result(18, 9, 5, 4, 0);
        ImportProcessingResult views = result(20, 20, 0, 0, 0);

        when(scopusDataService.importScopusDataSync("/tmp/scopus.json", 0, false)).thenReturn(publications);
        when(scopusDataService.importScopusDataCitationsSync("/tmp/scopus.json")).thenReturn(citations);
        when(scopusFactBuilderService.buildFactsFromImportEvents()).thenReturn(facts);
        when(scopusProjectionBuilderService.rebuildViews()).thenReturn(views);
        when(forumBuilder.buildScopusForums(any(), any())).thenReturn(emptyForumBuild());
        when(indexMaintenanceService.ensureIndexes()).thenReturn(
                new ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult(
                        List.of("c1"), List.of("p1"), List.of(), List.of()
                )
        );
        when(importEventRepository.count()).thenReturn(100L);
        when(publicationFactRepository.count()).thenReturn(50L);
        when(citationFactRepository.count()).thenReturn(80L);
        when(scholardexPublicationFactRepository.count()).thenReturn(55L);
        when(scholardexCitationFactRepository.count()).thenReturn(78L);
        when(forumFactRepository.count()).thenReturn(10L);
        when(authorFactRepository.count()).thenReturn(40L);
        when(affiliationFactRepository.count()).thenReturn(12L);
        when(scholardexSourceLinkRepository.count()).thenReturn(77L);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class)
        )).thenReturn(50L);

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult full = service.runFull();

        assertEquals(18, full.ingest().processed());
        assertEquals(7, full.ingest().imported());
        assertEquals(1, full.ensureIndexes().created());
        assertEquals(1, full.ensureIndexes().present());
        assertEquals(100L, full.verification().importEvents());
        assertEquals(78L, full.verification().canonicalCitationFacts());
        assertEquals(50L, full.verification().publicationViews());
    }

    @Test
    void incrementalUploadProjectionStepUsesBatchScopedProjectionBuilder() {
        ImportProcessingResult projections = result(3, 2, 1, 0, 0);
        when(scopusProjectionBuilderService.rebuildViewsForBatch("upload-batch-7")).thenReturn(projections);
        stubVerificationSummary();

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult run = service.runIncrementalUploadProjectionStep("upload-batch-7");

        assertEquals(3, run.buildProjections().processed());
        verify(scopusProjectionBuilderService).rebuildViewsForBatch("upload-batch-7");
        verify(scopusProjectionBuilderService, never()).rebuildViews();
    }

    @Test
    void incrementalUploadEdgeReconcileUsesBatchScopedEdgeService() {
        ImportProcessingResult edgeResult = result(2, 0, 1, 1, 0);
        when(edgeReconciliationService.reconcileEdges("upload-batch-7")).thenReturn(edgeResult);

        ImportProcessingResult run = service.runIncrementalUploadEdgeReconcileStep("upload-batch-7");

        assertEquals(1, run.getUpdatedCount());
        verify(edgeReconciliationService).reconcileEdges("upload-batch-7");
    }

    @Test
    void runIngestStepAggregatesPublicationAndCitationImport() {
        when(scopusDataService.importScopusDataSync("/tmp/scopus.json", 0, false)).thenReturn(result(5, 3, 1, 1, 0));
        when(scopusDataService.importScopusDataCitationsSync("/tmp/scopus.json")).thenReturn(result(4, 2, 0, 1, 1));
        stubVerificationSummary();

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult out = service.runIngestStep();

        assertEquals(9, out.ingest().processed());
        assertEquals(5, out.ingest().imported());
        assertEquals(1, out.ingest().updated());
        assertEquals(2, out.ingest().skipped());
        assertEquals(1, out.ingest().errors());
        assertNull(out.buildFacts());
    }

    @Test
    void runBuildFactsStepDefaultUsesAllCanonicalBuildersAndCheckpoint() {
        when(scopusFactBuilderService.buildFactsFromImportEvents()).thenReturn(result(2, 1, 1, 0, 0));
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(result(1, 1, 0, 0, 0));
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(result(1, 0, 1, 0, 0));
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(result(1, 1, 0, 0, 0));
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(result(1, 0, 0, 1, 0));
        when(forumBuilder.buildScopusForums(any(), any())).thenReturn(emptyForumBuild());
        stubVerificationSummary();

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult out = service.runBuildFactsStep();
        assertEquals(6, out.buildFacts().processed());
        assertEquals(3, out.buildFacts().imported());

        ArgumentCaptor<CanonicalBuildOptions> captor = ArgumentCaptor.forClass(CanonicalBuildOptions.class);
        verify(affiliationCanonicalizationService).rebuildCanonicalAffiliationFactsFromScopusFacts(captor.capture());
        CanonicalBuildOptions options = captor.getValue();
        assertTrue(options.useCheckpoint());
        assertNull(options.startBatchOverride());
    }

    @Test
    void runBuildFactsStepOverridePropagatesCanonicalBuildOptions() {
        when(scopusFactBuilderService.buildFactsFromImportEvents()).thenReturn(result(0, 0, 0, 0, 0));
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 0));
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 0));
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 0));
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 0));
        when(forumBuilder.buildScopusForums(any(), any())).thenReturn(emptyForumBuild());
        stubVerificationSummary();

        service.runBuildFactsStep(7, false, 33);

        ArgumentCaptor<CanonicalBuildOptions> captor = ArgumentCaptor.forClass(CanonicalBuildOptions.class);
        verify(publicationCanonicalizationService).rebuildCanonicalPublicationFactsFromScopusFacts(captor.capture());
        CanonicalBuildOptions options = captor.getValue();
        assertEquals(7, options.startBatchOverride());
        assertEquals(33, options.chunkSizeOverride());
        assertTrue(!options.useCheckpoint());
    }

    @Test
    void runIncrementalUploadBuildStepUsesBatchFilter() {
        when(scopusFactBuilderService.buildFactsFromImportEvents("batch-A")).thenReturn(result(1, 1, 0, 0, 0));
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(result(0, 0, 1, 0, 0));
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 1, 0));
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(result(0, 0, 0, 0, 1));
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(result(1, 0, 0, 0, 0));
        when(forumBuilder.buildScopusForums(any(), any())).thenReturn(emptyForumBuild());
        stubVerificationSummary();

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult out = service.runIncrementalUploadBuildStep("batch-A", 9);
        assertEquals(2, out.buildFacts().processed());
        assertEquals(1, out.buildFacts().errors());
        verify(scopusFactBuilderService).buildFactsFromImportEvents("batch-A");
    }

    @Test
    void runBuildProjectionsAndEnsureIndexesSteps() {
        when(scopusProjectionBuilderService.rebuildViews()).thenReturn(result(7, 6, 0, 1, 0));
        when(indexMaintenanceService.ensureIndexes()).thenReturn(
                new ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult(
                        List.of("idx1", "idx2"), List.of("idx3"), List.of("bad"), List.of("err")
                )
        );
        stubVerificationSummary();

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult projection = service.runBuildProjectionsStep();
        ScopusBigBangMigrationService.ScopusBigBangMigrationResult indexes = service.runEnsureIndexesStep();

        assertEquals(7, projection.buildProjections().processed());
        assertEquals(2, indexes.ensureIndexes().created());
        assertEquals(1, indexes.ensureIndexes().present());
        assertEquals(1, indexes.ensureIndexes().invalid());
        assertEquals(1, indexes.ensureIndexes().errors());
    }

    @Test
    void runCanonicalBuildStepAllWithReconcileSummaries() {
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(result(1, 0, 0, 0, 0));
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(result(1, 0, 1, 0, 0));
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(result(1, 1, 0, 0, 0));
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(result(1, 0, 0, 1, 0));
        when(sourceLinkService.reconcileLinks()).thenReturn(new ScholardexSourceLinkService.ImportRepairSummary(2, 1, 1));
        when(edgeReconciliationService.reconcileEdges()).thenReturn(result(0, 0, 3, 2, 1));

        ImportProcessingResult out = service.runCanonicalBuildStep("all", 3, true, 10, true, true);
        assertEquals(4, out.getProcessedCount());
        assertEquals(6, out.getUpdatedCount());
        assertEquals(4, out.getSkippedCount());
        assertEquals(2, out.getErrorCount());
    }

    @Test
    void runCanonicalBuildStepSpecificAndUnknownEntityBranches() {
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(result(2, 1, 0, 0, 0));
        when(sourceLinkService.reconcileLinks()).thenReturn(new ScholardexSourceLinkService.ImportRepairSummary(0, 0, 0));
        when(edgeReconciliationService.reconcileEdges()).thenReturn(result(0, 0, 0, 0, 0));

        ImportProcessingResult author = service.runCanonicalBuildStep(" author ", null, false, null, true, true);
        ImportProcessingResult unknown = service.runCanonicalBuildStep("weird", null, false, null, false, false);

        assertEquals(2, author.getProcessedCount());
        assertEquals(1, author.getImportedCount());
        assertEquals(1, unknown.getSkippedCount());
    }

    @Test
    void runSourceLinkAndEdgeReconcileAndCheckpointReset() {
        when(sourceLinkService.reconcileLinks()).thenReturn(new ScholardexSourceLinkService.ImportRepairSummary(4, 2, 1));
        when(edgeReconciliationService.reconcileEdges()).thenReturn(result(0, 0, 5, 1, 0));

        ScholardexSourceLinkService.ImportRepairSummary sl = service.runSourceLinkReconcileStep();
        ImportProcessingResult edge = service.runEdgeReconcileStep();
        service.resetCanonicalBuildCheckpoints();
        ImportProcessingResult citationBackfill = result(1, 0, 0, 0, 0);
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(citationBackfill);

        ImportProcessingResult backfillOut = service.runCitationIdentityBackfill();
        assertEquals(4, sl.updated());
        assertEquals(5, edge.getUpdatedCount());
        assertEquals(1, backfillOut.getProcessedCount());
        verify(canonicalBuildCheckpointService).resetAll();
    }

    @Test
    void resetCanonicalStateReturnsCountsAndInvokesDeletionPaths() {
        when(mongoTemplate.count(any(Query.class), eq("scopus.import_events"))).thenReturn(11L);
        when(publicationFactRepository.count()).thenReturn(12L);
        when(citationFactRepository.count()).thenReturn(13L);
        when(forumFactRepository.count()).thenReturn(14L);
        when(authorFactRepository.count()).thenReturn(15L);
        when(affiliationFactRepository.count()).thenReturn(16L);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM reporting_read.scholardex_publication_view"), eq(Long.class))).thenReturn(17L);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM reporting_read.scholardex_author_view"), eq(Long.class))).thenReturn(18L);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM reporting_read.scholardex_affiliation_view"), eq(Long.class))).thenReturn(19L);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM reporting_read.scholardex_forum_view"), eq(Long.class))).thenReturn(20L);
        when(mongoTemplate.count(any(Query.class), eq("scholardex.source_links"))).thenReturn(21L);
        when(mongoTemplate.count(any(Query.class), eq(ScholardexIdentityConflict.class))).thenReturn(22L);
        when(mongoTemplate.count(any(Query.class), eq(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact.class))).thenReturn(23L);
        when(mongoTemplate.count(any(Query.class), eq(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact.class))).thenReturn(24L);
        when(mongoTemplate.count(any(Query.class), eq(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact.class))).thenReturn(25L);
        when(mongoTemplate.count(any(Query.class), eq(ScholardexCanonicalBuildCheckpoint.class))).thenReturn(26L);

        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("scholardex.publication_facts")))
                .thenReturn(List.of(new Document("_id", "sp1")));
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("scholardex.citation_facts")))
                .thenReturn(List.of(new Document("_id", "sc1")));
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("scholardex.author_facts")))
                .thenReturn(List.of(new Document("_id", "sa1")));
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("scholardex.affiliation_facts")))
                .thenReturn(List.of(new Document("_id", "sf1")));
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("scholardex.forum_facts")))
                .thenReturn(List.of(new Document("_id", "sfo1")));

        ScopusBigBangMigrationService.CanonicalResetResult out = service.resetCanonicalState();

        assertEquals(11L, out.importEvents());
        assertEquals(12L, out.publicationFacts());
        assertEquals(1L, out.canonicalPublicationFacts());
        assertEquals(20L, out.canonicalForumViews());
        assertEquals(26L, out.canonicalBuildCheckpoints());
        verify(publicationFactRepository).deleteAll();
        verify(citationFactRepository).deleteAll();
        verify(authorFactRepository).deleteAll();
        verify(affiliationFactRepository).deleteAll();
        verify(forumFactRepository).deleteAll();
        verify(canonicalBuildCheckpointService).resetAll();
    }

    @Test
    void combineHandlesNullAndMergesBatchCheckpointMetadata() {
        ImportProcessingResult a = result(2, 1, 1, 0, 0);
        a.setStartBatch(5);
        a.setEndBatch(9);
        a.setTotalBatches(4);
        a.setBatchesProcessed(3);
        a.setCheckpointLastCompletedBatch(8);
        a.setResumedFromCheckpoint(false);

        ImportProcessingResult b = result(3, 2, 0, 1, 1);
        b.setStartBatch(2);
        b.setEndBatch(11);
        b.setTotalBatches(6);
        b.setBatchesProcessed(5);
        b.setCheckpointLastCompletedBatch(10);
        b.setResumedFromCheckpoint(true);

        ImportProcessingResult empty = ReflectionTestUtils.invokeMethod(service, "combine", (Object) null);
        assertEquals(0, empty.getProcessedCount());

        ImportProcessingResult combined = ReflectionTestUtils.invokeMethod(service, "combine", (Object) new ImportProcessingResult[]{a, null, b});
        assertEquals(5, combined.getProcessedCount());
        assertEquals(3, combined.getImportedCount());
        assertEquals(1, combined.getUpdatedCount());
        assertEquals(1, combined.getSkippedCount());
        assertEquals(1, combined.getErrorCount());
        assertEquals(2, combined.getStartBatch());
        assertEquals(11, combined.getEndBatch());
        assertEquals(10, combined.getTotalBatches());
        assertEquals(8, combined.getBatchesProcessed());
        assertEquals(10, combined.getCheckpointLastCompletedBatch());
        assertTrue(Boolean.TRUE.equals(combined.getResumedFromCheckpoint()));
    }

    @Test
    void applyReconcileSummaryHelpersHandleNullInputsAndApplyCounts() {
        ImportProcessingResult base = result(0, 0, 0, 0, 0);
        ImportProcessingResult edge = result(0, 0, 2, 1, 1);
        ScholardexSourceLinkService.ImportRepairSummary links = new ScholardexSourceLinkService.ImportRepairSummary(3, 2, 1);

        ReflectionTestUtils.invokeMethod(service, "applySourceLinkReconcileSummary", base, links);
        ReflectionTestUtils.invokeMethod(service, "applyEdgeReconcileSummary", base, edge);
        ReflectionTestUtils.invokeMethod(service, "applySourceLinkReconcileSummary", null, links);
        ReflectionTestUtils.invokeMethod(service, "applySourceLinkReconcileSummary", base, null);
        ReflectionTestUtils.invokeMethod(service, "applyEdgeReconcileSummary", null, edge);
        ReflectionTestUtils.invokeMethod(service, "applyEdgeReconcileSummary", base, null);

        assertEquals(5, base.getUpdatedCount());
        assertEquals(3, base.getSkippedCount());
        assertEquals(2, base.getErrorCount());
    }

    private ScholardexForumBuilder.ScopusForumBuildResult emptyForumBuild() {
        return new ScholardexForumBuilder.ScopusForumBuildResult(
                result(0, 0, 0, 0, 0), result(0, 0, 0, 0, 0), result(0, 0, 0, 0, 0),
                result(0, 0, 0, 0, 0), result(0, 0, 0, 0, 0), result(0, 0, 0, 0, 0), result(0, 0, 0, 0, 0));
    }

    private ImportProcessingResult result(int processed, int imported, int updated, int skipped, int errors) {
        ImportProcessingResult result = new ImportProcessingResult(10);
        for (int i = 0; i < processed; i++) {
            result.markProcessed();
        }
        for (int i = 0; i < imported; i++) {
            result.markImported();
        }
        for (int i = 0; i < updated; i++) {
            result.markUpdated();
        }
        for (int i = 0; i < skipped; i++) {
            result.markSkipped("s" + i);
        }
        for (int i = 0; i < errors; i++) {
            result.markError("e" + i);
        }
        return result;
    }

    private void stubVerificationSummary() {
        when(importEventRepository.count()).thenReturn(0L);
        when(publicationFactRepository.count()).thenReturn(0L);
        when(citationFactRepository.count()).thenReturn(0L);
        when(scholardexPublicationFactRepository.count()).thenReturn(0L);
        when(scholardexCitationFactRepository.count()).thenReturn(0L);
        when(forumFactRepository.count()).thenReturn(0L);
        when(authorFactRepository.count()).thenReturn(0L);
        when(affiliationFactRepository.count()).thenReturn(0L);
        when(scholardexSourceLinkRepository.count()).thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class)
        )).thenReturn(0L);
    }
}
