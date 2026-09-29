package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HIndexScoreSupportTest {

    private static Indicator withFormula(String formula) {
        Indicator indicator = new Indicator();
        indicator.setFormula(formula);
        return indicator;
    }

    @Test
    void theIdentityFormulaKeepsTheIndexSoPhysicsIsUnchanged() {
        assertEquals(12.0, HIndexScoreSupport.score(withFormula("S"), 12));
    }

    @Test
    void theSquaredFormulaGivesThePsihologie2026Points() {
        // Comisia 28, I13: hWoS × hWoS — the conferențiar threshold is 9 = 3².
        assertEquals(9.0, HIndexScoreSupport.score(withFormula("S*S"), 3));
        assertEquals(64.0, HIndexScoreSupport.score(withFormula("S * S"), 8));
    }

    @Test
    void aBlankOrMissingFormulaKeepsTheIndex() {
        assertEquals(5.0, HIndexScoreSupport.score(withFormula(null), 5));
        assertEquals(5.0, HIndexScoreSupport.score(withFormula("  "), 5));
        assertEquals(5.0, HIndexScoreSupport.score(null, 5));
    }

    @Test
    void aBrokenFormulaOrANonFiniteResultKeepsTheIndexInsteadOfScoringZero() {
        assertEquals(4.0, HIndexScoreSupport.score(withFormula("S * unknownVariable"), 4));
        assertEquals(4.0, HIndexScoreSupport.score(withFormula("S / 0.0"), 4));
    }

    @Test
    void integralScoresPrintWithoutDecimals() {
        assertEquals("9", HIndexScoreSupport.format(9.0));
        assertEquals("0", HIndexScoreSupport.format(0.0));
        assertEquals("2.25", HIndexScoreSupport.format(2.25));
    }
}
