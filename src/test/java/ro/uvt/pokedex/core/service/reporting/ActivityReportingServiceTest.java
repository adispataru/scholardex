package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityReportingServiceTest {

    @Mock
    private ScoringFactoryService scoringFactoryService;
    @Mock
    private ScoringService scoringService;

    @Test
    void genericCountFormulaUsesFieldsAndMathFunctions() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService);
        Indicator indicator = indicator(Indicator.Strategy.GENERIC_COUNT, "max(hours, bonus) + min(bonus, 2)");
        ActivityInstance activity = activity("a1", Map.of("hours", "3", "bonus", "5"), true);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(1.0, score.getScore());
        assertEquals(7.0, score.getAuthorScore());
        assertTrue(score.getDetails().contains("hours: 3.0"));
    }

    @Test
    void genericCountSetsScoreAndAuthorScoreFromFormula() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService);
        Indicator indicator = indicator(Indicator.Strategy.GENERIC_COUNT, "S * 2");
        ActivityInstance activity = activity("a1", Map.of(), false);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(1.0, score.getScore());
        assertEquals(2.0, score.getAuthorScore());
        assertEquals("Generic Count", score.getCoreRankingEquivalent());
    }

    @Test
    void delegatedScoringUsesScoringServiceMetadataAndExtrasInFormula() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService);
        Indicator indicator = indicator(Indicator.Strategy.CS_JOURNAL, "S * M");
        ActivityInstance activity = activity("a1", Map.of(), false);
        Score delegated = new Score();
        delegated.setScore(3.0);
        delegated.setYear(2023);
        delegated.setQuarter("Q1");
        delegated.setCoreRankingEquivalent("A");
        delegated.setScoringSource("SCOPUS+WOS");
        delegated.setScoringInfo(Map.of("source", "wos"));
        delegated.setExtra(Map.of("M", 4));

        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS_JOURNAL)).thenReturn(scoringService);
        when(scoringService.getScore(activity, indicator)).thenReturn(delegated);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(3.0, score.getScore());
        assertEquals(12.0, score.getAuthorScore());
        assertEquals("Q1", score.getQuarter());
        assertEquals("SCOPUS+WOS", score.getScoringSource());
        assertEquals(4, score.getExtra().get("M"));
    }

    @Test
    void invalidFormulaVariableYieldsZeroAuthorScore() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService);
        Indicator indicator = indicator(Indicator.Strategy.GENERIC_COUNT, "S + missingVar");
        ActivityInstance activity = activity("a1", Map.of(), false);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(1.0, score.getScore());
        assertEquals(0.0, score.getAuthorScore());
    }

    @Test
    void calculateActivityScoresFiltersZeroScoresAndComputesTotal() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService);
        Indicator indicator = indicator(Indicator.Strategy.GENERIC_COUNT, "S");
        ActivityInstance included = activity("a1", Map.of(), false);
        ActivityInstance excluded = activity("a2", Map.of(), false);

        Map<String, Score> scores = service.calculateActivityScores(List.of(included), indicator);
        assertEquals(1.0, scores.get("a1").getAuthorScore());
        assertEquals(1.0, scores.get("total").getAuthorScore());

        Indicator zeroIndicator = indicator(Indicator.Strategy.GENERIC_ACTIVITY, "0");
        Map<String, Score> zeroScores = service.calculateActivityScores(List.of(excluded), zeroIndicator);
        assertEquals(1, zeroScores.size());
        assertEquals(0.0, zeroScores.get("total").getAuthorScore());
    }

    private Indicator indicator(Indicator.Strategy strategy, String formula) {
        Indicator indicator = new Indicator();
        indicator.setScoringStrategy(strategy);
        indicator.setFormula(formula);
        Domain d = new Domain();
        d.setName("ALL");
        indicator.setDomain(d);
        indicator.setId("ind-1");
        return indicator;
    }

    private ActivityInstance activity(String id, Map<String, String> fields, boolean numericFields) {
        Activity activity = new Activity();
        if (numericFields) {
            Activity.Field hours = new Activity.Field();
            hours.setName("hours");
            hours.setNumber(true);
            Activity.Field bonus = new Activity.Field();
            bonus.setName("bonus");
            bonus.setNumber(true);
            activity.setFields(List.of(hours, bonus));
        }

        ActivityInstance instance = new ActivityInstance();
        instance.setId(id);
        instance.setDate("2024-01-01");
        instance.setActivity(activity);
        instance.setFields(fields);
        instance.setReferenceFields(Map.of());
        return instance;
    }
}
