package ro.uvt.pokedex.core.service.application;

import com.mongodb.client.MongoCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosBigBangMigrationServiceTest {

    @Mock private WosImportEventIngestionService ingestionService;
    @Mock private WosFactBuilderService factBuilderService;
    @Mock private WosProjectionBuilderService projectionBuilderService;
    @Mock private WosParityReconciliationService parityReconciliationService;
    @Mock private WosImportEventParserOrchestrator parserOrchestrator;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private MongoCollection<org.bson.Document> legacyCategoryCollection;
    @Mock private WosImportEventRepository importEventRepository;
    @Mock private WosJournalIdentityRepository journalIdentityRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosIdentityConflictRepository identityConflictRepository;
    @Mock private WosFactConflictRepository factConflictRepository;
    @Mock private WosRankingViewRepository rankingViewRepository;
    @Mock private WosScoringViewRepository scoringViewRepository;

    private WosBigBangMigrationService service;

    @BeforeEach
    void setUp() {
        service = new WosBigBangMigrationService(
                ingestionService,
                factBuilderService,
                projectionBuilderService,
                parityReconciliationService,
                parserOrchestrator,
                mongoTemplate,
                importEventRepository,
                journalIdentityRepository,
                metricFactRepository,
                identityConflictRepository,
                factConflictRepository,
                rankingViewRepository,
                scoringViewRepository
        );
        ReflectionTestUtils.setField(service, "migrationDataDirectory", "data/loaded");

        WosParserRunSummary summary = new WosParserRunSummary(20);
        summary.markProcessed();
        summary.markParsed();
        lenient().when(parserOrchestrator.parseAllEvents()).thenReturn(new WosParserRunResult(summary, List.of()));
        lenient().when(importEventRepository.count()).thenReturn(5L);
        lenient().when(journalIdentityRepository.count()).thenReturn(4L);
        lenient().when(metricFactRepository.count()).thenReturn(10L);
        lenient().when(mongoTemplate.getCollection("wos.category_facts")).thenReturn(legacyCategoryCollection);
        lenient().when(legacyCategoryCollection.countDocuments()).thenReturn(8L);
        lenient().when(rankingViewRepository.count()).thenReturn(4L);
        lenient().when(scoringViewRepository.count()).thenReturn(8L);
        lenient().when(factBuilderService.readFactBuildCheckpoint()).thenReturn(Optional.empty());
        lenient().when(parityReconciliationService.runEligibilityCheck()).thenReturn(
                new WosParityReconciliationService.ParityReconciliationResult(
                        true, true, List.of("eligibility"), 0, 0, List.of()
                )
        );
        lenient().when(parityReconciliationService.runFullParity()).thenReturn(
                new WosParityReconciliationService.ParityReconciliationResult(
                        true, true, List.of("counts"), 0, 0, List.of()
                )
        );
    }

    @Test
    void dryRunUsesPreviewAndSkipsMutatingSteps() {
        when(ingestionService.previewDirectory(eq("data/loaded"), eq("v2026")))
                .thenReturn(new WosImportEventIngestionService.WosIngestionPreview(3, 100, 0, List.of("sample")));

        WosBigBangMigrationService.WosBigBangMigrationResult result = service.run(true, "v2026");

        assertTrue(result.dryRun());
        assertFalse(result.ingest().executed());
        assertFalse(result.buildFacts().executed());
        assertFalse(result.buildProjections().executed());
        assertFalse(result.verification().parityPassed());
        assertEquals(0, result.verification().parityMismatchCount());
        verify(ingestionService).previewDirectory("data/loaded", "v2026");
        verify(ingestionService, never()).ingestDirectory(anyString(), anyString());
        verify(factBuilderService, never()).buildFactsFromImportEventsWithCheckpoint(any(), anyBoolean(), any(), any());
        verify(projectionBuilderService, never()).rebuildWosProjections();
        verify(parityReconciliationService).runEligibilityCheck();
    }

    @Test
    void fullRunExecutesAllMutatingSteps() {
        ImportProcessingResult ingestResult = new ImportProcessingResult(5);
        ingestResult.markProcessed();
        ingestResult.markImported();
        when(ingestionService.ingestDirectory(eq("data/loaded"), eq("v2026"))).thenReturn(ingestResult);

        ImportProcessingResult factResult = new ImportProcessingResult(5);
        factResult.markProcessed();
        factResult.markUpdated();
        when(factBuilderService.buildFactsFromImportEventsWithCheckpoint(eq(null), eq(true), anyString(), eq("v2026")))
                .thenReturn(new WosFactBuilderService.FactBuildRunResult(
                        factResult,
                        0,
                        0,
                        1,
                        false,
                        -1
                ));

        ImportProcessingResult projectionResult = new ImportProcessingResult(5);
        projectionResult.markProcessed();
        projectionResult.markImported();
        when(projectionBuilderService.rebuildWosProjections()).thenReturn(projectionResult);

        WosBigBangMigrationService.WosBigBangMigrationResult result = service.run(false, "v2026");

        assertFalse(result.dryRun());
        assertTrue(result.ingest().executed());
        assertTrue(result.buildFacts().executed());
        assertTrue(result.buildProjections().executed());
        assertTrue(result.verification().parityPassed());
        assertEquals(0, result.verification().parityMismatchCount());
        verify(ingestionService).ingestDirectory("data/loaded", "v2026");
        verify(factBuilderService).buildFactsFromImportEventsWithCheckpoint(eq(null), eq(true), anyString(), eq("v2026"));
        verify(projectionBuilderService).rebuildWosProjections();
        verify(parityReconciliationService).runFullParity();
    }

    @Test
    void resetFactBuildCheckpointDelegatesToFactBuilderService() {
        service.resetFactBuildCheckpoint();

        verify(factBuilderService).resetFactBuildCheckpoint();
    }

    @Test
    void runBuildFactsStepDelegatesToCheckpointedFactBuilder() {
        ImportProcessingResult factResult = new ImportProcessingResult(5);
        factResult.markProcessed();
        factResult.markImported();
        when(factBuilderService.buildFactsFromImportEventsWithCheckpoint(eq(200), eq(true), anyString(), eq("v2026")))
                .thenReturn(new WosFactBuilderService.FactBuildRunResult(
                        factResult,
                        200,
                        202,
                        3,
                        true,
                        199
                ));

        WosBigBangMigrationService.MigrationStepResult result = service.runBuildFactsStep(200, "v2026", true);

        assertEquals("build-facts", result.stepName());
        assertEquals(200, result.startBatch());
        assertEquals(202, result.endBatch());
        assertEquals(3, result.batchesProcessed());
        verify(factBuilderService).buildFactsFromImportEventsWithCheckpoint(eq(200), eq(true), anyString(), eq("v2026"));
    }

    @Test
    void runIngestStepDelegatesToIngestionService() {
        ImportProcessingResult ingestResult = new ImportProcessingResult(5);
        ingestResult.markProcessed();
        ingestResult.markImported();
        when(ingestionService.ingestDirectory(eq("data/loaded"), eq("v2026"))).thenReturn(ingestResult);

        WosBigBangMigrationService.MigrationStepResult result = service.runIngestStep("v2026");

        assertEquals("ingest", result.stepName());
        assertEquals(1, result.processed());
        assertEquals(1, result.imported());
        verify(ingestionService).ingestDirectory("data/loaded", "v2026");
    }

    @Test
    void resetCanonicalStateClearsCollectionsAndCheckpoint() {
        when(importEventRepository.count()).thenReturn(11L);
        when(journalIdentityRepository.count()).thenReturn(9L);
        when(metricFactRepository.count()).thenReturn(7L);
        when(legacyCategoryCollection.countDocuments()).thenReturn(6L);
        when(identityConflictRepository.count()).thenReturn(3L);
        when(factConflictRepository.count()).thenReturn(2L);
        when(rankingViewRepository.count()).thenReturn(5L);
        when(scoringViewRepository.count()).thenReturn(4L);

        WosBigBangMigrationService.CanonicalResetResult result = service.resetCanonicalState();

        assertEquals(11L, result.importEvents());
        assertEquals(9L, result.journalIdentities());
        assertEquals(7L, result.metricFacts());
        assertEquals(6L, result.categoryFacts());
        assertEquals(3L, result.identityConflicts());
        assertEquals(2L, result.factConflicts());
        assertEquals(5L, result.rankingViewRows());
        assertEquals(4L, result.scoringViewRows());
        verify(scoringViewRepository).deleteAll();
        verify(rankingViewRepository).deleteAll();
        verify(factConflictRepository).deleteAll();
        verify(identityConflictRepository).deleteAll();
        verify(mongoTemplate).remove(any(org.springframework.data.mongodb.core.query.Query.class), eq(ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact.class));
        verify(metricFactRepository).deleteAll();
        verify(journalIdentityRepository).deleteAll();
        verify(importEventRepository).deleteAll();
        verify(factBuilderService).resetFactBuildCheckpoint();
    }
}
