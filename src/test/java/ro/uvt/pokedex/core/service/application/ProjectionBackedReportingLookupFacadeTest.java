package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectionBackedReportingLookupFacadeTest {

    @Mock private CacheService cacheService;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private MongoTemplate mongoTemplate;

    private ProjectionBackedReportingLookupFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ProjectionBackedReportingLookupFacade(
                cacheService,
                metricFactRepository,
                categoryFactRepository,
                mongoTemplate
        );
    }

    @Test
    void getForumAndNonWosMethodsDelegateToCacheService() {
        Forum forum = new Forum();
        when(cacheService.getCachedForums("f1")).thenReturn(forum);
        when(cacheService.getUniversityAuthorIds()).thenReturn(Set.of("a1"));

        assertSame(forum, facade.getForum("f1"));
        assertEquals(Set.of("a1"), facade.getUniversityAuthorIds());
        verify(cacheService).getCachedForums("f1");
        verify(cacheService).getUniversityAuthorIds();
    }

    @Test
    void getRankingsByIssnBuildsLegacyRankingFromScoreAndCategoryFacts() {
        WosRankingView view = new WosRankingView();
        view.setId("j1");
        view.setName("Journal One");
        view.setIssn("1234-5678");
        view.setEIssn("8765-4321");
        view.setAlternativeIssns(List.of("0000-0000"));

        WosMetricFact ais = metricFact("j1", 2023, MetricType.AIS, 1.5);
        WosMetricFact ris = metricFact("j1", 2023, MetricType.RIS, 0.8);
        WosMetricFact ifFact = metricFact("j1", 2023, MetricType.IF, 2.2);

        WosCategoryFact catAis = categoryFact("j1", "ECONOMICS", 2023, MetricType.AIS, "Q1", 2, EditionNormalized.SCIE);
        catAis.setQuartileRank(1);
        WosCategoryFact catIf = categoryFact("j1", "ECONOMICS", 2023, MetricType.IF, "Q2", 4, EditionNormalized.SSCI);

        when(mongoTemplate.find(any(Query.class), eq(WosRankingView.class))).thenReturn(List.of(view));
        when(metricFactRepository.findAllByJournalIdIn(eq(List.of("j1"))))
                .thenReturn(List.of(ais, ris, ifFact));
        when(categoryFactRepository.findAllByJournalIdInAndEditionNormalizedIn(eq(List.of("j1")), eq(Set.of(EditionNormalized.SCIE, EditionNormalized.SSCI))))
                .thenReturn(List.of(catAis, catIf));

        List<WoSRanking> rankings = facade.getRankingsByIssn("12345678");

        assertEquals(1, rankings.size());
        WoSRanking ranking = rankings.getFirst();
        assertEquals(1.5, ranking.getScore().getAis().get(2023));
        assertEquals(0.8, ranking.getScore().getRis().get(2023));
        assertEquals(2.2, ranking.getScore().getIF().get(2023));
        assertTrue(ranking.getWebOfScienceCategoryIndex().containsKey("ECONOMICS - SCIE"));
        assertTrue(ranking.getWebOfScienceCategoryIndex().containsKey("ECONOMICS - SSCI"));
        assertEquals(1, ranking.getWebOfScienceCategoryIndex().get("ECONOMICS - SCIE").getQuartileRankAis().get(2023));
    }

    @Test
    void getTopRankingsCountsDistinctJournalIdsFromScoringView() {
        when(mongoTemplate.findDistinct(any(Query.class), eq("journalId"), eq(WosScoringView.class), eq(String.class)))
                .thenReturn(List.of("j1", "j2"));

        int count = facade.getTopRankings("ECONOMICS - SCIE", 2023);

        assertEquals(2, count);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findDistinct(queryCaptor.capture(), eq("journalId"), eq(WosScoringView.class), eq(String.class));
        String queryText = queryCaptor.getValue().toString();
        assertTrue(queryText.contains("ECONOMICS"));
        assertTrue(queryText.contains("SCIE"));
        assertTrue(queryText.contains("Q1"));
        assertTrue(queryText.contains("AIS"));
    }

    private WosMetricFact metricFact(String journalId, int year, MetricType metricType, double value) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId(journalId);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setValue(value);
        return fact;
    }

    private WosCategoryFact categoryFact(
            String journalId,
            String category,
            int year,
            MetricType metricType,
            String quarter,
            int rank,
            EditionNormalized editionNormalized
    ) {
        WosCategoryFact fact = new WosCategoryFact();
        fact.setJournalId(journalId);
        fact.setCategoryNameCanonical(category);
        fact.setYear(year);
        fact.setMetricType(metricType);
        fact.setQuarter(quarter);
        fact.setRank(rank);
        fact.setEditionNormalized(editionNormalized);
        return fact;
    }
}
