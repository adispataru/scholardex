package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosRankingDetailsReadServiceTest {

    @Mock private WosRankingViewRepository rankingViewRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;

    private WosRankingDetailsReadService service;

    @BeforeEach
    void setUp() {
        service = new WosRankingDetailsReadService(rankingViewRepository, metricFactRepository, categoryFactRepository);
    }

    @Test
    void returnsEmptyWhenProjectionMissing() {
        when(rankingViewRepository.findById("j1")).thenReturn(Optional.empty());
        assertTrue(service.findByJournalId("j1").isEmpty());
    }

    @Test
    void returnsEmptyWhenAllFactsMissing() {
        WosRankingView view = new WosRankingView();
        view.setId("j1");
        when(rankingViewRepository.findById("j1")).thenReturn(Optional.of(view));
        when(metricFactRepository.findAllByJournalId("j1")).thenReturn(List.of());
        when(categoryFactRepository.findAllByJournalId("j1")).thenReturn(List.of());

        assertTrue(service.findByJournalId("j1").isEmpty());
    }

    @Test
    void buildsLegacyCompatibleDetailsFromScoreAndCategoryFacts() {
        WosRankingView view = new WosRankingView();
        view.setId("j1");
        view.setName("Journal One");
        view.setIssn("1234-5678");
        view.setEIssn("8765-4321");
        view.setAlternativeIssns(List.of("1111-2222"));

        WosMetricFact aisMetric = metricFact(2021, MetricType.AIS, 1.25);
        WosMetricFact risMetric = metricFact(2021, MetricType.RIS, 0.75);
        WosMetricFact ifMetric = metricFact(2021, MetricType.IF, 2.10);

        WosCategoryFact aisCategory = categoryFact("COMPUTER SCIENCE", 2021, MetricType.AIS, "Q2", 10, EditionNormalized.SCIE);
        WosCategoryFact ifCategory = categoryFact("COMPUTER SCIENCE", 2021, MetricType.IF, "Q3", 15, EditionNormalized.SCIE);

        when(rankingViewRepository.findById("j1")).thenReturn(Optional.of(view));
        when(metricFactRepository.findAllByJournalId("j1")).thenReturn(List.of(aisMetric, risMetric, ifMetric));
        when(categoryFactRepository.findAllByJournalId("j1")).thenReturn(List.of(aisCategory, ifCategory));

        Optional<WoSRanking> result = service.findByJournalId("j1");

        assertTrue(result.isPresent());
        WoSRanking ranking = result.get();
        assertEquals(1.25, ranking.getScore().getAis().get(2021));
        assertEquals(0.75, ranking.getScore().getRis().get(2021));
        assertEquals(2.10, ranking.getScore().getIF().get(2021));

        String categoryKey = "COMPUTER SCIENCE - SCIE";
        assertEquals(WoSRanking.Quarter.Q2, ranking.getWebOfScienceCategoryIndex().get(categoryKey).getQAis().get(2021));
        assertEquals(10, ranking.getWebOfScienceCategoryIndex().get(categoryKey).getRankAis().get(2021));
        assertEquals(WoSRanking.Quarter.Q3, ranking.getWebOfScienceCategoryIndex().get(categoryKey).getQIF().get(2021));
        assertEquals(15, ranking.getWebOfScienceCategoryIndex().get(categoryKey).getRankIF().get(2021));
    }

    private WosMetricFact metricFact(int year, MetricType metricType, double value) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId("j1");
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setValue(value);
        return fact;
    }

    private WosCategoryFact categoryFact(
            String categoryName,
            int year,
            MetricType metricType,
            String quarter,
            Integer rank,
            EditionNormalized editionNormalized
    ) {
        WosCategoryFact fact = new WosCategoryFact();
        fact.setJournalId("j1");
        fact.setCategoryNameCanonical(categoryName);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setQuarter(quarter);
        fact.setRank(rank);
        fact.setEditionNormalized(editionNormalized);
        return fact;
    }
}
