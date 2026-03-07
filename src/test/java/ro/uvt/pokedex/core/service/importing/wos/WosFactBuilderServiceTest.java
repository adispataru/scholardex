package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.model.IdentityResolutionResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentityResolutionStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosFactBuilderServiceTest {
    @Mock
    private WosImportEventParserOrchestrator parserOrchestrator;
    @Mock
    private WosIdentityResolutionService identityResolutionService;
    @Mock
    private WosMetricFactRepository metricFactRepository;
    @Mock
    private WosCategoryFactRepository categoryFactRepository;
    @Mock
    private WosFactConflictRepository factConflictRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private WosFactBuildCheckpointService checkpointService;

    private final List<WosMetricFact> metricStore = new ArrayList<>();
    private final List<WosCategoryFact> categoryStore = new ArrayList<>();
    private final List<WosFactConflict> conflictStore = new ArrayList<>();
    private final AtomicInteger idSeq = new AtomicInteger(1);
    private SimpleMeterRegistry meterRegistry;

    private WosFactBuilderService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new WosFactBuilderService(
                parserOrchestrator,
                identityResolutionService,
                metricFactRepository,
                categoryFactRepository,
                factConflictRepository,
                mongoTemplate,
                checkpointService,
                meterRegistry
        );
        lenient().when(identityResolutionService.resolveIdentity(anyString(), anyString(), anyString(), any()))
                .thenReturn(new IdentityResolutionResult("jid-1", "key", WosIdentityResolutionStatus.MATCHED, null));
        lenient().when(mongoTemplate.find(any(Query.class), eq(WosMetricFact.class)))
                .thenAnswer(invocation -> new ArrayList<>(metricStore));
        lenient().when(mongoTemplate.find(any(Query.class), eq(WosCategoryFact.class)))
                .thenAnswer(invocation -> new ArrayList<>(categoryStore));
        lenient().when(metricFactRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Iterable<WosMetricFact> iterable = (Iterable<WosMetricFact>) invocation.getArgument(0);
            List<WosMetricFact> saved = new ArrayList<>();
            for (WosMetricFact fact : iterable) {
                if (fact.getId() == null) {
                    fact.setId("m-" + idSeq.getAndIncrement());
                    metricStore.add(fact);
                }
                saved.add(fact);
            }
            return saved;
        });
        lenient().when(categoryFactRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Iterable<WosCategoryFact> iterable = (Iterable<WosCategoryFact>) invocation.getArgument(0);
            List<WosCategoryFact> saved = new ArrayList<>();
            for (WosCategoryFact fact : iterable) {
                if (fact.getId() == null) {
                    fact.setId("c-" + idSeq.getAndIncrement());
                    categoryStore.add(fact);
                }
                saved.add(fact);
            }
            return saved;
        });
        lenient().when(factConflictRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Iterable<WosFactConflict> iterable = (Iterable<WosFactConflict>) invocation.getArgument(0);
            List<WosFactConflict> saved = new ArrayList<>();
            for (WosFactConflict conflict : iterable) {
                conflictStore.add(conflict);
                saved.add(conflict);
            }
            return saved;
        });
    }

    @Test
    void sourcePrecedencePrefersGovForAis() {
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setEditionNormalized(EditionNormalized.SCIE);
        existing.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        existing.setValue(0.8);
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2023", "2");
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getUpdatedCount());
        assertEquals(1.1, existing.getValue());
        assertEquals(WosSourceType.GOV_AIS_RIS, existing.getSourceType());
        assertTrue(conflictStore.stream().anyMatch(c -> "METRIC".equals(c.getFactType())));
    }

    @Test
    void sameSourceUsesLatestLineage() {
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.RIS);
        existing.setEditionNormalized(EditionNormalized.UNKNOWN);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceVersion("v2022");
        existing.setSourceRowItem("10");
        existing.setValue(0.7);
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.RIS, WosSourceType.GOV_AIS_RIS, 0.9, "v2023", "1");
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getUpdatedCount());
        assertEquals(0.9, existing.getValue());
        assertEquals("v2023", existing.getSourceVersion());
    }

    @Test
    void replayIdempotencyKeepsFactsStable() {
        WosParsedRecord record = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.2, "v2024", "5");
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(record)));

        ImportProcessingResult first = service.buildFactsFromImportEvents();
        ImportProcessingResult second = service.buildFactsFromImportEvents();

        assertEquals(2, first.getImportedCount()); // metric + category
        assertEquals(2, second.getSkippedCount()); // metric unchanged + category unchanged
        assertEquals(1, metricStore.size());
        assertEquals(1, categoryStore.size());
    }

    @Test
    void logsLoserWhenIncomingNotPreferred() {
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setEditionNormalized(EditionNormalized.SCIE);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("3");
        existing.setValue(1.5);
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.OFFICIAL_WOS_EXTRACT, 0.6, "v2023", "9");
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(0, result.getUpdatedCount());
        assertTrue(result.getSkippedCount() > 0);
        verify(factConflictRepository).saveAll(any());
        assertEquals(1.5, existing.getValue());
    }

    @Test
    void ifFromGovSourceIsSkippedBySourcePolicy() {
        WosParsedRecord incoming = record(MetricType.IF, WosSourceType.GOV_AIS_RIS, 2.4, "v2023", "7");
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(0, result.getImportedCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, metricStore.size());
        assertEquals(0, categoryStore.size());
        assertTrue(result.getSkippedCount() > 0);
        assertEquals(1.0, meterRegistry.get("pokedex.wos.if.source_policy.skips").counter().count());
    }

    @Test
    void ifFromOfficialSourceIsAcceptedBySourcePolicy() {
        WosParsedRecord incoming = record(MetricType.IF, WosSourceType.OFFICIAL_WOS_EXTRACT, 2.4, "v2023", "7");
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(2, result.getImportedCount()); // metric + category
        assertEquals(0, result.getErrorCount());
        assertEquals(1, metricStore.size());
        assertEquals(1, categoryStore.size());
        assertEquals(0.0, meterRegistry.get("pokedex.wos.if.source_policy.skips").counter().count());
    }

    @Test
    void recordWithoutIssnAndEIssnIsSkippedForIdentityResolution() {
        WosParsedRecord incoming = new WosParsedRecord(
                "Title Only",
                null,
                null,
                2023,
                MetricType.AIS,
                1.2,
                "ACOUSTICS",
                "SCIE",
                EditionNormalized.SCIE,
                "Q1",
                2,
                "ev-1",
                WosSourceType.GOV_AIS_RIS,
                "file.xlsx",
                "v2023",
                "9"
        );
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(0, result.getImportedCount());
        assertTrue(result.getSkippedCount() > 0);
        verify(identityResolutionService, org.mockito.Mockito.never())
                .resolveIdentity(anyString(), anyString(), anyString(), any());
    }

    @Test
    void checkpointedBuildStartsFromCheckpointPlusOne() {
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(generateRecords(3000)));

        ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint checkpoint =
                new ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint();
        checkpoint.setPipelineKey(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY);
        checkpoint.setLastCompletedBatch(1);
        when(checkpointService.readCheckpoint()).thenReturn(java.util.Optional.of(checkpoint));

        WosFactBuilderService.FactBuildRunResult run =
                service.buildFactsFromImportEventsWithCheckpoint(null, true, "run-1", "v2023");

        assertEquals(2, run.startBatch());
        assertEquals(2, run.endBatch());
        assertEquals(1, run.batchesProcessed());
        verify(checkpointService).upsertCheckpoint(eq(2), anyInt(), anyString(), eq("run-1"), eq("v2023"));
    }

    @Test
    void checkpointedBuildUsesManualOverrideWhenProvided() {
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(generateRecords(2000)));

        WosFactBuilderService.FactBuildRunResult run =
                service.buildFactsFromImportEventsWithCheckpoint(1, true, "run-2", "v2023");

        assertEquals(1, run.startBatch());
        assertEquals(1, run.endBatch());
        assertEquals(1, run.batchesProcessed());
        assertTrue(!run.resumedFromCheckpoint());
        verify(checkpointService).upsertCheckpoint(eq(1), anyInt(), anyString(), eq("run-2"), eq("v2023"));
    }

    private WosParsedRecord record(MetricType metricType, WosSourceType sourceType, Double value, String sourceVersion, String sourceRowItem) {
        return new WosParsedRecord(
                "Journal",
                "12345678",
                "87654321",
                2023,
                metricType,
                value,
                "ACOUSTICS",
                metricType == MetricType.RIS ? null : "SCIE",
                metricType == MetricType.RIS ? EditionNormalized.UNKNOWN : EditionNormalized.SCIE,
                "Q1",
                2,
                "ev-1",
                sourceType,
                "file.xlsx",
                sourceVersion,
                sourceRowItem
        );
    }

    private WosParserRunResult runOf(List<WosParsedRecord> records) {
        WosParserRunSummary summary = new WosParserRunSummary(10);
        records.forEach(r -> {
            summary.markProcessed();
            summary.markParsed();
        });
        return new WosParserRunResult(summary, records);
    }

    private List<WosParsedRecord> generateRecords(int count) {
        List<WosParsedRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            records.add(record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0 + i, "v2023", Integer.toString(i)));
        }
        return records;
    }
}
