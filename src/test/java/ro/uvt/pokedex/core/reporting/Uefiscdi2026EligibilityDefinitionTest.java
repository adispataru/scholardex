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
    private static SeedReportDefinition mentor;
    private static SeedReportDefinition te;

    @BeforeAll
    static void load() {
        // H137: one report per role — the director's and the mentor's standards are separate pages
        pd = new SeedReportDefinition("Eligibilitate PD 2026 — Director", "PD26_");
        mentor = new SeedReportDefinition("Eligibilitate PD 2026 — Mentor", "PD26_");
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

    private static List<Integer> toList(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(i -> out.add(i.asInt()));
        return out;
    }

    private static List<Integer> indices(JsonNode criterion) {
        List<Integer> out = new ArrayList<>();
        criterion.get("indicatorIndices").forEach(i -> out.add(i.asInt()));
        return out;
    }

    // ------------------------------------------------------------------ PD 2026

    @Test
    void pdDirectorNeedsThreeWosWorksAndOneQ1Q2OrCoreAEquivalent() {
        assertEquals(2, pd.report().get("criteria").size());
        JsonNode works = criterion(pd, 0);
        JsonNode topHalf = criterion(pd, 1);
        assertEquals(List.of(0), indices(works));
        assertEquals(3.0, threshold(works, "ASIST_UNIV"));
        assertEquals(List.of(1, 2), indices(topHalf), "the journal Q1/Q2 work and the CORE A/A* equivalent add up");
        assertEquals(1.0, threshold(topHalf, "LECT_UNIV"));
        assertEquals("FIRST_OR_CORRESPONDING", pd.kindField("Dir_CORE_A_echiv", "role"),
                "the CORE equivalent needs the candidate as first or corresponding author");
        assertEquals("ALL", pd.kindField("Dir_Q1_Q2", "role"), "the stored role; the chosen domain's rule replaces it at scoring (H138)");
        assertEquals("UEFISCDI 2026 — (fără domeniu ales)", pd.indicator("Dir_Q1_Q2").get("domain").get("$id").asText(),
                "the stored base domain qualifies nothing; the chosen competition domain replaces it");
        assertEquals("EXACT", pd.report().get("competitionFamily").asText());
        assertEquals("EXACT", mentor.report().get("competitionFamily").asText());
        assertEquals(List.of(2), toList(pd.indicator("Dir_CORE_A_echiv").get("competitionDomainCodes")), "the CORE route is Informatică's only");
        assertTrue(pd.indicator("Dir_lucrari_WoS").get("formula").asText().contains("NOT_FOUND"), "a work counts only in a journal of the chosen domain");
        assertEquals(2016, pd.indicator("Dir_Q1_Q2").get("yearRangeSpec").get("from").asInt());
    }

    @Test
    void pdMentorQ2DistinctJournalsRuleAppliesOnlyWithFewerThanThreeQ1Works() {
        // Anexa 2, mentor: "Dacă dintre articolele de referință 3 au fost publicate în reviste Q2, minimum 2
        // dintre acestea trebuie să fie din reviste diferite" — with 3 Q1 works the reference set needs no 3 Q2.
        assertEquals(5, mentor.report().get("criteria").size());
        JsonNode top50 = criterion(mentor, 0);
        JsonNode q1 = criterion(mentor, 1);
        JsonNode q2Distinct = criterion(mentor, 2);
        JsonNode articles = criterion(mentor, 3);
        JsonNode q1AtLeastThree = criterion(mentor, 4);
        assertEquals(5.0, threshold(top50, "PROF_UNIV"));
        assertEquals(List.of(1), indices(q1));
        assertEquals(2.0, threshold(q1, "PROF_UNIV"));
        assertEquals(List.of(1), indices(q1AtLeastThree), "the new criterion reads the same Q1 indicator");
        assertEquals(3.0, threshold(q1AtLeastThree, "PROF_UNIV"));
        assertEquals(3.0, threshold(articles, "PROF_UNIV"));
        assertEquals("DistinctForums", mentor.indicator("Mentor_Q2_reviste_distincte").get("selectorSpec").get("_class").asText()
                .substring("ro.uvt.pokedex.core.model.reporting.scoring.Selector$".length()));
        assertEquals(2.0, threshold(q2Distinct, "PROF_UNIV"));

        JsonNode verdict = mentor.report().get("perspectives").get(0);
        assertEquals("Mentor — verdict", verdict.get("name").asText());
        JsonNode all = verdict.get("composition").get("all");
        assertEquals(4, all.size());
        assertEquals(0, all.get(0).get("criterion").asInt());
        assertEquals(1, all.get(1).get("criterion").asInt());
        assertEquals(3, all.get(2).get("criterion").asInt());
        JsonNode any = all.get(3).get("any");
        assertEquals(4, any.get(0).get("criterion").asInt(), "Q1 ≥ 3 ...");
        assertEquals(2, any.get(1).get("criterion").asInt(), "... or 2 distinct Q2 journals");
        assertEquals(List.of(0, 1), pd.criteriaOf(0), "the director verdict is the two director criteria");
    }

    @Test
    void pdNothingContributesToATotalAndTheReportIsUefiscdi() {
        pd.report().get("criteria").forEach(c -> assertFalse(c.path("contributesToTotal").asBoolean(false), c.get("name").asText()));
        mentor.report().get("criteria").forEach(c -> assertFalse(c.path("contributesToTotal").asBoolean(false), c.get("name").asText()));
        assertEquals("UEFISCDI", pd.report().get("authority").asText());
        assertEquals("UEFISCDI", mentor.report().get("authority").asText());
    }

    @Test
    void thePhdAgeLimitAndThePhdAnchoredWindowsFollowThePackages() {
        // PD director: first PhD at most 8 years before the deadline; TE director 12; the mentor has no limit
        assertEquals(8, pd.report().get("phdLimitYears").asInt());
        assertEquals("2026-07-30T00:00:00Z", pd.report().get("competitionDeadline").get("$date").asText());
        assertEquals(12, te.report().get("phdLimitYears").asInt());
        assertNull(mentor.report().get("phdLimitYears"));
        // "după obținerea titlului de doctor" = the 2015–2026 window cut at the PhD year; the director's
        // "după admiterea la doctorat" stays the plain 2016–2026 approximation (the admission year is not held)
        assertTrue(mentor.indicator("Mentor_top50").get("yearRangeSpec").get("_class").asText().endsWith("AfterPhdAward"));
        assertTrue(te.indicator("Dir_Q1Q2").get("yearRangeSpec").get("_class").asText().endsWith("AfterPhdAward"));
        assertEquals(2015, te.indicator("Dir_CORE_A_echiv").get("yearRangeSpec").get("from").asInt());
        assertTrue(pd.indicator("Dir_Q1_Q2").get("yearRangeSpec").get("_class").asText().endsWith("Absolute"));
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
        assertTrue(te.domainCategories("Dir_Q1Q2").isEmpty(), "H138: the stored base domain is empty; the chosen domain's categories apply");
        assertEquals("EXACT", te.report().get("competitionFamily").asText());
        assertEquals(List.of(2), toList(te.indicator("Dir_CORE_A_echiv").get("competitionDomainCodes")));
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
        for (String title : List.of("Eligibilitate PD", "Eligibilitate Tinere Echipe", "Eligibilitate PD 2026")) {
            assertNull(SeedReportDefinition.find(title), title + " is superseded and must not be seeded");
        }
    }
}
