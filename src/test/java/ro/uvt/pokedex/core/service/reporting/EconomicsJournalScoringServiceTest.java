package ro.uvt.pokedex.core.service.reporting;

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

import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class EconomicsJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
    }
    @Test
    void articleUsesEconomicsCategoryMultiplierTen() {
        EconomicsJournalScoringService service = new EconomicsJournalScoringService(lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forum("1111-1111"));
        when(lookupPort.getRankingsByIssn("1111-1111")).thenReturn(List.of(ranking("ECONOMICS - SCIE", 2023, 2.5, WoSRanking.Quarter.Q1)));

        Score score = service.getScore(publication("ar"), indicator("IY"));

        assertEquals(2.5, score.getScore());
        assertEquals("Q1", score.getQuarter());
        assertEquals(10, score.getExtra().get("M"));
        // H52 slice 9: typed slot must be populated identically to extra["M"] (dual-write).
        assertEquals(10, score.getMultiplier());
    }

    @Test
    void nonArticleDoesNotScore() {
        EconomicsJournalScoringService service = new EconomicsJournalScoringService(lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forum("1111-1111"));

        Score score = service.getScore(publication("cp"), indicator("IY"));

        assertEquals(0.0, score.getScore());
    }

    @Test
    void activityPathUsesForumReferencesAndIndexFallbackMultiplier() {
        EconomicsJournalScoringService service = new EconomicsJournalScoringService(lookupPort);
        when(lookupPort.getRankingsByIssn("2222-2222")).thenReturn(List.of(ranking("OTHER CATEGORY - SCIE", 2023, 1.0, WoSRanking.Quarter.Q2)));

        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2023-06-01");
        activity.setReferenceFields(Map.of(Activity.ReferenceField.FORUM_ISSN, "2222-2222"));

        Score score = service.getScore(activity, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(6, score.getExtra().get("M"));
    }

    @Test
    void tieOnScorePrefersHigherMultiplierCategory() {
        EconomicsJournalScoringService service = new EconomicsJournalScoringService(lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forum("1111-1111"));
        when(lookupPort.getRankingsByIssn("1111-1111")).thenReturn(List.of(
                ranking("OTHER CATEGORY - SCIE", 2023, 2.0, WoSRanking.Quarter.Q2),
                ranking("ECONOMICS - SSCI", 2023, 2.0, WoSRanking.Quarter.Q1)
        ));

        Score score = service.getScore(publication("ar"), indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(10, score.getExtra().get("M"));
        assertEquals("Q1", score.getQuarter());
    }

    @Test
    void tieWithMissingMultiplierMetadataKeepsExistingBest() {
        EconomicsJournalScoringService service = new EconomicsJournalScoringService(lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forum("1111-1111"));
        when(lookupPort.getRankingsByIssn("1111-1111")).thenReturn(List.of(
                ranking("ECONOMICS - SCIE", 2023, 2.0, WoSRanking.Quarter.Q1),
                ranking("UNRELATED CATEGORY - AHCI", 2023, 2.0, WoSRanking.Quarter.Q4)
        ));

        Score score = service.getScore(publication("ar"), indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(10, score.getExtra().get("M"));
        assertEquals("Q1", score.getQuarter());
        assertNull(score.getScoringSource());
    }

    @Test
    void infoEconomicsCategorySetsMultiplierEightAndQuarter() {
        EconomicsJournalScoringService service = new EconomicsJournalScoringService(lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forum("1111-1111"));
        when(lookupPort.getRankingsByIssn("1111-1111")).thenReturn(List.of(
                ranking("COMPUTER SCIENCE, INFORMATION SYSTEMS - SCIE", 2023, 1.7, WoSRanking.Quarter.Q2)
        ));

        Score score = service.getScore(publication("ar"), indicator("IY"));

        assertEquals(1.7, score.getScore());
        assertEquals(8, score.getExtra().get("M"));
        assertEquals("Q2", score.getQuarter());
    }

    @Test
    void descriptionIsNonEmpty() {
        EconomicsJournalScoringService service = new EconomicsJournalScoringService(lookupPort);
        String description = service.getDescription();
        assertTrue(description != null && !description.isBlank());
    }

    @Test
    void compareScoresByPointsAndMultiplierReturnsFalseWhenTieAndMultiplierMissing() throws Exception {
        EconomicsJournalScoringService service = new EconomicsJournalScoringService(lookupPort);
        Score candidate = new Score();
        candidate.setScore(2.0);

        Method init = AbstractForumScoringService.class.getDeclaredMethod("initializeScoreResult");
        init.setAccessible(true);
        Object scoreResult = init.invoke(service);
        var scoreResultClass = scoreResult.getClass();
        var bestPointsField = scoreResultClass.getDeclaredField("bestPoints");
        bestPointsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<Double> bestPoints =
                (java.util.concurrent.atomic.AtomicReference<Double>) bestPointsField.get(scoreResult);
        bestPoints.set(2.0);

        Method compare = EconomicsJournalScoringService.class.getDeclaredMethod(
                "compareScoresByPointsAndMultiplier",
                Score.class,
                scoreResultClass
        );
        compare.setAccessible(true);
        boolean shouldReplace = (boolean) compare.invoke(service, candidate, scoreResult);
        assertEquals(false, shouldReplace);
    }

    private Indicator indicator(String yearRange) {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setScoreYearRange(yearRange);
        return indicator;
    }

    private ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView forum(String issn) {
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView forum =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView();
        forum.setIssn(issn);
        return forum;
    }

    private ScoringPublication publication(String subtype) {
        return new ScoringPublication(
                "pub-1",
                "eid-1",
                "forum-1",
                "2023-03-01",
                subtype,
                null,
                List.of("a1"),
                1,
                "10.1/x",
                null,
                "title",
                0,
                java.util.Set.of()
        );
    }

    private WoSRanking ranking(String category, int year, double ais, WoSRanking.Quarter quarter) {
        WoSRanking.Score score = new WoSRanking.Score();
        score.setAis(Map.of(year, ais));
        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(year, quarter));
        WoSRanking ranking = new WoSRanking();
        ranking.setScore(score);
        ranking.setWebOfScienceCategoryIndex(Map.of(category, rank));
        return ranking;
    }
}
