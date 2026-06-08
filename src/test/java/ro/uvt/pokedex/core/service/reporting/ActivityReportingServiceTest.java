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
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator());
        Indicator indicator = indicator("GENERIC_COUNT", "max(hours, bonus) + min(bonus, 2)");
        ActivityInstance activity = activity("a1", Map.of("hours", "3", "bonus", "5"), true);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(1.0, score.getScore());
        assertEquals(7.0, score.getAuthorScore());
        // H52 slice 11c: Score.details deleted; the debug breadcrumb assertion
        // is gone. The numeric assertions above are the real contract.
    }

    @Test
    void genericCountSetsScoreAndAuthorScoreFromFormula() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator());
        Indicator indicator = indicator("GENERIC_COUNT", "S * 2");
        ActivityInstance activity = activity("a1", Map.of(), false);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(1.0, score.getScore());
        assertEquals(2.0, score.getAuthorScore());
        assertEquals("Generic Count", score.getCoreRankingEquivalent());
    }

    @Test
    void genericActivityEvaluatesFormulaWithActivityFields() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator());
        Indicator indicator = indicator(
                "GENERIC_ACTIVITY",
                "B = Buget; X = B < 50000 ? 1 : B < 100000 ? 2 : B < 200000 ? 3 : B < 400000 ? 4 : 5; Rol == 'Membru' ? X : X * 2"
        );
        ActivityInstance activity = grantActivity("grant-1", Map.of(
                "Buget", "270000",
                "Rol", "Membru",
                "Nume Proiect", "SERRANO"
        ));

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("grant-1");

        assertEquals(1.0, score.getScore());
        assertEquals(4.0, score.getAuthorScore());
        assertEquals("Generic Activity", score.getCoreRankingEquivalent());
        // H52 slice 11c: Score.details deleted; the activity-field breadcrumb
        // strings used to land here. The formula result itself (authorScore=4.0,
        // which depends on Buget=270000 and Rol=Membru) is the proof those
        // variables were bound correctly.
    }

    @Test
    void delegatedScoringUsesScoringServiceMetadataAndExtrasInFormula() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator());
        Indicator indicator = indicator("CS_JOURNAL", "S * M");
        ActivityInstance activity = activity("a1", Map.of(), false);
        Score delegated = new Score();
        delegated.setScore(3.0);
        delegated.setYear(2023);
        delegated.setQuarter("Q1");
        delegated.setCoreRankingEquivalent("A");
        delegated.setScoringSource("SCOPUS+WOS");
        delegated.setScoringInfo(Map.of("source", "wos"));
        // H52 slice 11c: typed slot. Was {@code delegated.setExtra(Map.of("M", 4))}.
        delegated.setMultiplier(4);

        when(scoringFactoryService.getScoringService("CS_JOURNAL")).thenReturn(scoringService);
        when(scoringService.getScore(activity, indicator)).thenReturn(delegated);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(3.0, score.getScore());
        assertEquals(12.0, score.getAuthorScore());
        assertEquals("Q1", score.getQuarter());
        assertEquals("SCOPUS+WOS", score.getScoringSource());
        assertEquals(4, score.getMultiplier());
    }

    @Test
    void invalidFormulaVariableYieldsZeroAuthorScore() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator());
        Indicator indicator = indicator("GENERIC_COUNT", "S + missingVar");
        ActivityInstance activity = activity("a1", Map.of(), false);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(1.0, score.getScore());
        assertEquals(0.0, score.getAuthorScore());
    }

    @Test
    void calculateActivityScoresFiltersZeroScoresAndComputesTotal() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator());
        Indicator indicator = indicator("GENERIC_COUNT", "S");
        ActivityInstance included = activity("a1", Map.of(), false);
        ActivityInstance excluded = activity("a2", Map.of(), false);

        Map<String, Score> scores = service.calculateActivityScores(List.of(included), indicator);
        assertEquals(1.0, scores.get("a1").getAuthorScore());
        assertEquals(1.0, scores.get("total").getAuthorScore());

        Indicator zeroIndicator = indicator("GENERIC_ACTIVITY", "0");
        Map<String, Score> zeroScores = service.calculateActivityScores(List.of(excluded), zeroIndicator);
        assertEquals(1, zeroScores.size());
        assertEquals(0.0, zeroScores.get("total").getAuthorScore());
    }

    private Indicator indicator(String strategyName, String formula) {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, strategyName);
        // H52 slice 11d.5: legacy compat setters need both halves of the (outputType,
        // strategy) pair populated to materialize the typed kind. Provide a strategy-
        // appropriate default outputType.
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, legacyOutputTypeFor(strategyName));
        indicator.setFormula(formula);
        Domain d = new Domain();
        d.setName("ALL");
        indicator.setDomain(d);
        indicator.setId("ind-1");
        return indicator;
    }

    private static String legacyOutputTypeFor(String strategyName) {
        // Mirrors the production (outputType, strategy) combinations surveyed in
        // LegacyMappingTest. Defaults to PUBLICATIONS for anything not in the
        // explicit map.
        return switch (strategyName) {
            case "GENERIC_ACTIVITY" -> "GENERIC_ACTIVITIES";
            case "ART_EVENT" -> "ACTIVITY_EVENT";
            case "UNI_RANKING" -> "ACTIVITY_UNIVERSITY";
            default -> "PUBLICATIONS";
        };
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

    private ActivityInstance grantActivity(String id, Map<String, String> fields) {
        Activity.Field budget = new Activity.Field();
        budget.setName("Buget");
        budget.setNumber(true);
        Activity.Field role = new Activity.Field();
        role.setName("Rol");
        role.setNumber(false);
        Activity.Field project = new Activity.Field();
        project.setName("Nume Proiect");
        project.setNumber(false);

        Activity activity = new Activity();
        activity.setFields(List.of(budget, role, project));

        ActivityInstance instance = new ActivityInstance();
        instance.setId(id);
        instance.setDate("2024-09-01");
        instance.setActivity(activity);
        instance.setFields(fields);
        instance.setReferenceFields(Map.of());
        return instance;
    }
}
