package ro.uvt.pokedex.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.reporting.ScoringSubjectContext;
import ro.uvt.pokedex.core.service.reporting.UefiscdiPublisherListService;
import ro.uvt.pokedex.core.service.reporting.UefiscdiPublisherSupport;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H136 — pins the committed social/economic eligibility reports to Anexa 2 of the 2026 packages
 * («Pentru Științele sociale și Științele economice»): P = ΣA + ΣC + ΣK with A = 70 × AIS / N, C = 60 / N,
 * K = 30 / N, thresholds PD director 30/15, PD mentor 100/50, TE director 50/25, books and chapters only at the
 * publishers of Anexa 7c.
 */
class Uefiscdi2026SocialEligibilityDefinitionTest {

    private static SeedReportDefinition pd;
    private static SeedReportDefinition mentor;
    private static SeedReportDefinition te;

    @BeforeAll
    static void load() {
        pd = new SeedReportDefinition("Eligibilitate PD 2026 — științe sociale și economice — Director", "UEF26SE_");
        mentor = new SeedReportDefinition("Eligibilitate PD 2026 — științe sociale și economice — Mentor", "UEF26SE_");
        te = new SeedReportDefinition("Eligibilitate TE 2026 — științe sociale și economice", "UEF26SE_");
        UefiscdiPublisherSupport.register(UefiscdiPublisherListService.readFixture());
    }

    @AfterAll
    static void unregister() {
        UefiscdiPublisherSupport.register(List.of());
    }

    /** Short indicator names of a criterion, by index (the names share the "Director:"/"Mentor:" prefix). */
    private static Set<String> members(SeedReportDefinition def, int criterion) {
        Set<String> names = new java.util.HashSet<>();
        List<String> all = def.reportIndicatorNames();
        def.report().get("criteria").get(criterion).get("indicatorIndices")
                .forEach(i -> names.add(all.get(i.asInt()).substring("UEF26SE_".length())));
        return names;
    }

    private static double threshold(JsonNode criterion, String position) {
        for (JsonNode t : criterion.get("thresholds")) {
            if (position.equals(t.get("position").asText())) {
                return t.get("value").asDouble();
            }
        }
        throw new AssertionError(criterion.get("name").asText() + " has no threshold for " + position);
    }

    // ------------------------------------------------------------------ thresholds and members

    @Test
    void pdDirectorNeeds30PointsOfWhich15FromArticlesAndTheMentor100And50() {
        assertEquals(2, pd.report().get("criteria").size());
        assertEquals(2, mentor.report().get("criteria").size());
        JsonNode dirP = pd.report().get("criteria").get(0);
        JsonNode dirA = pd.report().get("criteria").get(1);
        JsonNode menP = mentor.report().get("criteria").get(0);
        JsonNode menA = mentor.report().get("criteria").get(1);
        for (String position : List.of("ASIST_UNIV", "PROF_UNIV", "CS_III", "ASIST_C")) {
            assertEquals(30.0, threshold(dirP, position), "one bar for every position");
            assertEquals(15.0, threshold(dirA, position));
            assertEquals(100.0, threshold(menP, position));
            assertEquals(50.0, threshold(menA, position));
        }
        assertEquals(Set.of("Dir_A", "Dir_C", "Dir_K"), members(pd, 0));
        assertEquals(Set.of("Dir_A"), members(pd, 1));
        assertEquals(Set.of("2015_A", "2015_C", "2015_K"), members(mentor, 0));
        assertEquals(Set.of("2015_A"), members(mentor, 1));
        assertEquals(List.of(0, 1), pd.criteriaOf(0));
        assertEquals(List.of(0, 1), mentor.criteriaOf(0));
        assertEquals("UEFISCDI", pd.report().get("authority").asText());
        assertEquals("UEFISCDI", mentor.report().get("authority").asText());
        assertEquals(8, pd.report().get("phdLimitYears").asInt());
        assertNull(mentor.report().get("phdLimitYears"));
        assertEquals(12, te.report().get("phdLimitYears").asInt());
    }

    @Test
    void teDirectorNeeds50PointsOfWhich25FromArticlesOver2015To2026() {
        assertEquals(2, te.report().get("criteria").size());
        assertEquals(50.0, threshold(te.report().get("criteria").get(0), "LECT_UNIV"));
        assertEquals(25.0, threshold(te.report().get("criteria").get(1), "LECT_UNIV"));
        assertEquals(Set.of("2015_A", "2015_C", "2015_K"), members(te, 0));
        assertEquals(Set.of("2015_A"), members(te, 1));
        assertEquals(List.of(0, 1), te.criteriaOf(0));
        assertEquals(2015, te.indicator("2015_A").get("yearRangeSpec").get("from").asInt());
        assertTrue(te.indicator("2015_A").get("yearRangeSpec").get("_class").asText().endsWith("AfterPhdAward"), "după obținerea titlului de doctor");
        assertEquals(2016, pd.indicator("Dir_A").get("yearRangeSpec").get("from").asInt(), "director PD approximates 'after PhD admission'");
        te.report().get("criteria").forEach(c -> assertFalse(c.path("contributesToTotal").asBoolean(false)));
    }

    // ------------------------------------------------------------------ the article points

    @Test
    void anArticleScores70TimesAisOverTheAuthorsOnlyInTheTopHalf() {
        assertEquals("PD_WOS", pd.kindField("Dir_A", "strategy"), "SCIE/SSCI/AHCI only, AIS of the publication year, JCR-2024 cap");
        assertEquals("ALL", pd.kindField("Dir_A", "role"), "'ca autor sau coautor' — no principal-author test");
        assertEquals(70.0 * 1.84 / 3, pd.evalFormula("Dir_A", Map.of("docType", "ar", "Q", "Q1", "AIS", 1.84, "N", 3)), 1e-9);
        assertEquals(70.0 * 0.6 / 2, pd.evalFormula("2015_A", Map.of("docType", "re", "Q", "Q2", "AIS", 0.6, "N", 2)), 1e-9);
        assertEquals(0.0, pd.evalFormula("Dir_A", Map.of("docType", "ar", "Q", "Q3", "AIS", 0.3, "N", 1)), "Q3 is not in the top half");
        assertEquals(0.0, pd.evalFormula("Dir_A", Map.of("docType", "cp", "Q", "Q1", "AIS", 2.0, "N", 1)), "strictly article or review");
        assertTrue(pd.domainCategories("Dir_A").isEmpty(), "H138: the stored base domain is empty; the chosen domain (11 or 12) applies");
        assertEquals("SOCIAL_ECONOMIC", pd.report().get("competitionFamily").asText());
        assertEquals("SOCIAL_ECONOMIC", te.report().get("competitionFamily").asText());
    }

    // ------------------------------------------------------------------ the declared books and chapters

    @Test
    void aBookAtAnAnexa7cPublisherScores60OverTheAuthorsAndAChapter30() {
        assertEquals(30.0, pd.activity("Dir_C", SeedReportDefinition.fields(
                "Titlu", "Economia comportamentală", "Tip", "Carte", "Editura", "Cambridge University Press",
                "ISBN", "978-1-108-00000-0", "N_autori", "2")), 1e-9);
        assertEquals(10.0, pd.activity("Dir_K", SeedReportDefinition.fields(
                "Titlu", "Un capitol", "Tip", "Capitol în volum colectiv", "Editura", "Routledge", "N_autori", "3")), 1e-9);
        assertEquals(60.0, pd.activity("2015_C", SeedReportDefinition.fields(
                "Titlu", "Singur autor", "Tip", "Carte", "Editura", "SAGE PUBLICATIONS")), 1e-9,
                "a missing author count means one author");
    }

    @Test
    void aMentorOrTeBookBeforeThePhdDoesNotCountWhenTheProfileHasTheYear() {
        // the test helper dates every declared item 2024
        Map<String, String> book = SeedReportDefinition.fields("Titlu", "Carte", "Tip", "Carte", "Editura", "Routledge", "N_autori", "1");
        assertEquals(60.0, ScoringSubjectContext.withPhdAwardYear(2020, () -> pd.activity("2015_C", book)), 1e-9, "PhD 2020, book 2024");
        assertEquals(60.0, ScoringSubjectContext.withPhdAwardYear(2024, () -> pd.activity("2015_C", book)), 1e-9, "the PhD year itself counts");
        assertEquals(0.0, ScoringSubjectContext.withPhdAwardYear(2025, () -> pd.activity("2015_C", book)), 1e-9, "before the PhD");
        assertEquals(60.0, pd.activity("2015_C", book), 1e-9, "no PhD year in the profile: the plain window");
        assertEquals(60.0, ScoringSubjectContext.withPhdAwardYear(2025, () -> pd.activity("Dir_C", book)), 1e-9,
                "the director's window is not PhD-anchored (admission, not award)");
    }

    @Test
    void aBookAtAPublisherOffTheListOrOfTheWrongKindScoresNothing() {
        assertEquals(0.0, pd.activity("Dir_C", SeedReportDefinition.fields(
                "Titlu", "Carte locală", "Tip", "Carte", "Editura", "Editura Universității de Vest", "N_autori", "1")));
        assertEquals(0.0, pd.activity("Dir_C", SeedReportDefinition.fields(
                "Titlu", "Capitol", "Tip", "Capitol în volum colectiv", "Editura", "Routledge", "N_autori", "1")),
                "the book indicator does not count a chapter");
        assertEquals(0.0, pd.activity("Dir_K", SeedReportDefinition.fields(
                "Titlu", "Carte", "Tip", "Carte", "Editura", "Routledge", "N_autori", "1")),
                "the chapter indicator does not count a book");
    }
}
