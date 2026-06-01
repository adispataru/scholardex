package ro.uvt.pokedex.core.model.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.scoring.Selector;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the {@code getEffective*} helpers on {@link Indicator} that bridge legacy and
 * v1 fields during the H52 migration. The contract every helper obeys: "if the v1 field is
 * set, return it; otherwise synthesise from the legacy field."
 */
class IndicatorEffectiveAccessorsTest {

    // ---------- Effective kind ----------

    @Test
    void effectiveKindUsesV1FieldWhenPresent() {
        Indicator ind = new Indicator();
        ind.setKind(new IndicatorKind.Publications(AuthorRole.MAIN, ScoringStrategy.IMPACT_FACTOR));
        // Legacy fields also populated with mismatched values — v1 wins.
        ind.setOutputType(Indicator.Type.CITATIONS_EXCLUDE_SELF);
        ind.setScoringStrategy(Indicator.Strategy.RIS);

        IndicatorKind effective = ind.getEffectiveKind();
        assertInstanceOf(IndicatorKind.Publications.class, effective);
        assertEquals(AuthorRole.MAIN, ((IndicatorKind.Publications) effective).role());
        assertEquals(ScoringStrategy.IMPACT_FACTOR, effective.strategy());
    }

    @Test
    void effectiveKindFallsBackToLegacyWhenV1Unset() {
        Indicator ind = new Indicator();
        ind.setOutputType(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR);
        ind.setScoringStrategy(Indicator.Strategy.IMPACT_FACTOR);

        IndicatorKind effective = ind.getEffectiveKind();
        assertInstanceOf(IndicatorKind.Publications.class, effective);
        assertEquals(AuthorRole.MAIN, ((IndicatorKind.Publications) effective).role());
    }

    @Test
    void effectiveKindReturnsNullWhenNothingSet() {
        // Defensive: a brand-new Indicator() before any field is populated must not throw.
        assertNull(new Indicator().getEffectiveKind());
    }

    // ---------- Effective yearRange ----------

    @Test
    void effectiveYearRangeUsesV1FieldWhenPresent() {
        Indicator ind = new Indicator();
        ind.setYearRangeSpec(new YearRangeSpec.Absolute(2015, 2025));
        ind.setYearRange("*");  // legacy mismatched on purpose

        assertEquals(new YearRangeSpec.Absolute(2015, 2025), ind.getEffectiveYearRange());
    }

    @Test
    void effectiveYearRangeParsesLegacyStringFallback() {
        Indicator ind = new Indicator();
        ind.setYearRange("2018->2025");
        assertEquals(new YearRangeSpec.Absolute(2018, 2025), ind.getEffectiveYearRange());
    }

    @Test
    void effectiveYearRangeDefaultsToAllYearsWhenLegacyNull() {
        // 37 of 42 production indicators use "*"; null/blank carry the same intent.
        assertEquals(new YearRangeSpec.AllYears(), new Indicator().getEffectiveYearRange());
    }

    // ---------- Effective scoreYearRange ----------

    @Test
    void effectiveScoreYearRangeUsesV1FieldWhenPresent() {
        Indicator ind = new Indicator();
        ind.setScoreYearRangeSpec(new ScoreYearRangeSpec.Absolute(2019, 2023));
        ind.setScoreYearRange("IY");  // mismatched legacy

        assertEquals(new ScoreYearRangeSpec.Absolute(2019, 2023), ind.getEffectiveScoreYearRange());
    }

    @Test
    void effectiveScoreYearRangeParsesItemYearFromLegacy() {
        // The dominant production value: 31 / 42 indicators are "IY".
        Indicator ind = new Indicator();
        ind.setScoreYearRange("IY");
        assertEquals(new ScoreYearRangeSpec.ItemYear(), ind.getEffectiveScoreYearRange());
    }

    @Test
    void effectiveScoreYearRangeDefaultsToItemYearWhenLegacyNull() {
        assertEquals(new ScoreYearRangeSpec.ItemYear(), new Indicator().getEffectiveScoreYearRange());
    }

    // ---------- Effective selector ----------

    @Test
    void effectiveSelectorUsesV1FieldWhenPresent() {
        Indicator ind = new Indicator();
        ind.setSelectorSpec(new Selector.TopN(10));
        ind.setSelector(Indicator.Selector.ALL); // mismatched legacy

        assertEquals(new Selector.TopN(10), ind.getEffectiveSelector());
    }

    @Test
    void effectiveSelectorFallsBackToLegacy() {
        Indicator ind = new Indicator();
        ind.setSelector(Indicator.Selector.TOP_10);
        assertEquals(new Selector.TopN(10), ind.getEffectiveSelector());

        ind.setSelector(Indicator.Selector.ALL);
        assertEquals(new Selector.All(), ind.getEffectiveSelector());
    }

    @Test
    void effectiveSelectorDefaultsToAllWhenLegacyNull() {
        // 30 of 42 indicators have selector=null; the dominant intent is "all items".
        assertEquals(new Selector.All(), new Indicator().getEffectiveSelector());
    }

    // ---------- The v1 fields don't trip Lombok-generated equals/hashCode ----------

    @Test
    void twoBareIndicatorsAreEqualByDefault() {
        // Smoke test that adding the v1 fields didn't accidentally split @Data's
        // equals/hashCode in a way that breaks the existing test suites.
        assertEquals(new Indicator(), new Indicator());
        assertEquals(new Indicator().hashCode(), new Indicator().hashCode());
    }
}
