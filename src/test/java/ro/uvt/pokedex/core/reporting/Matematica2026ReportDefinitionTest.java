package ro.uvt.pokedex.core.reporting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H145 — "FV Matematică 2026" against OM 3019/2025, Comisia 1: C1/C2 count citations from M1/M2 journals "care citează
 * articole științifice din lista A" (the candidate's articles in L = SCIE without fee), and leave out only the citations
 * from articles the candidate signed.
 */
class Matematica2026ReportDefinitionTest {

    private static SeedReportDefinition mate;

    @BeforeAll
    static void load() {
        mate = new SeedReportDefinition("FV Matematică 2026", "Mate26_");
    }

    private static Map<String, Object> citation(boolean citedInScie, boolean citedFee, String quartile) {
        Map<String, Object> v = new HashMap<>();
        v.put("citedScieIndexed", citedInScie);
        v.put("citedFeeJournal", citedFee);
        v.put("scieIndexed", true);
        v.put("feeJournal", false);
        v.put("Q", quartile);
        return v;
    }

    @Test
    void theCitationMinimumsAreTheStandards() {
        mate.assertThresholds("C1 — citări", 16.0, 32.0, 20.0);
        mate.assertThresholds("C2 — citări", 8.0, 16.0, 10.0);
    }

    @Test
    void aCitationCountsOnlyForAnArticleOfListA() {
        assertEquals(1.0, mate.evalFormula("C1", citation(true, false, "Q3")), 1e-9);
        assertEquals(0.0, mate.evalFormula("C1", citation(false, false, "Q1")), 1e-9, "the cited article is not in an SCIE journal");
        assertEquals(0.0, mate.evalFormula("C1", citation(true, true, "Q1")), 1e-9, "the cited article's journal charges a fee");
        assertEquals(1.0, mate.evalFormula("C2", citation(true, false, "Q2")), 1e-9);
        assertEquals(0.0, mate.evalFormula("C2", citation(true, false, "Q3")), 1e-9, "M2 is Q1–Q2");
        assertEquals(0.0, mate.evalFormula("C2", citation(false, false, "Q1")), 1e-9);
    }

    @Test
    void onlyTheCitationsFromArticlesTheCandidateSignedAreLeftOut() {
        assertEquals("CANDIDATE_ONLY", mate.kindField("C1", "policy"));
        assertEquals("CANDIDATE_ONLY", mate.kindField("C2", "policy"));
    }

    @Test
    void everyIndicatorIsDescribed() throws IOException {
        mate.assertDescribedBy("matematica-2026.json");
    }
}
