package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Comisia28RulesTest {

    @Test
    void anIndicatorWithoutAFlagIsNotA2026Indicator() {
        assertTrue(Comisia28Rules.of(new Indicator()).isEmpty());
        assertTrue(Comisia28Rules.of(null).isEmpty());
    }

    @Test
    void thePsychologyFlagSelectsThePsychologyValues() {
        Indicator indicator = new Indicator();
        indicator.setPsihologie2026(true);

        Comisia28Rules rules = Comisia28Rules.of(indicator).orElseThrow();

        assertEquals(Comisia28Rules.PSIHOLOGIE, rules);
        assertEquals(1.0, rules.impactFactorThreshold());
        assertTrue(rules.aboveMedianException());
        assertFalse(rules.recognisedDatabases().contains("DOAJ"), "DOAJ is not on the Psychology list");
    }

    @Test
    void psychologyCountsTheThresholdItselfAndTheTwoQuartilesAboveTheMedian() {
        Comisia28Rules rules = Comisia28Rules.PSIHOLOGIE;
        assertTrue(rules.countsOnStrictPath(1.0, "Q4"));
        assertTrue(rules.countsOnStrictPath(0.4, "Q2"));
        assertTrue(rules.countsOnStrictPath(0.4, "Q1"));
        assertFalse(rules.countsOnStrictPath(0.99, "Q3"));
        assertFalse(rules.countsOnStrictPath(0.99, null));
    }

    @Test
    void theEducationalSciencesFlagSelectsItsOwnValues() {
        Indicator indicator = new Indicator();
        indicator.setStiinteEducatiei2026(true);

        Comisia28Rules rules = Comisia28Rules.of(indicator).orElseThrow();

        assertEquals(Comisia28Rules.STIINTE_EDUCATIEI, rules);
        assertEquals(0.10, rules.impactFactorThreshold());
        assertFalse(rules.aboveMedianException());
        assertTrue(rules.recognisedDatabases().contains("DOAJ"));
        assertFalse(rules.recognisedDatabases().contains("CROSSREF"), "left out until the faculty decides");
    }

    @Test
    void educationalSciencesCountTheThresholdAndNothingBelowItWhateverTheQuartile() {
        Comisia28Rules rules = Comisia28Rules.STIINTE_EDUCATIEI;
        assertTrue(rules.countsOnStrictPath(0.10, "Q4"));
        assertTrue(rules.countsOnStrictPath(2.4, null));
        assertFalse(rules.countsOnStrictPath(0.09, "Q1"));
    }

    @Test
    void wideningAddsTheEsciEditionOfEveryCategoryAndLeavesTheStoredDomainAlone() {
        Domain stored = new Domain();
        stored.setId("Psychology");
        stored.setName("Psychology");
        stored.setWosCategories(new ArrayList<>(List.of(
                "PSYCHOLOGY - SCIE", "PSYCHOLOGY, CLINICAL - SSCI", "PSYCHOLOGY, CLINICAL - SCIE")));

        Domain widened = Comisia28Rules.withEmergingSources(stored);

        assertEquals(List.of("PSYCHOLOGY - SCIE", "PSYCHOLOGY, CLINICAL - SSCI", "PSYCHOLOGY, CLINICAL - SCIE",
                "PSYCHOLOGY - ESCI", "PSYCHOLOGY, CLINICAL - ESCI"), widened.getWosCategories());
        assertEquals("Psychology", widened.getName());
        assertEquals(3, stored.getWosCategories().size(), "the stored domain is shared with the 2016 report");
    }

    @Test
    void wideningToleratesAMissingDomain() {
        assertNull(Comisia28Rules.withEmergingSources(null));
    }
}
