package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosRankingDetailsReadServiceTest {

    @Mock
    private WosRankingViewRepository rankingViewRepository;
    @Mock
    private WosMetricFactRepository metricFactRepository;

    private WosRankingDetailsReadService service;

    @BeforeEach
    void setUp() {
        service = new WosRankingDetailsReadService(rankingViewRepository, metricFactRepository);
    }

    @Test
    void returnsEmptyWhenProjectionMissing() {
        when(rankingViewRepository.findById("j1")).thenReturn(Optional.empty());

        assertTrue(service.findByJournalId("j1").isEmpty());
    }

    @Test
    void returnsEmptyWhenFactsMissing() {
        WosRankingView view = new WosRankingView();
        view.setId("j1");
        when(rankingViewRepository.findById("j1")).thenReturn(Optional.of(view));
        when(metricFactRepository.findAllByJournalId("j1")).thenReturn(List.of());

        assertTrue(service.findByJournalId("j1").isEmpty());
    }

    @Test
    void buildsLegacyCompatibleDetailsFromProjectionAndFacts() {
        WosRankingView view = new WosRankingView();
        view.setId("j1");
        view.setName("Journal One");
        view.setIssn("1234-5678");
        view.setEIssn("8765-4321");
        view.setAlternativeIssns(List.of("1111-2222"));

        WosMetricFact aisMetric = metricFact(2021, MetricType.AIS, 1.25, EditionNormalized.SCIE);
        WosMetricFact risMetric = metricFact(2021, MetricType.RIS, 0.75, EditionNormalized.SCIE);
        WosMetricFact ifMetric = metricFact(2021, MetricType.IF, 2.10, EditionNormalized.SCIE);
        aisMetric.setCategoryNameCanonical("COMPUTER SCIENCE");
        aisMetric.setQuarter("Q2");
        aisMetric.setRank(10);
        ifMetric.setCategoryNameCanonical("COMPUTER SCIENCE");
        ifMetric.setQuarter("Q3");
        ifMetric.setRank(15);

        when(rankingViewRepository.findById("j1")).thenReturn(Optional.of(view));
        when(metricFactRepository.findAllByJournalId("j1")).thenReturn(List.of(aisMetric, risMetric, ifMetric));

        Optional<WoSRanking> result = service.findByJournalId("j1");

        assertTrue(result.isPresent());
        WoSRanking ranking = result.get();
        assertEquals("Journal One", ranking.getName());
        assertEquals(1.25, ranking.getScore().getAis().get(2021));
        assertEquals(0.75, ranking.getScore().getRis().get(2021));
        assertEquals(2.10, ranking.getScore().getIF().get(2021));
        String categoryKey = "COMPUTER SCIENCE - SCIE";
        assertEquals(WoSRanking.Quarter.Q2, ranking.getWebOfScienceCategoryIndex().get(categoryKey).getQAis().get(2021));
        assertEquals(10, ranking.getWebOfScienceCategoryIndex().get(categoryKey).getRankAis().get(2021));
        assertEquals(WoSRanking.Quarter.Q3, ranking.getWebOfScienceCategoryIndex().get(categoryKey).getQIF().get(2021));
        assertEquals(15, ranking.getWebOfScienceCategoryIndex().get(categoryKey).getRankIF().get(2021));
    }

    private WosMetricFact metricFact(int year, MetricType metricType, double value, EditionNormalized editionNormalized) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId("j1");
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setValue(value);
        fact.setEditionNormalized(editionNormalized);
        return fact;
    }
}
