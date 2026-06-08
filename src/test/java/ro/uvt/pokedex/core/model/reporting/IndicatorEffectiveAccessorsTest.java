package ro.uvt.pokedex.core.model.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.scoring.Selector;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the {@code getEffective*} helpers on {@link Indicator}. Contract:
 * there is exactly ONE storage location per shape ({@code kind},
 * {@code yearRangeSpec}, {@code scoreYearRangeSpec}, {@code selectorSpec}).
 * Admin form string binding lives in the controller DTO, not in the domain model.
 */
class IndicatorEffectiveAccessorsTest {

    // ---------- Effective kind ----------

    @Test
    void effectiveKindReflectsTypedKind() {
        Indicator ind = new Indicator();
        ind.setKind(new IndicatorKind.Publications(AuthorRole.MAIN, ScoringStrategy.IMPACT_FACTOR));

        IndicatorKind effective = ind.getEffectiveKind();
        assertInstanceOf(IndicatorKind.Publications.class, effective);
        assertEquals(AuthorRole.MAIN, ((IndicatorKind.Publications) effective).role());
        assertEquals(ScoringStrategy.IMPACT_FACTOR, effective.strategy());
    }

    @Test
    void legacyFormSettersAreNotPartOfTheDomainModel() {
        assertThrows(NoSuchMethodException.class, () -> Indicator.class.getDeclaredMethod("setOutputType", String.class));
        assertThrows(NoSuchMethodException.class, () -> Indicator.class.getDeclaredMethod("setScoringStrategy", String.class));
    }

    @Test
    void effectiveKindReturnsNullWhenNothingSet() {
        // Defensive: a brand-new Indicator() before any field is populated must not throw.
        assertNull(new Indicator().getEffectiveKind());
    }

    // ---------- Effective yearRange ----------

    @Test
    void effectiveYearRangeReflectsTypedSpec() {
        Indicator ind = new Indicator();
        ind.setYearRangeSpec(new YearRangeSpec.Absolute(2015, 2025));
        assertEquals(new YearRangeSpec.Absolute(2015, 2025), ind.getEffectiveYearRange());
    }

    @Test
    void legacyYearRangeSetterIsNotPartOfTheDomainModel() {
        assertThrows(NoSuchMethodException.class, () -> Indicator.class.getDeclaredMethod("setYearRange", String.class));
    }

    @Test
    void effectiveYearRangeDefaultsToAllYearsWhenUnset() {
        // 37 of 42 production indicators use "*"; null/blank carry the same intent.
        assertEquals(new YearRangeSpec.AllYears(), new Indicator().getEffectiveYearRange());
    }

    // ---------- Effective scoreYearRange ----------

    @Test
    void effectiveScoreYearRangeReflectsTypedSpec() {
        Indicator ind = new Indicator();
        ind.setScoreYearRangeSpec(new ScoreYearRangeSpec.Absolute(2019, 2023));
        assertEquals(new ScoreYearRangeSpec.Absolute(2019, 2023), ind.getEffectiveScoreYearRange());
    }

    @Test
    void legacyScoreYearRangeSetterIsNotPartOfTheDomainModel() {
        assertThrows(NoSuchMethodException.class, () -> Indicator.class.getDeclaredMethod("setScoreYearRange", String.class));
    }

    @Test
    void effectiveScoreYearRangeDefaultsToItemYearWhenUnset() {
        assertEquals(new ScoreYearRangeSpec.ItemYear(), new Indicator().getEffectiveScoreYearRange());
    }

    // ---------- Effective selector ----------

    @Test
    void effectiveSelectorReflectsTypedSpec() {
        Indicator ind = new Indicator();
        ind.setSelectorSpec(new Selector.TopN(10));
        assertEquals(new Selector.TopN(10), ind.getEffectiveSelector());
    }

    @Test
    void legacySelectorSetterIsNotPartOfTheDomainModel() {
        assertThrows(NoSuchMethodException.class, () -> Indicator.class.getDeclaredMethod("setSelector", String.class));
    }

    @Test
    void effectiveSelectorDefaultsToAllWhenUnset() {
        // 30 of 42 indicators have selector=null; the dominant intent is "all items".
        assertEquals(new Selector.All(), new Indicator().getEffectiveSelector());
    }

    // ---------- equals/hashCode sanity ----------

    @Test
    void twoBareIndicatorsAreEqualByDefault() {
        // Smoke test that the v1 typed fields don't accidentally split @Data's
        // equals/hashCode in a way that breaks the existing test suites.
        assertEquals(new Indicator(), new Indicator());
        assertEquals(new Indicator().hashCode(), new Indicator().hashCode());
    }
}
