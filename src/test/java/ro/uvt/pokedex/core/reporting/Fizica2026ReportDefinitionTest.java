package ro.uvt.pokedex.core.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ro.uvt.pokedex.core.reporting.SeedReportDefinition.fields;

/**
 * H145 — "FV Fizică 2026" against OM 3019/2025, Comisia 3 (Fizică): the minimums of each section, original articles
 * at I and P (reviews belong to A2), proceedings papers outside A2/A5 (precizarea 5), patents only with their authors,
 * and the citations of articles the candidate signed left out of C.
 */
class Fizica2026ReportDefinitionTest {

    private static SeedReportDefinition fiz;

    @BeforeAll
    static void load() {
        fiz = new SeedReportDefinition("FV Fizică 2026", "Fiz26_");
    }

    @AfterEach
    void resetRegistries() {
        SeedReportDefinition.resetRegistries();
    }

    @Test
    void theMinimumsAreTheStandards() {
        fiz.assertThresholds("A — activitate", 1.0, 2.0, 2.0); // Anexa 2 p. 144, Anexa 3 p. 275: A ≥ 2
        fiz.assertThresholds("I — articole", 2.0, 4.0, 4.0);
        fiz.assertThresholds("P — prim autor", 2.0, 4.0, 4.0);
        fiz.assertThresholds("C — citări", 20.0, 40.0, 40.0);
        fiz.assertThresholds("h — indice", 5.0, 10.0, 10.0);
        fiz.assertThresholds("T — punctaj", 5.0, 12.5, 11.5);
    }

    @Test
    void aReviewCountsAtA2NotAtIOrP() {
        Map<String, Object> review = Map.of("docType", "re", "S", 2.0, "Nef", 2.0);
        Map<String, Object> article = Map.of("docType", "ar", "S", 2.0, "Nef", 2.0);
        assertEquals(0.0, fiz.evalFormula("I", review), 1e-9);
        assertEquals(0.0, fiz.evalFormula("P", review), 1e-9);
        assertEquals(0.5, fiz.evalFormula("A2_reviewuri", review), 1e-9);
        assertEquals(1.0, fiz.evalFormula("I", article), 1e-9);
        assertEquals(2.0, fiz.evalFormula("P", article), 1e-9);
        assertEquals(0.0, fiz.evalFormula("A2_reviewuri", article), 1e-9);
    }

    @Test
    void aProceedingsPaperIsNoBookChapter() {
        assertEquals(1.0, fiz.evalFormula("A2_capitole", Map.of("docType", "ch", "wosBookPublisher", true, "proceedings", false, "Nef", 1.0)), 1e-9);
        assertEquals(0.0, fiz.evalFormula("A2_capitole", Map.of("docType", "ch", "wosBookPublisher", true, "proceedings", true, "Nef", 1.0)), 1e-9);
        assertEquals(0.2, fiz.evalFormula("A5", Map.of("docType", "ch", "wosBookPublisher", false, "proceedings", false, "Nef", 1.0)), 1e-9);
        assertEquals(0.0, fiz.evalFormula("A5", Map.of("docType", "ch", "wosBookPublisher", false, "proceedings", true, "Nef", 1.0)), 1e-9);
    }

    @Test
    void aPatentCountsByItsGrantedCodeAndOnlyWithItsAuthors() {
        assertEquals(3.0, fiz.activity("A7", fields("Cod brevet", "EP 1234567 B1", "N_autori", "1")), 1e-9);
        assertEquals(0.25, fiz.activity("A8", fields("Cod brevet", "RO 123456 B1", "N_autori", "2")), 1e-9);
        assertEquals(0.0, fiz.activity("A7", fields("Cod brevet", "EP 1234567 B1")), 1e-9, "no authors, no points");
        assertEquals(0.0, fiz.activity("A8", fields("Cod brevet", "RO 123456 B1", "N_autori", "0")), 1e-9);
    }

    @Test
    void onlyTheCitationsOfArticlesTheCandidateSignedAreLeftOut() {
        // "Nu se iau în considerare citările provenind din articole care au ca autor sau coautor candidatul"
        assertEquals("CANDIDATE_ONLY", fiz.kindField("C", "policy"));
    }

    @Test
    void everyIndicatorIsDescribed() throws IOException {
        fiz.assertDescribedBy("fizica-2026.json");
    }
}
