package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H60: the article-inclusion filter (yearRangeSpec) exercised through the GenericCount count path — the "total"
 * author-score is the number of publications that survived the year filter.
 */
class ScientificProductionYearInclusionTest {

    private final ScientificProductionService service = new ScientificProductionService(
            Mockito.mock(ScoringFactoryService.class), Mockito.mock(FormulaEvaluator.class));

    private static ScoringPublication pub(String id, int year) {
        return new ScoringPublication(id, null, null, year + "-06-01", "ar", null,
                List.of(), 1, null, null, "Title-" + id, 0, Set.of());
    }

    private static Indicator genericCount(YearRangeSpec yearRange) {
        Indicator ind = new Indicator();
        ind.setKind(new IndicatorKind.GenericCount());
        ind.setYearRangeSpec(yearRange);
        return ind;
    }

    private double count(Indicator ind, List<ScoringPublication> pubs, Integer referenceYear) {
        Map<String, Score> r = ScoringReferenceYearContext.with(referenceYear,
                () -> service.calculateScientificProductionScore(pubs, ind));
        return r.get("total").getAuthorScore();
    }

    private final List<ScoringPublication> pubs = List.of(pub("old", 2015), pub("a", 2024), pub("b", 2025));

    @Test
    void previousNYearsExcludesPublicationsOutsideTheRollingWindow() {
        // referenceYear 2026, window [2019..2025] → old(2015) dropped, 2024 + 2025 kept.
        assertEquals(2.0, count(genericCount(new YearRangeSpec.PreviousNYears(7)), pubs, 2026));
    }

    @Test
    void previousNYearsIsNoOpWithoutAReferenceYearInScope() {
        // Legacy unenforced behaviour: a PreviousNYears window can't resolve without referenceYear → keep all.
        assertEquals(3.0, count(genericCount(new YearRangeSpec.PreviousNYears(7)), pubs, null));
    }

    @Test
    void absoluteYearRangeFiltersWithoutNeedingAReferenceYear() {
        assertEquals(2.0, count(genericCount(new YearRangeSpec.Absolute(2019, 2025)), pubs, null));
    }

    @Test
    void allYearsIncludesEverything() {
        assertEquals(3.0, count(genericCount(new YearRangeSpec.AllYears()), pubs, 2026));
    }
}
