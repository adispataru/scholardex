package ro.uvt.pokedex.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H135 — pins the committed UEFISCDI eligibility reports to the 2026 packages: «Eligibilitate PD 2026»
 * (PN-IV-RU-SC-PD-2026-1, Anexa 2) and «Eligibilitate TE 2026» (PN-IV-RU-SC-TE-2026-2, Anexa 2). The
 * earlier «Eligibilitate PD» (JIF approximation) and «Eligibilitate Tinere Echipe» (2015–2025 rules) are gone.
 */
class Uefiscdi2026EligibilityDefinitionTest {

    private static SeedReportDefinition pd;
    private static SeedReportDefinition te;

    @BeforeAll
    static void load() {
        pd = new SeedReportDefinition("Eligibilitate PD 2026", "PD26_");
        te = new SeedReportDefinition("Eligibilitate TE 2026", "TE26_");
    }

    private static JsonNode criterion(SeedReportDefinition def, int index) {
        return def.report().get("criteria").get(index);
    }

    private static double threshold(JsonNode criterion, String position) {
        for (JsonNode t : criterion.get("thresholds")) {
            if (position.equals(t.get("position").asText())) {
                return t.get("value").asDouble();
            }
        }
        throw new AssertionError(criterion.get("name").asText() + " has no threshold for " + position);
    }

    private static List<Integer> indices(JsonNode criterion) {
        List<Integer> out = new ArrayList<>();
        criterion.get("indicatorIndices").forEach(i -> out.add(i.asInt()));
        return out;
    }

    // ------------------------------------------------------------------ PD 2026

    @Test
    void pdDirectorNeedsThreeWosWorksAndOneQ1Q2OrCoreAEquivalent() {
        assertEquals(7, pd.report().get("criteria").size());
        JsonNode works = criterion(pd, 0);
        JsonNode topHalf = criterion(pd, 1);
        assertEquals(List.of(0), indices(works));
        assertEquals(3.0, threshold(works, "ASIST_UNIV"));
        assertEquals(List.of(1, 2), indices(topHalf), "the journal Q1/Q2 work and the CORE A/A* equivalent add up");
        assertEquals(1.0, threshold(topHalf, "LECT_UNIV"));
        assertEquals("FIRST_OR_CORRESPONDING", pd.kindField("Dir_CORE_A_echiv", "role"),
                "the CORE equivalent needs the candidate as first or corresponding author");
        assertEquals("ALL", pd.kindField("Dir_Q1_Q2", "role"), "Anexa 6(e): all authors count in the Informatică categories");
        assertEquals(2016, pd.indicator("Dir_Q1_Q2").get("yearRangeSpec").get("from").asInt());
    }

    @Test
    void pdMentorQ2DistinctJournalsRuleAppliesOnlyWithFewerThanThreeQ1Works() {
        // Anexa 2, mentor: "Dacă dintre articolele de referință 3 au fost publicate în reviste Q2, minimum 2
        // dintre acestea trebuie să fie din reviste diferite" — with 3 Q1 works the reference set needs no 3 Q2.
        JsonNode q1 = criterion(pd, 3);
        JsonNode q2Distinct = criterion(pd, 4);
        JsonNode q1AtLeastThree = criterion(pd, 6);
        assertEquals(2.0, threshold(q1, "PROF_UNIV"));
        assertEquals(List.of(4), indices(q1AtLeastThree), "the new criterion reads the same Q1 indicator");
        assertEquals(3.0, threshold(q1AtLeastThree, "PROF_UNIV"));
        assertEquals("DistinctForums", pd.indicator("Mentor_Q2_reviste_distincte").get("selectorSpec").get("_class").asText()
                .substring("ro.uvt.pokedex.core.model.reporting.scoring.Selector$".length()));
        assertEquals(2.0, threshold(q2Distinct, "PROF_UNIV"));

        JsonNode mentor = pd.report().get("perspectives").get(1);
        assertEquals("Mentor — verdict", mentor.get("name").asText());
        JsonNode all = mentor.get("composition").get("all");
        assertEquals(4, all.size());
        assertEquals(2, all.get(0).get("criterion").asInt());
        assertEquals(3, all.get(1).get("criterion").asInt());
        assertEquals(5, all.get(2).get("criterion").asInt());
        JsonNode any = all.get(3).get("any");
        assertEquals(6, any.get(0).get("criterion").asInt(), "Q1 ≥ 3 ...");
        assertEquals(4, any.get(1).get("criterion").asInt(), "... or 2 distinct Q2 journals");
        assertEquals(List.of(0, 1), pd.criteriaOf(0), "the director verdict is the two director criteria");
    }

    @Test
    void pdNothingContributesToATotalAndTheReportIsUefiscdi() {
        pd.report().get("criteria").forEach(c -> assertFalse(c.path("contributesToTotal").asBoolean(false), c.get("name").asText()));
        assertEquals("UEFISCDI", pd.report().get("authority").asText());
    }

    // ------------------------------------------------------------------ TE 2026

    @Test
    void teDirectorNeedsThreeTopHalfWorksWithAtMostTwoFromCoreConferences() {
        assertEquals(4, te.report().get("criteria").size());
        JsonNode works = criterion(te, 0);
        assertEquals(List.of(0), indices(works));
        assertEquals(3.0, threshold(works, "CS_III"), "research positions apply too");
        assertEquals(3.0, threshold(works, "ASIST_UNIV"));
        JsonNode addition = works.get("thresholdCapAdditions").get(0);
        assertEquals(1, addition.get("indicatorIndex").asInt(), "the CORE A/A* indicator is added ...");
        assertEquals(2.0, Math.floor(addition.get("percent").asDouble() / 100.0 * 3.0 + 1e-6),
                "... capped at 2 of the 3 works (66.67 % of the threshold)");
        assertEquals("FIRST_OR_CORRESPONDING", te.kindField("Dir_CORE_A_echiv", "role"));
        assertEquals("CS_CONFERENCE", te.kindField("Dir_CORE_A_echiv", "strategy"));
    }

    @Test
    void teWorksAreTheMentorShapeOfPd2015To2026OnThe6eCategories() {
        assertEquals("PD_WOS", te.kindField("Dir_Q1Q2", "strategy"), "SCIE/SSCI/AHCI only, AIS quartile, JCR-2024 cap");
        assertEquals("ALL", te.kindField("Dir_Q1Q2", "role"));
        assertEquals(2015, te.indicator("Dir_Q1Q2").get("yearRangeSpec").get("from").asInt());
        assertEquals(2026, te.indicator("Dir_Q1Q2").get("yearRangeSpec").get("to").asInt());
        assertTrue(te.indicator("Dir_Q1Q2").get("formula").asText().contains("\"cp\""), "proceedings papers count for TE");
        Set<String> categories = te.domainCategories("Dir_Q1Q2");
        assertEquals(17, categories.size());
        assertTrue(categories.contains("MATHEMATICS - SCIE"));
        assertTrue(categories.contains("ECONOMICS - SSCI"));
        assertTrue(categories.contains("COMPUTER SCIENCE, THEORY & METHODS - SCIE"));
        assertTrue(categories.stream().noneMatch(c -> c.endsWith("ESCI")), "ESCI never qualifies");
    }

    @Test
    void teNotesDistinctJournalsAndArticleTypeAndTheVerdictReadsAllThree() {
        JsonNode distinct = criterion(te, 1);
        JsonNode articles = criterion(te, 2);
        JsonNode core = criterion(te, 3);
        assertEquals(List.of(2), indices(distinct));
        assertEquals(2.0, threshold(distinct, "LECT_UNIV"));
        assertEquals("DistinctForums", te.indicator("Dir_reviste_distincte").get("selectorSpec").get("_class").asText()
                .substring("ro.uvt.pokedex.core.model.reporting.scoring.Selector$".length()));
        assertEquals(List.of(3), indices(articles));
        assertEquals(2.0, threshold(articles, "LECT_UNIV"));
        assertFalse(te.indicator("Dir_tip_article").get("formula").asText().contains("\"re\""), "strictly type article");
        assertEquals(0, core.get("thresholds").size(), "the CORE count is informational");
        assertEquals(List.of(0, 1, 2), te.criteriaOf(0));
        assertEquals("UEFISCDI", te.report().get("authority").asText());
        te.report().get("criteria").forEach(c -> assertFalse(c.path("contributesToTotal").asBoolean(false)));
    }

    // ------------------------------------------------------------------ what is gone

    @Test
    void theSupersededReportsAndIndicatorsAreNotInTheSeed() {
        for (String title : List.of("Eligibilitate PD", "Eligibilitate Tinere Echipe")) {
            assertNull(SeedReportDefinition.find(title), title + " is superseded and must not be seeded");
        }
    }
}
