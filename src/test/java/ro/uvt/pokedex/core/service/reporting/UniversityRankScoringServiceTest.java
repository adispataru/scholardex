package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.service.model.URAPUniversityRankingService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class UniversityRankScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;
    @Mock
    private URAPUniversityRankingService urapRankingService;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
    }
    @Test
    void missingUniversityReferenceReturnsZero() {
        UniversityRankScoringService service = new UniversityRankScoringService(lookupPort, urapRankingService);
        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2023-01-01");
        activity.setReferenceFields(Map.of());

        Score score = service.getScore(activity, indicator());

        assertEquals(0.0, score.getScore());
    }

    @Test
    void missingUrapEntryReturnsZero() {
        UniversityRankScoringService service = new UniversityRankScoringService(lookupPort, urapRankingService);
        ActivityInstance activity = activityWithUniversity("UVT");
        when(urapRankingService.getURAPUniversityRankingByName("UVT")).thenReturn(null);

        Score score = service.getScore(activity, indicator());

        assertEquals(0.0, score.getScore());
    }

    @Test
    void selectsBestLowestRankAcrossAllowedYears() {
        UniversityRankScoringService service = new UniversityRankScoringService(lookupPort, urapRankingService);
        ActivityInstance activity = activityWithUniversity("UVT");
        URAPUniversityRanking ranking = new URAPUniversityRanking();
        ranking.setScores(Map.of(
                2022, score(450),
                2023, score(300),
                2024, score(700)
        ));
        when(urapRankingService.getURAPUniversityRankingByName("UVT")).thenReturn(ranking);

        Score score = service.getScore(activity, indicator());

        assertEquals(300.0, score.getScore());
        assertEquals(2023, score.getYear());
    }

    @Test
    void visitYearOutsideUrapWindowFallsBackToTheClosestDataYear() {
        // URAP data starts ~2018; a 2010 Pisa visit used to land in the S==0 branch (unranked floor)
        // even though the university IS ranked — the closest data year's rank is the right estimate.
        UniversityRankScoringService service = new UniversityRankScoringService(lookupPort, urapRankingService);
        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2010-05-01");
        activity.setReferenceFields(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, "University of Pisa"));
        URAPUniversityRanking ranking = new URAPUniversityRanking();
        ranking.setScores(Map.of(
                2018, score(240),
                2024, score(237)
        ));
        when(urapRankingService.getURAPUniversityRankingByName("University of Pisa")).thenReturn(ranking);
        Indicator itemYear = indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(itemYear, "IY");

        Score score = service.getScore(activity, itemYear);

        // 2010 -> closest data year is 2018 (rank 240) -> top-500 bucket, category C.
        assertEquals(240.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
    }

    @Test
    void closestYearTiePrefersTheEarlierYear() {
        UniversityRankScoringService service = new UniversityRankScoringService(lookupPort, urapRankingService);
        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2021-06-01");
        activity.setReferenceFields(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, "UVT"));
        URAPUniversityRanking ranking = new URAPUniversityRanking();
        ranking.setScores(Map.of(
                2020, score(100),
                2022, score(600)
        ));
        when(urapRankingService.getURAPUniversityRankingByName("UVT")).thenReturn(ranking);
        Indicator itemYear = indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(itemYear, "IY");

        Score score = service.getScore(activity, itemYear);

        // 2021 is equidistant from 2020 and 2022 — the earlier year (closer to the visit era) wins.
        assertEquals(100.0, score.getScore());
    }

    private ActivityInstance activityWithUniversity(String name) {
        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2023-03-10");
        activity.setReferenceFields(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, name));
        return activity;
    }

    private Indicator indicator() {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "2022->2024"); // H52 11d.1: comma-list grammar dropped in v1
        return indicator;
    }

    private URAPUniversityRanking.Score score(int rank) {
        URAPUniversityRanking.Score score = new URAPUniversityRanking.Score();
        score.setRank(rank);
        return score;
    }
}
