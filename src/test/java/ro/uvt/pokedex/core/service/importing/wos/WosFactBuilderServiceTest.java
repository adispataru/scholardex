package ro.uvt.pokedex.core.service.importing.wos;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosFactBuilderServiceTest {

    @Mock private WosImportEventParserOrchestrator parserOrchestrator;
    @Mock private WosIdentityResolutionService identityResolutionService;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private WosFactConflictRepository factConflictRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private WosFactBuildCheckpointService checkpointService;

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
    void fullRecordWritesScoreAndCategoryFacts() {
        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.2, "v2024", "5", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(2, result.getImportedCount());
        assertEquals(1, metricStore.size());
        assertEquals(1, categoryStore.size());
        assertEquals(1.2, metricStore.getFirst().getValue());
        assertEquals("Q1", categoryStore.getFirst().getQuarter());
        assertEquals(2, categoryStore.getFirst().getRank());
    }

    @Test
    void missingCategorySkipsCategoryButWritesScore() {
        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.2, "v2024", "5", null, EditionNormalized.SCIE, null, null);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getImportedCount());
        assertTrue(result.getSkippedCount() > 0);
        assertEquals(1, metricStore.size());
        assertEquals(0, categoryStore.size());
    }

    @Test
    void sameScoreSkipsWithoutMetricConflict() {
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(1.1);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2024", "8", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(0, result.getUpdatedCount());
        assertTrue(result.getSkippedCount() > 0);
        assertTrue(conflictStore.stream().noneMatch(c -> "METRIC_SCORE".equals(c.getFactType())));
        verify(factConflictRepository, never()).saveAll(any());
    }

    @Test
    void incomingZeroKeepsExistingScoreWithoutConflict() {
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(2.5);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceFile("old.xlsx");
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 0.0, "v2024", "8", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(0, result.getUpdatedCount());
        assertTrue(result.getSkippedCount() > 0);
        assertEquals(2.5, existing.getValue());
        assertTrue(conflictStore.stream().noneMatch(c -> "METRIC_SCORE".equals(c.getFactType())));
    }

    @Test
    void sameFileDuplicateDifferentScoreSkipsWithoutConflict() {
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(1.1);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceFile("AIS_2023.xlsx");
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = new WosParsedRecord(
                "Journal",
                "12345678",
                "87654321",
                2023,
                MetricType.AIS,
                1.4,
                "ACOUSTICS",
                "SSCI",
                EditionNormalized.SSCI,
                "Q1",
                null,
                2,
                "ev-1",
                WosSourceType.GOV_AIS_RIS,
                "AIS_2023.xlsx",
                "v2023",
                "2"
        );
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(0, result.getUpdatedCount());
        assertTrue(result.getSkippedCount() > 0);
        assertEquals(1.1, existing.getValue());
        assertTrue(conflictStore.stream().noneMatch(c -> "METRIC_SCORE".equals(c.getFactType()) && "duplicate-source-file".equals(c.getConflictReason())));
    }

    @Test
    void differentScoreCreatesScoreConflict() {
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(0.8);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceVersion("v2022");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2023", "2", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getUpdatedCount());
        assertEquals(1.1, existing.getValue());
        assertTrue(conflictStore.stream().anyMatch(c -> "METRIC_SCORE".equals(c.getFactType())));
    }

    @Test
    void govSourcePrecedenceUpdateDoesNotEmitConflict() {
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(0.8);
        existing.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        existing.setSourceFile("old.xlsx");
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2023", "2", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getUpdatedCount());
        assertEquals(1.1, existing.getValue());
        assertTrue(conflictStore.isEmpty());
    }

    @Test
    void categoryMissingRankingIsEnrichedFromOfficialWithoutConflict() {
        WosCategoryFact existing = new WosCategoryFact();
        existing.setId("c-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setCategoryNameCanonical("ACOUSTICS");
        existing.setEditionNormalized(EditionNormalized.SCIE);
        existing.setQuarter(null);
        existing.setRank(null);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceFile("AIS_2023.xlsx");
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        categoryStore.add(existing);

        WosParsedRecord incoming = record(
                MetricType.AIS,
                WosSourceType.OFFICIAL_WOS_EXTRACT,
                1.2,
                "v2023",
                "77",
                "ACOUSTICS",
                EditionNormalized.SCIE,
                "Q2",
                13
        );
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getUpdatedCount());
        assertEquals("Q2", existing.getQuarter());
        assertEquals(13, existing.getRank());
        assertTrue(conflictStore.stream().noneMatch(c -> "CATEGORY_RANKING".equals(c.getFactType())));
    }

    @Test
    void categoryRankingTupleIncludesQuartileRank() {
        WosCategoryFact existing = new WosCategoryFact();
        existing.setId("c-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setCategoryNameCanonical("ACOUSTICS");
        existing.setEditionNormalized(EditionNormalized.SCIE);
        existing.setQuarter("Q1");
        existing.setQuartileRank(4);
        existing.setRank(20);
        existing.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        existing.setSourceFile("wos.json");
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        categoryStore.add(existing);

        WosParsedRecord incoming = record(
                MetricType.AIS,
                WosSourceType.OFFICIAL_WOS_EXTRACT,
                1.1,
                "v2023",
                "2",
                "ACOUSTICS",
                EditionNormalized.SCIE,
                "Q1",
                5,
                20
        );
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getUpdatedCount());
        assertEquals(5, existing.getQuartileRank());
        assertEquals(20, existing.getRank());
        assertTrue(conflictStore.stream().anyMatch(c -> "CATEGORY_RANKING".equals(c.getFactType())));
    }

    @Test
    void ifFromGovSourceIsSkippedBySourcePolicy() {
        WosParsedRecord incoming = record(MetricType.IF, WosSourceType.GOV_AIS_RIS, 2.4, "v2023", "7", "ACOUSTICS", EditionNormalized.SCIE, "Q2", 10);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(0, result.getImportedCount());
        assertEquals(0, metricStore.size());
        assertEquals(0, categoryStore.size());
        assertTrue(result.getSkippedCount() > 0);
        assertEquals(1.0, meterRegistry.get("pokedex.wos.if.source_policy.skips").counter().count());
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

    private WosParsedRecord record(
            MetricType metricType,
            WosSourceType sourceType,
            Double value,
            String sourceVersion,
            String sourceRowItem,
            String category,
            EditionNormalized edition,
            String quarter,
            Integer rank
    ) {
        return record(metricType, sourceType, value, sourceVersion, sourceRowItem, category, edition, quarter, null, rank);
    }

    private WosParsedRecord record(
            MetricType metricType,
            WosSourceType sourceType,
            Double value,
            String sourceVersion,
            String sourceRowItem,
            String category,
            EditionNormalized edition,
            String quarter,
            Integer quartileRank,
            Integer rank
    ) {
        return new WosParsedRecord(
                "Journal",
                "12345678",
                "87654321",
                2023,
                metricType,
                value,
                category,
                edition == null ? null : edition.name(),
                edition,
                quarter,
                quartileRank,
                rank,
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
            records.add(record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0 + i, "v2023", Integer.toString(i), "ACOUSTICS", EditionNormalized.SCIE, "Q1", 1));
        }
        return records;
    }
}
