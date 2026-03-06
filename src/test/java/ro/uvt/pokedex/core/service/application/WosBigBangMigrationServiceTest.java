package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosBigBangMigrationServiceTest {

    @Mock private WosImportEventIngestionService ingestionService;
    @Mock private WosFactBuilderService factBuilderService;
    @Mock private WosProjectionBuilderService projectionBuilderService;
    @Mock private WosImportEventParserOrchestrator parserOrchestrator;
    @Mock private WosImportEventRepository importEventRepository;
    @Mock private WosJournalIdentityRepository journalIdentityRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private WosRankingViewRepository rankingViewRepository;
    @Mock private WosScoringViewRepository scoringViewRepository;

    private WosBigBangMigrationService service;

    @BeforeEach
    void setUp() {
        service = new WosBigBangMigrationService(
                ingestionService,
                factBuilderService,
                projectionBuilderService,
                parserOrchestrator,
                importEventRepository,
                journalIdentityRepository,
                metricFactRepository,
                categoryFactRepository,
                rankingViewRepository,
                scoringViewRepository
        );
        ReflectionTestUtils.setField(service, "migrationDataDirectory", "data/loaded");

        WosParserRunSummary summary = new WosParserRunSummary(20);
        summary.markProcessed();
        summary.markParsed();
        when(parserOrchestrator.parseAllEvents()).thenReturn(new WosParserRunResult(summary, List.of()));
        when(importEventRepository.count()).thenReturn(5L);
        when(journalIdentityRepository.count()).thenReturn(4L);
        when(metricFactRepository.count()).thenReturn(10L);
        when(categoryFactRepository.count()).thenReturn(8L);
        when(rankingViewRepository.count()).thenReturn(4L);
        when(scoringViewRepository.count()).thenReturn(8L);
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
        verify(ingestionService).previewDirectory("data/loaded", "v2026");
        verify(ingestionService, never()).ingestDirectory(anyString(), anyString());
        verify(factBuilderService, never()).buildFactsFromImportEvents();
        verify(projectionBuilderService, never()).rebuildWosProjections();
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
        when(factBuilderService.buildFactsFromImportEvents()).thenReturn(factResult);

        ImportProcessingResult projectionResult = new ImportProcessingResult(5);
        projectionResult.markProcessed();
        projectionResult.markImported();
        when(projectionBuilderService.rebuildWosProjections()).thenReturn(projectionResult);

        WosBigBangMigrationService.WosBigBangMigrationResult result = service.run(false, "v2026");

        assertFalse(result.dryRun());
        assertTrue(result.ingest().executed());
        assertTrue(result.buildFacts().executed());
        assertTrue(result.buildProjections().executed());
        verify(ingestionService).ingestDirectory("data/loaded", "v2026");
        verify(factBuilderService).buildFactsFromImportEvents();
        verify(projectionBuilderService).rebuildWosProjections();
    }
}

