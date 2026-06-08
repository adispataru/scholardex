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
