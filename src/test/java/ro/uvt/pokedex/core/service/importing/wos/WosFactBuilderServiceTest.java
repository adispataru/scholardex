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
import ro.uvt.pokedex.core.service.application.ImportRunMetricService;
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.model.IdentityResolutionResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentityResolutionStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentitySourceContext;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosFactBuilderServiceTest {

    @Mock private WosImportEventParserOrchestrator parserOrchestrator;
    @Mock private WosIdentityResolutionService identityResolutionService;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository coverageFactRepository;
    @Mock private WosFactConflictRepository factConflictRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private WosFactBuildCheckpointService checkpointService;
    @Mock private WosIndexMaintenanceService wosIndexMaintenanceService;
    @Mock private ImportRunMetricService importRunMetricService;

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
                new WosTitleAuthority(),
                metricFactRepository,
                categoryFactRepository,
                coverageFactRepository,
                factConflictRepository,
                mongoTemplate,
                checkpointService,
                wosIndexMaintenanceService,
                new WosOptimizationProperties(),
                meterRegistry
        );

        lenient().when(identityResolutionService.resolveIdentity(anyString(), anyString(), anyString(), any()))
                .thenReturn(new IdentityResolutionResult("jid-1", "key", WosIdentityResolutionStatus.MATCHED, null));
        lenient().when(identityResolutionService.findCandidatesByIssnTokenSets(any()))
                .thenReturn(java.util.Map.of());
        lenient().when(identityResolutionService.resolveIdentityWithPrefetchedCandidates(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new IdentityResolutionResult("jid-1", "key", WosIdentityResolutionStatus.MATCHED, null));

        lenient().when(mongoTemplate.find(any(Query.class), eq(WosMetricFact.class)))
                .thenAnswer(invocation -> new ArrayList<>(metricStore));
        lenient().when(mongoTemplate.find(any(Query.class), eq(WosCategoryFact.class)))
                .thenAnswer(invocation -> new ArrayList<>(categoryStore));
        lenient().when(metricFactRepository.findAll()).thenAnswer(invocation -> new ArrayList<>(metricStore));
        lenient().when(categoryFactRepository.findAll()).thenAnswer(invocation -> new ArrayList<>(categoryStore));
        lenient().when(metricFactRepository.findAllByJournalIdIn(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> journalIds = (List<String>) invocation.getArgument(0);
            return metricStore.stream()
                    .filter(fact -> journalIds.contains(fact.getJournalId()))
                    .toList();
        });
        lenient().when(categoryFactRepository.findAllByJournalIdIn(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> journalIds = (List<String>) invocation.getArgument(0);
            return categoryStore.stream()
                    .filter(fact -> journalIds.contains(fact.getJournalId()))
                    .toList();
        });
        lenient().when(categoryFactRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(any(), anyString(), anyString()))
                .thenAnswer(invocation -> categoryStore.stream()
                        .filter(fact -> fact.getSourceType() == invocation.getArgument(0))
                        .filter(fact -> java.util.Objects.equals(fact.getSourceFile(), invocation.getArgument(1)))
                        .filter(fact -> java.util.Objects.equals(fact.getSourceVersion(), invocation.getArgument(2)))
                        .toList());

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
        WosMetricFact metric = metricStore.getFirst();
        assertEquals(1.2, metric.getValue());
        assertEquals("jid-1", metric.getJournalId());
        assertEquals(MetricType.AIS, metric.getMetricType());
        assertEquals(2023, metric.getYear());
        assertEquals(WosSourceType.GOV_AIS_RIS, metric.getSourceType());
        assertEquals("ev-1", metric.getSourceEventId());
        assertEquals("file.xlsx", metric.getSourceFile());
        assertEquals("v2024", metric.getSourceVersion());
        assertEquals("5", metric.getSourceRowItem());
        assertNotNull(metric.getCreatedAt());

        WosCategoryFact category = categoryStore.getFirst();
        assertEquals("Q1", category.getQuarter());
        assertEquals(2, category.getRank());
        assertEquals("jid-1", category.getJournalId());
        assertEquals("ACOUSTICS", category.getCategoryNameCanonical());
        assertEquals(EditionNormalized.SCIE, category.getEditionNormalized());
        assertEquals(2023, category.getYear());
        assertEquals(WosSourceType.GOV_AIS_RIS, category.getSourceType());
        assertEquals("ev-1", category.getSourceEventId());
        assertEquals("file.xlsx", category.getSourceFile());
        assertEquals("v2024", category.getSourceVersion());
        assertEquals("5", category.getSourceRowItem());
        assertEquals("SCIE", category.getEditionRaw());
        assertNotNull(category.getCreatedAt());
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
        existing.setSourceEventId("ev-old");
        existing.setSourceFile("old-file.xlsx");
        existing.setSourceVersion("v2022");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2023", "2", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getUpdatedCount());
        assertEquals(1.1, existing.getValue());
        assertEquals(WosSourceType.GOV_AIS_RIS, existing.getSourceType());
        assertEquals("ev-1", existing.getSourceEventId());
        assertEquals("file.xlsx", existing.getSourceFile());
        assertEquals("v2023", existing.getSourceVersion());
        assertEquals("2", existing.getSourceRowItem());
        assertNotNull(existing.getCreatedAt());
        assertTrue(conflictStore.stream().anyMatch(c -> "METRIC_SCORE".equals(c.getFactType())));

        WosFactConflict conflict = conflictStore.stream()
                .filter(c -> "METRIC_SCORE".equals(c.getFactType())).findFirst().orElseThrow();
        assertEquals("METRIC_SCORE", conflict.getFactType());
        assertNotNull(conflict.getConflictReason());
        assertNotNull(conflict.getFactKey());
        assertEquals("1.1", conflict.getWinnerValueSnapshot());
        assertEquals("0.8", conflict.getLoserValueSnapshot());
        assertEquals("v2023", conflict.getWinnerSourceVersion());
        assertEquals("v2022", conflict.getLoserSourceVersion());
        assertEquals(WosSourceType.GOV_AIS_RIS, conflict.getWinnerSourceType());
        assertEquals(WosSourceType.GOV_AIS_RIS, conflict.getLoserSourceType());
        assertEquals("ev-1", conflict.getWinnerSourceEventId());
        assertEquals("file.xlsx", conflict.getWinnerSourceFile());
        assertEquals("2", conflict.getWinnerSourceRowItem());
        assertEquals("1", conflict.getLoserSourceRowItem());
        assertEquals("ev-old", conflict.getLoserSourceEventId());
        assertEquals("old-file.xlsx", conflict.getLoserSourceFile());
        assertNotNull(conflict.getDetectedAt());
    }

    @Test
    void latestLineageWinnerRecordsAuditOnlyMetric() {
        service = serviceWithImportRunMetrics();
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(0.8);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceEventId("ev-old");
        existing.setSourceFile("old-file.xlsx");
        existing.setSourceVersion("v2022");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2023", "2",
                "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        service.buildFactsFromImportEventsWithCheckpoint(null, false, "wos-run-1", null);

        verify(importRunMetricService).record(
                "wos-run-1",
                "GOV_AIS_RIS",
                "METRIC_SCORE",
                "deterministic-wos-fact-winner-latest-lineage",
                1
        );
    }

    @Test
    void sourcePrecedenceWinnerRecordsAuditMetricWithoutFactConflictRow() {
        service = serviceWithImportRunMetrics();
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(0.8);
        existing.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        existing.setSourceEventId("ev-official");
        existing.setSourceFile("official.json");
        existing.setSourceVersion("v2024");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2023", "2",
                "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        service.buildFactsFromImportEvents();

        verify(importRunMetricService).record(
                "v2023",
                "GOV_AIS_RIS",
                "METRIC_SCORE",
                "deterministic-wos-fact-winner-source-precedence",
                1
        );
        verify(factConflictRepository, never()).saveAll(any());
    }

    @Test
    void repeatedIdentityResolutionUsesRunLocalCache() {
        WosParsedRecord first = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "1", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 1);
        WosParsedRecord second = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2024", "2", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(first, second)));
        when(identityResolutionService.resolveIdentityWithPrefetchedCandidates(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new IdentityResolutionResult("jid-1", "key", WosIdentityResolutionStatus.MATCHED, null));

        service.buildFactsFromImportEvents();

        verify(identityResolutionService, times(1))
                .resolveIdentityWithPrefetchedCandidates(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void skipsCandidatePrefetchWhenChunkHasNoUnresolvedRecords() {
        WosParsedRecord first = recordWithIdentity("11112222", "33334444", MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "1");
        WosParsedRecord second = recordWithIdentity("11112222", "33334444", MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.1, "v2024", "2");
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(first, second)));
        when(identityResolutionService.findJournalIdsByIdentityKeys(any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Set<String> keys = invocation.getArgument(0);
                    return keys.stream().collect(java.util.stream.Collectors.toMap(k -> k, k -> "jid-1"));
                });

        service.buildFactsFromImportEvents();

        verify(identityResolutionService, never()).findCandidatesByIssnTokenSets(any());
        verify(identityResolutionService, never())
                .resolveIdentityWithPrefetchedCandidates(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void prefetchesCandidatesOnlyForUnresolvedTokenSets() {
        WosParsedRecord resolvedByIdentityKey = recordWithIdentity("11112222", "33334444", MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "1");
        WosParsedRecord unresolved = recordWithIdentity("55556666", "77778888", MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.2, "v2024", "2");
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(resolvedByIdentityKey, unresolved)));
        String resolvedIdentityKey = identityKeyFor("11112222", "33334444", 2023, EditionNormalized.SCIE.name());
        when(identityResolutionService.findJournalIdsByIdentityKeys(any()))
                .thenReturn(Map.of(resolvedIdentityKey, "jid-1"));

        service.buildFactsFromImportEvents();

        verify(identityResolutionService, times(1))
                .findCandidatesByIssnTokenSets(argThat(tokenSets ->
                        tokenSets.size() == 1
                                && tokenSets.containsKey("55556666|77778888")
                                && !tokenSets.containsKey("11112222|33334444")));
    }

    @Test
    void govCategorySourcePrecedenceKeepsExistingWithoutConflict() {
        WosCategoryFact existing = new WosCategoryFact();
        existing.setId("c-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setCategoryNameCanonical("ACOUSTICS");
        existing.setEditionNormalized(EditionNormalized.SCIE);
        existing.setQuarter("Q1");
        existing.setRank(5);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceFile("AIS_2023.xlsx");
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        categoryStore.add(existing);

        WosParsedRecord incoming = record(
                MetricType.AIS, WosSourceType.OFFICIAL_WOS_EXTRACT, 1.2, "v2023", "77",
                "ACOUSTICS", EditionNormalized.SCIE, "Q2", 10
        );
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertTrue(result.getSkippedCount() > 0);
        assertEquals(0, result.getUpdatedCount());
        assertEquals("Q1", existing.getQuarter());
        assertEquals(5, existing.getRank());
        assertTrue(conflictStore.stream().noneMatch(c -> "CATEGORY_RANKING".equals(c.getFactType())));
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
        assertEquals(WosSourceType.GOV_AIS_RIS, existing.getSourceType());
        assertEquals("ev-1", existing.getSourceEventId());
        assertEquals("file.xlsx", existing.getSourceFile());
        assertEquals("v2023", existing.getSourceVersion());
        assertEquals("2", existing.getSourceRowItem());
        assertNotNull(existing.getCreatedAt());
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
                "v2024",
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
        assertEquals(WosSourceType.OFFICIAL_WOS_EXTRACT, existing.getSourceType());
        assertEquals("ev-1", existing.getSourceEventId());
        assertEquals("file.xlsx", existing.getSourceFile());
        assertEquals("v2024", existing.getSourceVersion());
        assertEquals("77", existing.getSourceRowItem());
        assertEquals("SCIE", existing.getEditionRaw());
        assertNotNull(existing.getCreatedAt());
        assertTrue(conflictStore.stream().noneMatch(c -> "CATEGORY_RANKING".equals(c.getFactType())));
    }

    @Test
    void identicalCategoryRankingSkipsWithoutConflict() {
        WosCategoryFact existing = new WosCategoryFact();
        existing.setId("c-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setCategoryNameCanonical("ACOUSTICS");
        existing.setEditionNormalized(EditionNormalized.SCIE);
        existing.setQuarter("Q2");
        existing.setQuartileRank(3);
        existing.setRank(7);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("1");
        categoryStore.add(existing);

        WosParsedRecord incoming = record(
                MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "2",
                "ACOUSTICS", EditionNormalized.SCIE, "Q2", 3, 7
        );
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertTrue(result.getSkippedCount() > 0);
        assertEquals(0, result.getUpdatedCount());
        assertTrue(conflictStore.isEmpty());
        assertEquals("Q2", existing.getQuarter());
        assertEquals(3, existing.getQuartileRank());
        assertEquals(7, existing.getRank());
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
        existing.setSourceEventId("ev-old-cat");
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
        assertEquals(WosSourceType.OFFICIAL_WOS_EXTRACT, existing.getSourceType());
        assertEquals("ev-1", existing.getSourceEventId());
        assertEquals("file.xlsx", existing.getSourceFile());
        assertEquals("v2023", existing.getSourceVersion());
        assertEquals("2", existing.getSourceRowItem());
        assertEquals("SCIE", existing.getEditionRaw());
        assertNotNull(existing.getCreatedAt());
        assertTrue(conflictStore.stream().anyMatch(c -> "CATEGORY_RANKING".equals(c.getFactType())));

        WosFactConflict catConflict = conflictStore.stream()
                .filter(c -> "CATEGORY_RANKING".equals(c.getFactType())).findFirst().orElseThrow();
        assertNotNull(catConflict.getConflictReason());
        assertNotNull(catConflict.getFactKey());
        assertEquals("ACOUSTICS|SCIE|Q1|5|20", catConflict.getWinnerValueSnapshot());
        assertEquals("ACOUSTICS|SCIE|Q1|4|20", catConflict.getLoserValueSnapshot());
        assertEquals("v2023", catConflict.getWinnerSourceVersion());
        assertEquals("v2023", catConflict.getLoserSourceVersion());
        assertEquals(WosSourceType.OFFICIAL_WOS_EXTRACT, catConflict.getWinnerSourceType());
        assertEquals(WosSourceType.OFFICIAL_WOS_EXTRACT, catConflict.getLoserSourceType());
        assertEquals("ev-1", catConflict.getWinnerSourceEventId());
        assertEquals("file.xlsx", catConflict.getWinnerSourceFile());
        assertEquals("2", catConflict.getWinnerSourceRowItem());
        assertEquals("1", catConflict.getLoserSourceRowItem());
        assertEquals("ev-old-cat", catConflict.getLoserSourceEventId());
        assertEquals("wos.json", catConflict.getLoserSourceFile());
        assertNotNull(catConflict.getDetectedAt());
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
    void enrichMissingCategoryRankingFieldsComputesAndPreservesSourceQuarter() {
        metricStore.add(metric("jid-1", 2024, MetricType.AIS, 9.0));
        metricStore.add(metric("jid-2", 2024, MetricType.AIS, 9.0));
        metricStore.add(metric("jid-3", 2024, MetricType.AIS, 7.5));

        WosCategoryFact c1 = category("c1", "jid-1", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact c2 = category("c2", "jid-2", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, "Q4", null, null);
        WosCategoryFact c3 = category("c3", "jid-3", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, "Q3", 1, 3);
        categoryStore.addAll(List.of(c1, c2, c3));

        ImportProcessingResult result = service.enrichMissingCategoryRankingFields();

        assertEquals(3, result.getProcessedCount());
        assertEquals(2, result.getUpdatedCount());

        assertEquals(1, c1.getRank());
        assertEquals("Q1", c1.getQuarter());
        assertEquals(1, c1.getQuartileRank());

        assertEquals(1, c2.getRank());
        assertEquals("Q4", c2.getQuarter());
        assertEquals(1, c2.getQuartileRank());

        assertEquals(3, c3.getRank());
        assertEquals("Q3", c3.getQuarter());
        assertEquals(1, c3.getQuartileRank());
    }

    @Test
    void computedQuarterCorrectlyAssignsAllFourQuartersForFourFacts() {
        // computedQuarterByFactId: n=4, q1End=1, q2End=2, q3End=3 → kills arithmetic mutations
        metricStore.add(metric("jid-q1", 2024, MetricType.AIS, 4.0));
        metricStore.add(metric("jid-q2", 2024, MetricType.AIS, 3.0));
        metricStore.add(metric("jid-q3", 2024, MetricType.AIS, 2.0));
        metricStore.add(metric("jid-q4", 2024, MetricType.AIS, 1.0));

        WosCategoryFact c1 = category("cq1", "jid-q1", 2024, MetricType.AIS, "PHYSICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact c2 = category("cq2", "jid-q2", 2024, MetricType.AIS, "PHYSICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact c3 = category("cq3", "jid-q3", 2024, MetricType.AIS, "PHYSICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact c4 = category("cq4", "jid-q4", 2024, MetricType.AIS, "PHYSICS", EditionNormalized.SCIE, null, null, null);
        categoryStore.addAll(List.of(c1, c2, c3, c4));

        service.enrichMissingCategoryRankingFields();

        assertEquals("Q1", c1.getQuarter());
        assertEquals("Q2", c2.getQuarter());
        assertEquals("Q3", c3.getQuarter());
        assertEquals("Q4", c4.getQuarter());
    }

    @Test
    void enrichMissingCategoryRankingFieldsSkipsWhenMetricValueIsMissing() {
        WosCategoryFact c1 = category("c1", "jid-1", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        categoryStore.add(c1);

        ImportProcessingResult result = service.enrichMissingCategoryRankingFields();

        assertEquals(1, result.getProcessedCount());
        assertEquals(0, result.getUpdatedCount());
        assertTrue(result.getSkippedCount() > 0);
        assertEquals(null, c1.getRank());
        assertEquals(null, c1.getQuarter());
        assertEquals(null, c1.getQuartileRank());
    }

    @Test
    void scopedEnrichmentUpdatesOnlyFactsFromStoredUploadLineage() {
        metricStore.add(metric("jid-1", 2024, MetricType.AIS, 9.0));
        metricStore.add(metric("jid-2", 2024, MetricType.AIS, 7.0));

        WosCategoryFact target = category("c1", "jid-1", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        target.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        target.setSourceFile("wos.json");
        target.setSourceVersion("2026-Q1");

        WosCategoryFact unrelatedSameJournal = category("c2", "jid-1", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        unrelatedSameJournal.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        unrelatedSameJournal.setSourceFile("older.json");
        unrelatedSameJournal.setSourceVersion("2025-Q4");

        WosCategoryFact peerInAffectedSlice = category("c3", "jid-2", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        peerInAffectedSlice.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        peerInAffectedSlice.setSourceFile("wos.json");
        peerInAffectedSlice.setSourceVersion("2026-Q1");

        categoryStore.addAll(List.of(target, unrelatedSameJournal, peerInAffectedSlice));

        ImportProcessingResult result = service.enrichMissingCategoryRankingFieldsForSource(
                WosSourceType.OFFICIAL_WOS_EXTRACT,
                "wos.json",
                "2026-Q1"
        );

        assertEquals(2, result.getProcessedCount());
        assertEquals(2, result.getUpdatedCount());
        assertEquals(1, target.getRank());
        assertEquals(3, peerInAffectedSlice.getRank());
        assertEquals(null, unrelatedSameJournal.getRank());
        verify(categoryFactRepository, times(1)).saveAll(argThat(facts -> {
            List<String> ids = new ArrayList<>();
            for (WosCategoryFact fact : facts) {
                ids.add(fact.getId());
            }
            return ids.contains("c1") && ids.contains("c3") && !ids.contains("c2");
        }));
    }

    @Test
    void scopedFactBuildParsesOnlyRequestedLineage() {
        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.OFFICIAL_WOS_EXTRACT, 1.2, "2026-Q1", "1", "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseSourceLineage(WosSourceType.OFFICIAL_WOS_EXTRACT, "wos.json", "2026-Q1"))
                .thenReturn(runOf(List.of(incoming)));

        WosFactBuilderService.FactBuildRunResult result = service.buildFactsFromImportEventsForSource(
                WosSourceType.OFFICIAL_WOS_EXTRACT,
                "wos.json",
                "2026-Q1"
        );

        assertEquals(2, result.result().getImportedCount());
        verify(parserOrchestrator).parseSourceLineage(WosSourceType.OFFICIAL_WOS_EXTRACT, "wos.json", "2026-Q1");
        verify(parserOrchestrator, never()).parseAllEvents();
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
    void categoryFactPreservesMetricTypeAndQuartileRankFromRecord() {
        // toCategoryFact L1074 (setMetricType) and L1079 (setQuartileRank): both removed-call mutations
        // survived because no test previously asserted these fields on the saved category fact
        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.5, "v2024", "3",
                "ACOUSTICS", EditionNormalized.SCIE, "Q2", 2, 3);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        service.buildFactsFromImportEvents();

        WosCategoryFact cat = categoryStore.getFirst();
        assertEquals(MetricType.AIS, cat.getMetricType());  // kills L1074
        assertEquals(2, cat.getQuartileRank());              // kills L1079
    }

    @Test
    void existingCategoryWinsConflictEmitsCategoryConflictRecord() {
        // Triggers buildCategoryConflictExistingWinner — covers all 17 NO_COVERAGE mutations
        // Existing wins because its sourceVersion (v2024) > incoming (v2023)
        WosCategoryFact existing = new WosCategoryFact();
        existing.setId("c-existing");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setCategoryNameCanonical("ACOUSTICS");
        existing.setEditionNormalized(EditionNormalized.SCIE);
        existing.setEditionRaw("SCIE");
        existing.setQuarter("Q1");
        existing.setRank(1);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceEventId("ev-old");
        existing.setSourceFile("old.xlsx");
        existing.setSourceVersion("v2024");
        existing.setSourceRowItem("5");
        categoryStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 2.0, "v2023", "1",
                "ACOUSTICS", EditionNormalized.SCIE, "Q2", 5);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        service.buildFactsFromImportEvents();

        assertEquals("Q1", existing.getQuarter());
        assertEquals(1, existing.getRank());

        WosFactConflict conflict = conflictStore.stream()
                .filter(c -> "CATEGORY_RANKING".equals(c.getFactType())).findFirst().orElseThrow();
        assertNotNull(conflict.getConflictReason());
        assertNotNull(conflict.getFactKey());
        assertEquals(WosSourceType.GOV_AIS_RIS, conflict.getWinnerSourceType());
        assertEquals("ev-old", conflict.getWinnerSourceEventId());
        assertEquals("old.xlsx", conflict.getWinnerSourceFile());
        assertEquals("v2024", conflict.getWinnerSourceVersion());
        assertEquals("5", conflict.getWinnerSourceRowItem());
        assertNotNull(conflict.getWinnerValueSnapshot());
        assertEquals(WosSourceType.GOV_AIS_RIS, conflict.getLoserSourceType());
        assertEquals("ev-1", conflict.getLoserSourceEventId());
        assertEquals("file.xlsx", conflict.getLoserSourceFile());
        assertEquals("v2023", conflict.getLoserSourceVersion());
        assertEquals("1", conflict.getLoserSourceRowItem());
        assertNotNull(conflict.getLoserValueSnapshot());
        assertNotNull(conflict.getDetectedAt());
    }

    @Test
    void enrichMissingCategoryRankingFieldsAssignsQuartersByMetricRankAndSetsCreatedAt() {
        // 4 facts in same group (ACOUSTICS/AIS/2023/SCIE), no rank/quarter set
        // After enrichment: highest metric value → Q1, lowest → Q4
        // Kills L1319 (list.sort removal scrambles quarter assignment)
        //       L1364 (setCreatedAt removal leaves createdAt null)
        //       L1447 (normalizeQuarter null-check removal → NPE on null quarter input)
        metricStore.add(metric("jid-1", 2023, MetricType.AIS, 4.0));
        metricStore.add(metric("jid-2", 2023, MetricType.AIS, 3.0));
        metricStore.add(metric("jid-3", 2023, MetricType.AIS, 2.0));
        metricStore.add(metric("jid-4", 2023, MetricType.AIS, 1.0));

        WosCategoryFact c1 = category("cf-1", "jid-1", 2023, MetricType.AIS, "ACOUSTICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact c4 = category("cf-4", "jid-4", 2023, MetricType.AIS, "ACOUSTICS", EditionNormalized.SCIE, null, null, null);
        categoryStore.add(c1);
        categoryStore.add(category("cf-2", "jid-2", 2023, MetricType.AIS, "ACOUSTICS", EditionNormalized.SCIE, null, null, null));
        categoryStore.add(category("cf-3", "jid-3", 2023, MetricType.AIS, "ACOUSTICS", EditionNormalized.SCIE, null, null, null));
        categoryStore.add(c4);

        ImportProcessingResult result = service.enrichMissingCategoryRankingFields();

        assertTrue(result.getUpdatedCount() > 0);
        assertEquals("Q1", c1.getQuarter());    // highest metric → Q1; kills L1319
        assertEquals("Q4", c4.getQuarter());    // lowest metric → Q4; kills L1319
        assertNotNull(c1.getCreatedAt());        // kills L1364
        assertNotNull(c1.getQuartileRank());     // kills L1447 "replaced return value with """
    }

    @Test
    void enrichMissingCategoryRankingFieldsDoesNotEnrichAlreadyCompleteCategory() {
        // requiresCategoryRankingEnrichment L1439 "replaced boolean return with true":
        // if always true, factB (value 4.0, already complete) enters the group alongside factA,
        // pushing factA from Q1 (sole entry) to Q3 (position 2 in 2-entry group).
        metricStore.add(metric("jid-A", 2023, MetricType.AIS, 2.0));
        metricStore.add(metric("jid-B", 2023, MetricType.AIS, 4.0));

        WosCategoryFact factA = category("cf-A", "jid-A", 2023, MetricType.AIS, "ACOUSTICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact factB = category("cf-B", "jid-B", 2023, MetricType.AIS, "ACOUSTICS", EditionNormalized.SCIE, "Q1", 1, 1);
        categoryStore.add(factA);
        categoryStore.add(factB);

        service.enrichMissingCategoryRankingFields();

        assertEquals("Q1", factA.getQuarter()); // sole group member → Q1; mutant gives Q3
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

    @Test
    void existingNewerVersionKeepsExistingOverIncomingWithHigherRowItem() {
        // compareLineage line 984: version must take precedence over rowItem
        // existingVersion v2024 > incomingVersion v2023 → existing wins despite incoming having higher rowItem
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-older");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(1.0);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceEventId("ev-existing");
        existing.setSourceFile("existing-file.xlsx");
        existing.setSourceVersion("v2024");
        existing.setSourceRowItem("1");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 2.0, "v2023", "99",
                "ACOUSTICS", EditionNormalized.SCIE, "Q1", 2);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        service.buildFactsFromImportEvents();

        // Existing (v2024, row 1) beats incoming (v2023, row 99) by version priority
        assertEquals(1.0, existing.getValue());
        assertEquals(0, metricStore.stream().filter(m -> m.getValue() == 2.0).count());

        // buildMetricConflictExistingWinner emits a conflict even when existing wins
        assertTrue(conflictStore.stream().anyMatch(c -> "METRIC_SCORE".equals(c.getFactType())));
        WosFactConflict conflict = conflictStore.stream()
                .filter(c -> "METRIC_SCORE".equals(c.getFactType())).findFirst().orElseThrow();
        assertNotNull(conflict.getConflictReason());
        assertNotNull(conflict.getFactKey());
        assertEquals(WosSourceType.GOV_AIS_RIS, conflict.getWinnerSourceType());
        assertEquals("ev-existing", conflict.getWinnerSourceEventId());
        assertEquals("existing-file.xlsx", conflict.getWinnerSourceFile());
        assertEquals("v2024", conflict.getWinnerSourceVersion());
        assertEquals("1", conflict.getWinnerSourceRowItem());
        assertEquals("1.0", conflict.getWinnerValueSnapshot());
        assertEquals("ev-1", conflict.getLoserSourceEventId());
        assertEquals("file.xlsx", conflict.getLoserSourceFile());
        assertEquals(WosSourceType.GOV_AIS_RIS, conflict.getLoserSourceType());
        assertEquals("v2023", conflict.getLoserSourceVersion());
        assertEquals("99", conflict.getLoserSourceRowItem());
        assertEquals("2.0", conflict.getLoserValueSnapshot());
        assertNotNull(conflict.getDetectedAt());
    }

    @Test
    void sameVersionHigherRowItemNumericIncomingOverridesExisting() {
        // compareRowItem line 993: numeric comparison must beat lexicographic ("10" > "9" but "10" < "9" as string)
        WosMetricFact existing = new WosMetricFact();
        existing.setId("m-low-row");
        existing.setJournalId("jid-1");
        existing.setYear(2023);
        existing.setMetricType(MetricType.AIS);
        existing.setValue(1.0);
        existing.setSourceType(WosSourceType.GOV_AIS_RIS);
        existing.setSourceVersion("v2023");
        existing.setSourceRowItem("9");
        metricStore.add(existing);

        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 2.0, "v2023", "10",
                "ACOUSTICS", EditionNormalized.SCIE, "Q1", 1);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        service.buildFactsFromImportEvents();

        assertEquals(2.0, existing.getValue());
    }

    @Test
    void nullEditionNormalizedSkipsCategoryFact() {
        // categoryKey line 934: null editionNormalized check must prevent category creation
        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "1",
                "ACOUSTICS", null, null, null);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        service.buildFactsFromImportEvents();

        assertEquals(0, categoryStore.size());
    }

    @Test
    void blankCategoryNameSkipsCategoryFact() {
        // categoryKey line 937: blank categoryNameCanonical must prevent category creation
        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "1",
                "  ", EditionNormalized.SCIE, null, null);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming)));

        service.buildFactsFromImportEvents();

        assertEquals(0, categoryStore.size());
    }

    @Test
    void categoryRankingKeyStringIsNonEmpty() {
        // categoryRankingKeyString line 1209: must return non-empty key (mutation returns "")
        WosParsedRecord incoming = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "1",
                "ACOUSTICS", EditionNormalized.SCIE, "Q1", 1);
        WosParsedRecord incoming2 = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 2.0, "v2024", "2",
                "ACOUSTICS", EditionNormalized.SCIE, "Q1", 1);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(incoming, incoming2)));

        service.buildFactsFromImportEvents();

        // If categoryRankingKeyString returned "", both records would map to the same key ""
        // and the second would conflict with the first. With a proper key, they map to the same
        // journal/category and only 1 category fact should exist.
        assertEquals(1, categoryStore.size());
    }

    @Test
    void distinctCategoryNameProducesSeparateCategoryFacts() {
        // categoryRankingKeyString L1209: key must include category name so that distinct categories produce distinct facts
        WosParsedRecord acoustics = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "1",
                "ACOUSTICS", EditionNormalized.SCIE, "Q1", 1);
        WosParsedRecord physics = record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0, "v2024", "2",
                "PHYSICS", EditionNormalized.SCIE, "Q1", 1);
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(acoustics, physics)));

        service.buildFactsFromImportEvents();

        // Two different categories → two separate category facts
        assertEquals(2, categoryStore.size());
        assertTrue(categoryStore.stream().anyMatch(c -> "ACOUSTICS".equals(c.getCategoryNameCanonical())));
        assertTrue(categoryStore.stream().anyMatch(c -> "PHYSICS".equals(c.getCategoryNameCanonical())));
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

    private WosFactBuilderService serviceWithImportRunMetrics() {
        return new WosFactBuilderService(
                parserOrchestrator,
                identityResolutionService,
                new WosTitleAuthority(),
                metricFactRepository,
                categoryFactRepository,
                coverageFactRepository,
                factConflictRepository,
                mongoTemplate,
                checkpointService,
                wosIndexMaintenanceService,
                new WosOptimizationProperties(),
                meterRegistry,
                importRunMetricService
        );
    }

    private WosParsedRecord recordWithIdentity(
            String issn,
            String eIssn,
            MetricType metricType,
            WosSourceType sourceType,
            Double value,
            String sourceVersion,
            String sourceRowItem
    ) {
        return new WosParsedRecord(
                "Journal",
                issn,
                eIssn,
                2023,
                metricType,
                value,
                "ACOUSTICS",
                EditionNormalized.SCIE.name(),
                EditionNormalized.SCIE,
                "Q1",
                null,
                1,
                "ev-1",
                sourceType,
                "file.xlsx",
                sourceVersion,
                sourceRowItem
        );
    }

    private String identityKeyFor(String issn, String eIssn, int year, String editionRaw) {
        return WosCanonicalContractSupport.buildIdentityKey(
                Set.of(
                        WosCanonicalContractSupport.normalizeIssnToken(issn),
                        WosCanonicalContractSupport.normalizeIssnToken(eIssn)
                ),
                null,
                year,
                editionRaw
        );
    }


    @Test
    void resolutionOrderIsMjlThenOfficialThenGovNewestFirstAndJcrNeverResolves() {
        WosParsedRecord gov2011 = new WosParsedRecord("ACOUST AUST", "1111-1111", "2222-2222", 2011,
                MetricType.AIS, 1.0, null, "SCIE", EditionNormalized.SCIE, null, null, null,
                "ev-gov11", WosSourceType.GOV_AIS_RIS, "AIS_2011.xlsx", "v2011", "1", null);
        WosParsedRecord gov2019 = new WosParsedRecord("Some Other Journal", "3333-3333", "4444-4444", 2019,
                MetricType.RIS, 1.0, null, "SCIE", EditionNormalized.SCIE, null, null, null,
                "ev-gov19", WosSourceType.GOV_AIS_RIS, "RIS_2019.xlsx", "v2019", "1", null);
        WosParsedRecord official = new WosParsedRecord("Journal of Sound and Vibration", "0022-460X", "1095-8568", 2018,
                MetricType.AIS, 1.0, null, "SCIE", EditionNormalized.SCIE, null, null, null,
                "ev-json", WosSourceType.OFFICIAL_WOS_EXTRACT, "journals-SCIE-year-2018.json", "v2018", "1", "J SOUND VIB");
        WosParsedRecord mjl = new WosParsedRecord("ACOUSTICS AUSTRALIA", "0814-6039", "1839-2571", 2025,
                null, null, "Acoustics", "SCIE", EditionNormalized.SCIE, null, null, null,
                "ev-mjl", WosSourceType.MJL_COVERAGE, "scie.csv", "2025", "0814-6039|SCIE", null);
        WosParsedRecord jcr = new WosParsedRecord("ACOUSTICS AUSTRALIA", null, null, 2025,
                null, null, null, "SCIE", null, null, null, null,
                "ev-jcr", WosSourceType.JCR_REFERENCE, "JCR 2025.csv", "2025", "ACOUST AUST", "ACOUST AUST");
        // deliberately shuffled input: the ordered-rebuild contract must impose the source priority
        when(parserOrchestrator.parseAllEvents()).thenReturn(runOf(List.of(gov2011, gov2019, jcr, official, mjl)));

        service.buildFactsFromImportEvents();

        ArgumentCaptor<WosIdentitySourceContext> contexts = ArgumentCaptor.forClass(WosIdentitySourceContext.class);
        verify(identityResolutionService, times(4))
                .resolveIdentityWithPrefetchedCandidates(any(), any(), any(), contexts.capture(), any());
        List<String> resolvedOrder = contexts.getAllValues().stream().map(WosIdentitySourceContext::sourceFile).toList();
        // JCR never resolves (naming reference only); MJL first, then official extract, then GOV newest-first
        assertEquals(List.of("scie.csv", "journals-SCIE-year-2018.json", "RIS_2019.xlsx", "AIS_2011.xlsx"), resolvedOrder);
        assertEquals("J SOUND VIB", contexts.getAllValues().get(1).abbreviatedTitle());
    }

    @Test
    void enrichmentAssignsTiedValuesTheSameQuartile() {
        // 4 journals, values 5 / 3 / 3 / 2 -> competition ranks 1,2,2,4; quartile bounds q1=1,q2=2,q3=3.
        // The tied pair shares rank 2 and must BOTH be Q2 (JCR convention), not split Q2/Q3 by position.
        metricStore.add(metric("jid-1", 2024, MetricType.AIS, 5.0));
        metricStore.add(metric("jid-2", 2024, MetricType.AIS, 3.0));
        metricStore.add(metric("jid-3", 2024, MetricType.AIS, 3.0));
        metricStore.add(metric("jid-4", 2024, MetricType.AIS, 2.0));
        WosCategoryFact c1 = category("c1", "jid-1", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact c2 = category("c2", "jid-2", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact c3 = category("c3", "jid-3", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        WosCategoryFact c4 = category("c4", "jid-4", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        categoryStore.addAll(List.of(c1, c2, c3, c4));

        service.enrichMissingCategoryRankingFields();

        assertEquals("Q1", c1.getQuarter());
        assertEquals("Q2", c2.getQuarter());
        assertEquals("Q2", c3.getQuarter());
        assertEquals("Q4", c4.getQuarter());
        assertEquals(2, c2.getRank());
        assertEquals(2, c3.getRank());
    }

    @Test
    void scopedEnrichmentRanksAgainstTheFullCategoryCohortNotJustUploadedJournals() {
        // The upload touches only jid-1, but jid-9 (from an older lineage, different journal) sits in the
        // same category group with a higher value — the computed rank must account for it.
        metricStore.add(metric("jid-1", 2024, MetricType.AIS, 5.0));
        metricStore.add(metric("jid-9", 2024, MetricType.AIS, 9.0));
        WosCategoryFact target = category("c1", "jid-1", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, null, null, null);
        target.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        target.setSourceFile("wos.json");
        target.setSourceVersion("2026-Q1");
        WosCategoryFact cohortPeer = category("c9", "jid-9", 2024, MetricType.AIS, "ECONOMICS", EditionNormalized.SCIE, "Q1", 1, 1);
        cohortPeer.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        cohortPeer.setSourceFile("older.json");
        cohortPeer.setSourceVersion("2025-Q4");
        categoryStore.addAll(List.of(target, cohortPeer));

        service.enrichMissingCategoryRankingFieldsForSource(WosSourceType.OFFICIAL_WOS_EXTRACT, "wos.json", "2026-Q1");

        assertEquals(2, target.getRank());
        assertEquals(1, cohortPeer.getRank());
    }

    private WosParserRunResult runOf(List<WosParsedRecord> records) {
        WosParserRunSummary summary = new WosParserRunSummary(10);
        records.forEach(r -> {
            summary.markProcessed();
            summary.markParsed();
        });
        return new WosParserRunResult(summary, records);
    }

    private WosMetricFact metric(String journalId, int year, MetricType metricType, double value) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setValue(value);
        return fact;
    }

    private WosCategoryFact category(
            String id,
            String journalId,
            int year,
            MetricType metricType,
            String category,
            EditionNormalized edition,
            String quarter,
            Integer quartileRank,
            Integer rank
    ) {
        WosCategoryFact fact = new WosCategoryFact();
        fact.setId(id);
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setCategoryNameCanonical(category);
        fact.setEditionNormalized(edition);
        fact.setQuarter(quarter);
        fact.setQuartileRank(quartileRank);
        fact.setRank(rank);
        return fact;
    }

    private List<WosParsedRecord> generateRecords(int count) {
        List<WosParsedRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            records.add(record(MetricType.AIS, WosSourceType.GOV_AIS_RIS, 1.0 + i, "v2023", Integer.toString(i), "ACOUSTICS", EditionNormalized.SCIE, "Q1", 1));
        }
        return records;
    }
}
