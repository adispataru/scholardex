package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.service.model.UniversityRankingLookupService;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** H83 S3 — the UNI_RANKING scorer consumes the best-of URAP/ARWU/QS lookup and stamps provenance. */
@ExtendWith(MockitoExtension.class)
class UniversityRankScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;
    @Mock
    private UniversityRankingLookupService rankingLookupService;

    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
    }

    private UniversityRankScoringService service() {
        return new UniversityRankScoringService(lookupPort, rankingLookupService);
    }

    @Test
    void missingUniversityReferenceReturnsZero() {
        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2023-01-01");
        activity.setReferenceFields(Map.of());

        assertEquals(0.0, service().getScore(activity, indicator()).getScore());
    }

    @Test
    void unresolvedUniversityReturnsZero() {
        ActivityInstance activity = activityWithUniversity("Nowhere U", "2023-03-10");
        when(rankingLookupService.bestRank(eq("Nowhere U"), anyInt())).thenReturn(Optional.empty());

        assertEquals(0.0, service().getScore(activity, indicator()).getScore());
    }

    @Test
    void winningSourceProvenanceIsStamped() {
        // ARWU 101-150 beats URAP's 106 — the OM's "cele mai bune poziții" reading. Provenance
        // (source, data year, band) must reach scoringInfo for the drilldown.
        ActivityInstance activity = activityWithUniversity("Aix Marseille University", "2015-04-01");
        Indicator itemYear = indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(itemYear, "IY");
        when(rankingLookupService.bestRank("Aix Marseille University", 2015))
                .thenReturn(Optional.of(new UniversityRankingLookupService.BestRank(101, "ARWU", 2015, "101-150")));

        Score score = service().getScore(activity, itemYear);

        assertEquals(101.0, score.getScore());
        assertEquals("B", score.getCoreRankingEquivalent()); // top-200 bracket
        assertEquals("ARWU", score.getScoringSource());
        assertEquals("101-150", score.getScoringInfo().get("rankBand"));
        assertEquals(2015, score.getScoringInfo().get("resolvedDataYear"));
    }

    @Test
    void bestRankAcrossAllowedYearsWins() {
        // A windowed score-year range: the minimum rank across the allowed years is kept.
        ActivityInstance activity = activityWithUniversity("UVT", "2023-03-10");
        when(rankingLookupService.bestRank(eq("UVT"), anyInt()))
                .thenAnswer(inv -> {
                    int y = inv.getArgument(1);
                    return Optional.of(new UniversityRankingLookupService.BestRank(
                            y == 2023 ? 300 : 450, "URAP", y, String.valueOf(y == 2023 ? 300 : 450)));
                });

        Score score = service().getScore(activity, indicator());

        assertEquals(300.0, score.getScore());
        assertEquals(2023, score.getYear());
    }

    private ActivityInstance activityWithUniversity(String name, String date) {
        ActivityInstance activity = new ActivityInstance();
        activity.setDate(date);
        activity.setReferenceFields(Map.of(Activity.ReferenceField.UNIVERSITY_NAME, name));
        return activity;
    }

    private Indicator indicator() {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "ACTIVITY_UNIVERSITY");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "UNI_RANKING");
        return indicator;
    }
}
