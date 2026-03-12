package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosScoringViewRepository;
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosProjectionBuilderServiceTest {

    @Mock private WosJournalIdentityRepository identityRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private WosRankingViewRepository rankingViewRepository;
    @Mock private WosScoringViewRepository scoringViewRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private WosIndexMaintenanceService wosIndexMaintenanceService;
    @Mock private BulkOperations bulkOperations;

    @Test
    void rebuildCreatesChunkedProjectionRowsWithStableContracts() {
        WosProjectionBuilderService service = service();

        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity("jid-1"))), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class)))
                .thenReturn(List.of(
                        metric("jid-1", 2022, MetricType.AIS, 1.2),
                        metric("jid-1", 2023, MetricType.RIS, 0.8)
                ));
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(
                List.of(
                        category("jid-1", 2022, MetricType.AIS, EditionNormalized.SCIE, "ACOUSTICS", "Q1", 7, 1),
                        category("jid-1", 2023, MetricType.RIS, EditionNormalized.SSCI, "ACOUSTICS", "Q2", null, 3)
                ),
                List.of(
                        category("jid-1", 2022, MetricType.AIS, EditionNormalized.SCIE, "ACOUSTICS", "Q1", 7, 1),
                        category("jid-1", 2023, MetricType.RIS, EditionNormalized.SSCI, "ACOUSTICS", "Q2", null, 3)
                ),
                List.of()
        );
        when(mongoTemplate.bulkOps(eq(BulkOperations.BulkMode.UNORDERED), eq(WosScoringView.class)))
                .thenReturn(bulkOperations);
        when(bulkOperations.insert(anyList())).thenReturn(bulkOperations);
        when(bulkOperations.execute()).thenReturn(mock(com.mongodb.bulk.BulkWriteResult.class));

        ImportProcessingResult result = service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<WosScoringView>> scoringCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        verify(bulkOperations).insert(scoringCaptor.capture());

        WosRankingView rankingView = rankingCaptor.getValue().getFirst();
        WosScoringView scoringView = scoringCaptor.getValue().getFirst();
        assertEquals("jid-1", rankingView.getId());
        assertEquals(2022, rankingView.getLatestAisYear());
        assertEquals(2023, rankingView.getLatestRisYear());
        assertNotNull(rankingView.getBuildVersion());
        assertEquals(rankingView.getBuildVersion(), scoringView.getBuildVersion());
        assertEquals("ACOUSTICS", scoringView.getCategoryNameCanonical());
        assertEquals("Q1", scoringView.getQuarter());
        assertEquals(7, scoringView.getQuartileRank());
        assertEquals(1, scoringView.getRank());
        assertEquals(1.2, scoringView.getValue());
        assertEquals(3, result.getImportedCount());
    }

    @Test
    void rebuildNormalizesSearchFields() {
        WosProjectionBuilderService service = service();
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-4");
        identity.setTitle("  Journal:  Of  TESTS  ");
        identity.setPrimaryIssn(" 1234-5678 ");
        identity.setEIssn(" 8765 4321 ");
        identity.setAliasIssns(Arrays.asList("1111-2222", " 11112222 ", null, ""));
        when(identityRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(identity)), new PageImpl<>(List.of()));
        when(mongoTemplate.find(any(), eq(WosMetricFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(WosCategoryFact.class))).thenReturn(List.of(), List.of());
        when(rankingViewRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.rebuildWosProjections();

        ArgumentCaptor<List<WosRankingView>> rankingCaptor = ArgumentCaptor.forClass(List.class);
        verify(rankingViewRepository).saveAll(rankingCaptor.capture());
        WosRankingView view = rankingCaptor.getValue().getFirst();
        assertEquals("journal: of tests", view.getNameNorm());
        assertEquals("12345678", view.getIssnNorm());
    }

    private WosProjectionBuilderService service() {
        WosOptimizationProperties properties = new WosOptimizationProperties();
        return new WosProjectionBuilderService(
                identityRepository,
                metricFactRepository,
                categoryFactRepository,
                rankingViewRepository,
                scoringViewRepository,
                mongoTemplate,
                wosIndexMaintenanceService,
                properties
        );
    }

    private WosJournalIdentity identity(String id) {
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId(id);
        identity.setTitle("Journal " + id);
        identity.setPrimaryIssn("12345678");
        identity.setEIssn("87654321");
        identity.setAliasIssns(List.of("11112222"));
        identity.setAlternativeNames(List.of("Alt Journal " + id));
        return identity;
    }

    private WosMetricFact metric(String journalId, int year, MetricType metricType, Double value) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setValue(value);
        fact.setSourceVersion("v" + year);
        fact.setSourceRowItem("1");
        return fact;
    }

    private WosCategoryFact category(
            String journalId,
            int year,
            MetricType metricType,
            EditionNormalized edition,
            String categoryName,
            String quarter,
            Integer quartileRank,
            Integer rank
    ) {
        WosCategoryFact fact = new WosCategoryFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setEditionNormalized(edition);
        fact.setCategoryNameCanonical(categoryName);
        fact.setQuarter(quarter);
        fact.setQuartileRank(quartileRank);
        fact.setRank(rank);
        return fact;
    }
}
