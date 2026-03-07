package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock private WosImportEventRepository importEventRepository;
    @Mock private WosJournalIdentityRepository journalIdentityRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
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
                importEventRepository,
                journalIdentityRepository,
                metricFactRepository,
                categoryFactRepository,
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
        lenient().when(categoryFactRepository.count()).thenReturn(8L);
        lenient().when(rankingViewRepository.count()).thenReturn(4L);
        lenient().when(scoringViewRepository.count()).thenReturn(8L);
        lenient().when(factBuilderService.readFactBuildCheckpoint()).thenReturn(Optional.empty());
        lenient().when(parityReconciliationService.runEligibilityCheck()).thenReturn(
                new WosParityReconciliationService.ParityReconciliationResult(true, true, List.of("eligibility"), 0, 0, List.of())
        );
        lenient().when(parityReconciliationService.runFullParity()).thenReturn(
                new WosParityReconciliationService.ParityReconciliationResult(true, true, List.of("counts"), 0, 0, List.of())
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
        assertFalse(result.enrichCategoryRankings().executed());
        assertFalse(result.buildProjections().executed());
        assertFalse(result.verification().parityPassed());
        verify(ingestionService, never()).ingestDirectory(anyString(), anyString());
        verify(factBuilderService, never()).buildFactsFromImportEventsWithCheckpoint(any(), anyBoolean(), any(), any());
        verify(factBuilderService, never()).enrichMissingCategoryRankingFields();
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
                .thenReturn(new WosFactBuilderService.FactBuildRunResult(factResult, 0, 0, 1, false, -1));
        ImportProcessingResult enrichmentResult = new ImportProcessingResult(5);
        enrichmentResult.markProcessed();
        enrichmentResult.markUpdated();
        when(factBuilderService.enrichMissingCategoryRankingFields()).thenReturn(enrichmentResult);

        ImportProcessingResult projectionResult = new ImportProcessingResult(5);
        projectionResult.markProcessed();
        projectionResult.markImported();
        when(projectionBuilderService.rebuildWosProjections()).thenReturn(projectionResult);

        WosBigBangMigrationService.WosBigBangMigrationResult result = service.run(false, "v2026");

        assertFalse(result.dryRun());
        assertTrue(result.ingest().executed());
        assertTrue(result.buildFacts().executed());
        assertTrue(result.enrichCategoryRankings().executed());
        assertTrue(result.buildProjections().executed());
        assertTrue(result.verification().parityPassed());
        verify(factBuilderService).enrichMissingCategoryRankingFields();
        verify(parityReconciliationService).runFullParity();
    }

    @Test
    void resetCanonicalStateClearsCollectionsAndCheckpoint() {
        when(importEventRepository.count()).thenReturn(11L);
        when(journalIdentityRepository.count()).thenReturn(9L);
        when(metricFactRepository.count()).thenReturn(7L);
        when(categoryFactRepository.count()).thenReturn(6L);
        when(identityConflictRepository.count()).thenReturn(3L);
        when(factConflictRepository.count()).thenReturn(2L);
        when(rankingViewRepository.count()).thenReturn(5L);
        when(scoringViewRepository.count()).thenReturn(4L);

        WosBigBangMigrationService.CanonicalResetResult result = service.resetCanonicalState();

        assertEquals(6L, result.categoryFacts());
        verify(categoryFactRepository).deleteAll();
        verify(metricFactRepository).deleteAll();
        verify(factBuilderService).resetFactBuildCheckpoint();
    }

    @Test
    void runEnrichCategoryRankingsStepDelegatesToFactBuilder() {
        ImportProcessingResult enrichmentResult = new ImportProcessingResult(5);
        enrichmentResult.markProcessed();
        enrichmentResult.markUpdated();
        when(factBuilderService.enrichMissingCategoryRankingFields()).thenReturn(enrichmentResult);

        WosBigBangMigrationService.MigrationStepResult step = service.runEnrichCategoryRankingsStep();

        assertTrue(step.executed());
        assertEquals(1, step.processed());
        assertEquals(1, step.updated());
        verify(factBuilderService).enrichMissingCategoryRankingFields();
    }
}
