package ro.uvt.pokedex.core.service.reporting;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class ImpactFactorJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ro.uvt.pokedex.core.testsupport.ReportingLookupTestSupport.delegateForumLookupToIssn(lookupPort);
    }
    @Test
    void missingIfDataReturnsEmptyScoreAndIncrementsMissingCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImpactFactorJournalScoringService service = new ImpactFactorJournalScoringService(lookupPort, meterRegistry);

        Indicator indicator = indicatorForAllDomain();
        ScoringPublication publication = publication("forum-1", "ar", "2023-01-01");
        ScholardexForumView forum = forum("1234-5678", null);

        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());

        Score score = service.getScore(publication, indicator);

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.requests").counter().count());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.missing").counter().count());
        assertEquals(0.0, meterRegistry.get("pokedex.reporting.if.success").counter().count());
    }

    @Test
    void ifDataPresentReturnsScoreAndIncrementsSuccessCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImpactFactorJournalScoringService service = new ImpactFactorJournalScoringService(lookupPort, meterRegistry);

        Indicator indicator = indicatorForAllDomain();
        ScoringPublication publication = publication("forum-1", "ar", "2023-01-01");
        ScholardexForumView forum = forum("1234-5678", null);
        WoSRanking ranking = rankingWithIf("ECONOMICS - SCIE", 2023, 2.5, WoSRanking.Quarter.Q1);

        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking));

        Score score = service.getScore(publication, indicator);

        assertEquals(2.5, score.getScore());
        assertEquals(2023, score.getYear());
        assertEquals("Q1", score.getQuarter());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.requests").counter().count());
        assertEquals(0.0, meterRegistry.get("pokedex.reporting.if.missing").counter().count());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.success").counter().count());
    }

    @Test
    void activityPathUsesForumReferenceAndCounters() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImpactFactorJournalScoringService service = new ImpactFactorJournalScoringService(lookupPort, meterRegistry);
        Indicator indicator = indicatorForAllDomain();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        ActivityInstance activity = new ActivityInstance();
        activity.setId("act-1");
        activity.setDate("2022-09-10");
        activity.setReferenceFields(Map.of(Activity.ReferenceField.FORUM_ISSN, "7777-7777"));

        WoSRanking ranking = rankingWithIf("ECONOMICS - SCIE", 2022, 4.2, WoSRanking.Quarter.Q3);
        when(lookupPort.getRankingsByIssn("7777-7777")).thenReturn(List.of(ranking));

        Score score = service.getScore(activity, indicator);

        assertEquals(4.2, score.getScore());
        assertEquals("Q3", score.getQuarter());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.requests").counter().count());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.success").counter().count());
    }

    @Test
    void nonArticlePublicationReturnsMissingCountersOnly() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImpactFactorJournalScoringService service = new ImpactFactorJournalScoringService(lookupPort, meterRegistry);
        Indicator indicator = indicatorForAllDomain();
        ScoringPublication publication = publication("forum-1", "cp", "2023-01-01");
        ScholardexForumView forum = forum("1234-5678", null);

        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        Score score = service.getScore(publication, indicator);
        assertEquals(0.0, score.getScore());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.missing").counter().count());
    }

    private Indicator indicatorForAllDomain() {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        return indicator;
    }

    private ScoringPublication publication(String forumId, String subtype, String coverDate) {
        return new ScoringPublication(
                "pub-1",
                "eid-1",
                forumId,
                coverDate,
                subtype,
                null,
                List.of("a1"),
                1,
                "10.1000/pub-1",
                null,
                "Test Journal Article",
                0,
                java.util.Set.of()
        );
    }

    private ScholardexForumView forum(String issn, String eIssn) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Test Journal");
        forum.setIssn(issn);
        forum.setEIssn(eIssn);
        forum.setAggregationType("Journal");
        return forum;
    }

    private WoSRanking rankingWithIf(String category, int year, double value, WoSRanking.Quarter quarter) {
        WoSRanking.Score score = new WoSRanking.Score();
        score.setIF(Map.of(year, value));

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQIF(Map.of(year, quarter));
        rank.setRankIF(Map.of(year, 1));

        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-1");
        ranking.setScore(score);
        ranking.setWebOfScienceCategoryIndex(Map.of(category, rank));
        return ranking;
    }
}
