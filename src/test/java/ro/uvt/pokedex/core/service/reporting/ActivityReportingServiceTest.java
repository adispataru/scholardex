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
    // H64 slice 4a: default mock returns null from findById → proj_* all null → no effect on these existing cases.
    @Mock
    private ro.uvt.pokedex.core.service.application.ScholardexProjectReadPort scholardexProjectReadPort;

    @Test
    void genericCountFormulaUsesFieldsAndMathFunctions() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
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
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator indicator = indicator("GENERIC_COUNT", "S * 2");
        ActivityInstance activity = activity("a1", Map.of(), false);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(1.0, score.getScore());
        assertEquals(2.0, score.getAuthorScore());
        assertEquals("Generic Count", score.getCoreRankingEquivalent());
    }

    @Test
    void genericActivityEvaluatesFormulaWithActivityFields() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator indicator = indicator(
                "GENERIC_ACTIVITY",
                "B = Buget; X = B < 50000 ? 1 : B < 100000 ? 2 : B < 200000 ? 3 : B < 500000 ? 4 : 5; Rol == 'Membru' ? X : X * 2"
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

        // CNATDCU perspective d.v boundary: a 450k EUR grant sits in the 200k-499,999
        // bracket (8|4), NOT the >=500k bracket (10|5). Locks the corrected boundary.
        ActivityInstance boundaryDirector = grantActivity("grant-2", Map.of(
                "Buget", "450000",
                "Rol", "Director",
                "Nume Proiect", "BOUNDARY"
        ));
        assertEquals(8.0,
                service.calculateActivityScores(List.of(boundaryDirector), indicator).get("grant-2").getAuthorScore());
    }

    @Test
    void delegatedScoringUsesScoringServiceMetadataAndExtrasInFormula() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
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
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator indicator = indicator("GENERIC_COUNT", "S + missingVar");
        ActivityInstance activity = activity("a1", Map.of(), false);

        Score score = service.calculateActivityScores(List.of(activity), indicator).get("a1");

        assertEquals(1.0, score.getScore());
        assertEquals(0.0, score.getAuthorScore());
    }

    @Test
    void calculateActivityScoresFiltersZeroScoresAndComputesTotal() {
        ActivityReportingService service = new ActivityReportingService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
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

    @Test
    void physicsDidacticActivityScoresKOverNefFromNAutori() {
        // H65: A1 = 4/Nef. A manual book with 6 authors → Nef = (6+5)/2 = 5.5 → 4/5.5.
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator a1 = indicator("GENERIC_ACTIVITY", "4/Nef");

        Score score = service.calculateActivityScores(List.of(physicsActivity("b1", "6")), a1).get("b1");

        assertEquals(4.0 / 5.5, score.getAuthorScore(), 1e-9);
    }

    @Test
    void physicsActivityWithBlankOrZeroAuthorsFallsBackToNefOne() {
        // No divide-by-zero: a manual item has at least one author, so N_autori 0 → Nef 1 → 4/1.
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator a1 = indicator("GENERIC_ACTIVITY", "4/Nef");

        assertEquals(4.0, service.calculateActivityScores(List.of(physicsActivity("b1", "0")), a1).get("b1").getAuthorScore(), 1e-9);
    }

    // ── H64 slice 4a: linked canonical-project injection (proj_* variables) ──────────────

    @Test
    void a10PrefersCanonicalBudgetOverDeclaredWhenProjectLinked() {
        when(scholardexProjectReadPort.findById("sproj_1")).thenReturn(project(270000L, "EC"));
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator a10 = indicator("GENERIC_ACTIVITY", "proj_budget != null ? proj_budget/100000 : Buget/100000");
        ActivityInstance act = grantActivityWithProject("g1",
                Map.of("Buget", "50000", "Rol", "Director", "Nume Proiect", "X"), "sproj_1");

        // canonical 270000/100000 = 2.7 — NOT the declared 50000 (0.5)
        assertEquals(2.7, service.calculateActivityScores(List.of(act), a10).get("g1").getAuthorScore(), 1e-9);
    }

    @Test
    void fallsBackToDeclaredBudgetWhenNoProjectLinked() {
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator a10 = indicator("GENERIC_ACTIVITY", "proj_budget != null ? proj_budget/100000 : Buget/100000");
        ActivityInstance act = grantActivity("g2", Map.of("Buget", "50000", "Rol", "Director", "Nume Proiect", "X"));

        // no PROJECT_GRANT_ID reference → proj_budget null → declared 50000/100000 = 0.5
        assertEquals(0.5, service.calculateActivityScores(List.of(act), a10).get("g2").getAuthorScore(), 1e-9);
    }

    @Test
    void funderGatingReadsCanonicalFunder() {
        when(scholardexProjectReadPort.findById("sproj_eu")).thenReturn(project(null, "EC"));
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator gate = indicator("GENERIC_ACTIVITY", "proj_funder == 'EC' ? 10 : 1");
        ActivityInstance act = grantActivityWithProject("g3",
                Map.of("Buget", "0", "Rol", "Membru", "Nume Proiect", "X"), "sproj_eu");

        assertEquals(10.0, service.calculateActivityScores(List.of(act), gate).get("g3").getAuthorScore(), 1e-9);
    }

    // ── Grant budget brackets: the derived Interval_buget variable + optional-number robustness ──

    @Test
    void missingOrBlankNumberFieldBindsNullInsteadOfCrashing() {
        // The erascu/sancira prod case: grant entries without (or with blank) Buget used to NPE in
        // Double.parseDouble and take down the whole indicator computation for the report.
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator nullCheck = indicator("GENERIC_ACTIVITY", "Buget == null ? 7 : 1");

        ActivityInstance missing = grantActivity("g-missing", Map.of("Rol", "Director", "Nume Proiect", "X"));
        ActivityInstance blank = grantActivity("g-blank", Map.of("Buget", "", "Rol", "Membru", "Nume Proiect", "Y"));

        assertEquals(7.0, service.calculateActivityScores(List.of(missing), nullCheck).get("g-missing").getAuthorScore(), 1e-9);
        assertEquals(7.0, service.calculateActivityScores(List.of(blank), nullCheck).get("g-blank").getAuthorScore(), 1e-9);
    }

    @Test
    void intervalBugetPrefersCanonicalProjectBudget() {
        when(scholardexProjectReadPort.findById("sproj_1")).thenReturn(project(270000L, "EC"));
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator bracket = indicator("GENERIC_ACTIVITY", "Interval_buget");
        ActivityInstance act = grantActivityWithProject("g4",
                Map.of("Buget", "50000", "Rol", "Director", "Nume Proiect", "X"), "sproj_1");

        // CORDIS 270k beats the declared 50k: bracket 4 (200.000–499.999), not 2.
        assertEquals(4.0, service.calculateActivityScores(List.of(act), bracket).get("g4").getAuthorScore(), 1e-9);
    }

    @Test
    void intervalBugetFallsBackToExactDeclaredBudget() {
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator bracket = indicator("GENERIC_ACTIVITY", "Interval_buget");
        ActivityInstance act = grantActivity("g5", Map.of("Buget", "150000", "Rol", "Membru", "Nume Proiect", "X"));

        assertEquals(3.0, service.calculateActivityScores(List.of(act), bracket).get("g5").getAuthorScore(), 1e-9);
    }

    @Test
    void declaredIntervalOutranksTheCurrencyAmbiguousBuget() {
        // H99 item 5 (Florin Fortis's SCIPA/Dehems): Buget has no currency semantics — prod holds lei
        // amounts and locale-formatted strings — while the interval select is explicitly EUR-labeled.
        // A conflicting declared interval must therefore win over the raw number.
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator bracket = indicator("GENERIC_ACTIVITY", "Interval_buget");

        // SCIPA: 565.600 LEI entered as Buget, project declared 100–199k EUR → bracket 3, not 5.
        ActivityInstance scipa = grantActivity("g-scipa", Map.of(
                "Buget", "565600", "Rol", "Membru", "Nume Proiect", "SCIPA",
                "Interval_buget", "100.000 – 199.999 EUR"));
        // Dehems: "156.491" parses as 156.491 EUR (dot-as-thousands) → would be bracket 1; declared 100–199k wins.
        ActivityInstance dehems = grantActivity("g-dehems", Map.of(
                "Buget", "156.491", "Rol", "Director (proiect internațional)", "Nume Proiect", "Dehems",
                "Interval_buget", "100.000 – 199.999 EUR"));

        assertEquals(3.0, service.calculateActivityScores(List.of(scipa), bracket).get("g-scipa").getAuthorScore(), 1e-9);
        assertEquals(3.0, service.calculateActivityScores(List.of(dehems), bracket).get("g-dehems").getAuthorScore(), 1e-9);
    }

    @Test
    void cordisBudgetStillOutranksTheDeclaredInterval() {
        // CORDIS is authoritative EUR from the funder — it stays above the researcher's select.
        when(scholardexProjectReadPort.findById("sproj_2")).thenReturn(project(270000L, "EC"));
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator bracket = indicator("GENERIC_ACTIVITY", "Interval_buget");
        ActivityInstance act = grantActivityWithProject("g-cordis",
                Map.of("Rol", "Membru", "Nume Proiect", "X", "Interval_buget", "sub 50.000 EUR"), "sproj_2");

        assertEquals(4.0, service.calculateActivityScores(List.of(act), bracket).get("g-cordis").getAuthorScore(), 1e-9);
    }

    @Test
    void intervalBugetUsesTheDeclaredIntervalSelectWhenNoAmountIsKnown() {
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator bracket = indicator("GENERIC_ACTIVITY", "Interval_buget");
        ActivityInstance act = grantActivity("g6", Map.of(
                "Rol", "Membru", "Nume Proiect", "X",
                "Interval_buget", "50.000 – 99.999 EUR"));

        assertEquals(2.0, service.calculateActivityScores(List.of(act), bracket).get("g6").getAuthorScore(), 1e-9);
    }

    @Test
    void unknownBudgetScoresAsLowestBracketViaTheFormulaDefault() {
        // Info_D_v's new shape: nothing known → Interval_buget == 0 → the formula's own default takes
        // the LOWEST bracket (user decision: a competitive grant's existence merits the base tier).
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator dV = indicator("GENERIC_ACTIVITY",
                "X = Interval_buget == 0 ? 1 : Interval_buget; Rol == 'Membru' ? X : X * 2");
        ActivityInstance act = grantActivity("g7", Map.of("Rol", "Director (proiect național)", "Nume Proiect", "X"));

        assertEquals(2.0, service.calculateActivityScores(List.of(act), dV).get("g7").getAuthorScore(), 1e-9);
    }

    @Test
    void editionsMultiplierDerivesFromYearRangeFields() {
        // 20 years on the SYNASC organizing committee = ONE entry: An_inceput/An_sfarsit derive
        // N_editii, and the flat per-event formula multiplies exactly.
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator dVii = indicator("GENERIC_ACTIVITY", "(Rol == 'Membru' ? 1 : 2) * N_editii");
        ActivityInstance act = editionsActivity("e1", Map.of(
                "Rol", "Membru", "An_inceput", "2005", "An_sfarsit", "2024"));

        assertEquals(20.0, service.calculateActivityScores(List.of(act), dVii).get("e1").getAuthorScore(), 1e-9);
    }

    @Test
    void editionsMultiplierDefaultsToOneWithoutOrWithInvertedYearRange() {
        ActivityReportingService service = new ActivityReportingService(
                scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator(), scholardexProjectReadPort);
        Indicator dVii = indicator("GENERIC_ACTIVITY", "(Rol == 'Membru' ? 1 : 2) * N_editii");

        ActivityInstance noRange = editionsActivity("e2", Map.of("Rol", "Membru"));
        ActivityInstance inverted = editionsActivity("e3", Map.of(
                "Rol", "Membru", "An_inceput", "2024", "An_sfarsit", "2005"));

        assertEquals(1.0, service.calculateActivityScores(List.of(noRange), dVii).get("e2").getAuthorScore(), 1e-9);
        assertEquals(1.0, service.calculateActivityScores(List.of(inverted), dVii).get("e3").getAuthorScore(), 1e-9);
    }

    private ActivityInstance editionsActivity(String id, Map<String, String> fields) {
        Activity.Field role = new Activity.Field();
        role.setName("Rol");
        role.setNumber(false);
        Activity.Field start = new Activity.Field();
        start.setName("An_inceput");
        start.setNumber(true);
        Activity.Field end = new Activity.Field();
        end.setName("An_sfarsit");
        end.setNumber(true);
        Activity activity = new Activity();
        activity.setFields(List.of(role, start, end));

        ActivityInstance instance = new ActivityInstance();
        instance.setId(id);
        instance.setDate("2024-01-01");
        instance.setActivity(activity);
        instance.setFields(fields);
        instance.setReferenceFields(Map.of());
        return instance;
    }

    private static ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse project(Long budget, String funder) {
        return new ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse(
                "sproj_1", "PN-CODE", null, "Title", funder, "Dir", 2017, 2018, "UVT", budget);
    }

    private ActivityInstance grantActivityWithProject(String id, Map<String, String> fields, String projectRef) {
        ActivityInstance instance = grantActivity(id, fields);
        instance.setReferenceFields(Map.of(Activity.ReferenceField.PROJECT_GRANT_ID, projectRef));
        return instance;
    }

    private ActivityInstance physicsActivity(String id, String nAutori) {
        Activity.Field n = new Activity.Field();
        n.setName("N_autori");
        n.setNumber(true);
        Activity activity = new Activity();
        activity.setFields(List.of(n));
        ActivityInstance instance = new ActivityInstance();
        instance.setId(id);
        instance.setDate("2024-01-01");
        instance.setActivity(activity);
        instance.setFields(Map.of("N_autori", nAutori));
        instance.setReferenceFields(Map.of());
        return instance;
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
        Activity.Field interval = new Activity.Field();
        interval.setName("Interval_buget");
        interval.setNumber(false);

        Activity activity = new Activity();
        activity.setFields(List.of(budget, role, project, interval));

        ActivityInstance instance = new ActivityInstance();
        instance.setId(id);
        instance.setDate("2024-09-01");
        instance.setActivity(activity);
        instance.setFields(fields);
        instance.setReferenceFields(Map.of());
        return instance;
    }
}
