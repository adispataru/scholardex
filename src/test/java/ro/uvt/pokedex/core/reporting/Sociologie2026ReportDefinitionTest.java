package ro.uvt.pokedex.core.reporting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaVariableContract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ro.uvt.pokedex.core.reporting.SeedReportDefinition.fields;

/**
 * Pins the committed "FV Sociologie și Asistență Socială 2026" definition to OM 3.019/2025, COMISIA 25, the
 * sociology group of the annex (Sociologie, Resurse Umane, Antropologie, Asistență Socială): the thresholds
 * of the three ranks, which indicators each criterion reads, the two criteria that are percentages, the
 * points of every indicator, and the readings the platform had to choose where the annex is silent.
 */
class Sociologie2026ReportDefinitionTest {

    private static final double M1 = 1.0;
    private static SeedReportDefinition soc;

    @BeforeAll
    static void load() {
        soc = new SeedReportDefinition("FV Sociologie și Asistență Socială 2026", "Soc26_");
    }

    // ------------------------------------------------------------------ criteria and thresholds

    @Test
    void thresholdsAreTheOnesOfTheStandardForTheThreeRanks() {
        soc.assertThresholds("C.1", 10.0, 15.0, 15.0);
        soc.assertThresholds("C.2", null, 30.0, 30.0);
        soc.assertThresholds("C.3", 50.0, 70.0, 70.0);
        soc.assertThresholds("C.4", 5.0, 10.0, 10.0);
        soc.assertThresholds("C.5", 1.0, 2.0, 2.0);
        soc.assertThresholds("C.6", 50.0, 100.0, 100.0);
        soc.assertThresholds("C.7", 10.0, 20.0, 20.0);
        soc.assertThresholds("C.8", 25.0, 50.0, 50.0);
        soc.assertThresholds("C.9", 130.0, 200.0, 200.0);
        soc.assertThresholds("C.10", null, 1.0, null);
        assertEquals(10, soc.report().get("criteria").size());
    }

    @Test
    void theStandardSetsNoThresholdsBelowConferentiar() {
        soc.report().get("criteria").forEach(criterion -> criterion.get("thresholds").forEach(threshold ->
                assertTrue(Set.of("CONF_UNIV", "PROF_UNIV", "HABIL").contains(threshold.get("position").asText()),
                        criterion.get("name").asText() + " carries a threshold for " + threshold.get("position"))));
    }

    @Test
    void theTwoShareCriteriaArePercentagesOfTheFirstIndicator() {
        assertEquals(Set.of("I1_N"), soc.shortMembers("C.2"));
        assertEquals(Set.of("I1"), soc.shareOf("C.2"));
        assertEquals(Set.of("I1_NC"), soc.shortMembers("C.3"));
        assertEquals(Set.of("I1"), soc.shareOf("C.3"));
        for (String sum : List.of("C.1", "C.4", "C.5", "C.6", "C.7", "C.8", "C.9", "C.10")) {
            assertTrue(soc.shareOf(sum).isEmpty(), sum + " is a sum, not a share");
        }
    }

    @Test
    void criteriaReadTheIndicatorsTheStandardNames() {
        assertEquals(Set.of("I1"), soc.shortMembers("C.1"));
        assertEquals(Set.of("C4_articole", "C4_articole_decl", "C4_capitole", "C4_capitole_decl"),
                soc.shortMembers("C.4"));
        assertEquals(Set.of("I9", "I9_decl"), soc.shortMembers("C.7"));
        assertEquals(Set.of("C8", "C8_decl"), soc.shortMembers("C.8"));
        assertEquals(Set.of("C10"), soc.shortMembers("C.10"));

        Set<String> publications = soc.shortMembers("C.6");
        assertEquals(Set.of("I1", "I2", "I2_decl", "I3", "I3_decl", "I4", "I4_decl", "I5", "I6", "I6_decl", "I7",
                "I8", "I8_decl", "I8_trad"), publications);

        Set<String> total = soc.shortMembers("C.9");
        assertEquals(29, total.size());
        assertTrue(total.containsAll(publications));
        assertTrue(total.containsAll(soc.shortMembers("C.7")));
        for (String activity : List.of("I10", "I11", "I12", "I13", "I14", "I15", "I16", "I17", "I18", "I19_1",
                "I19_2", "I19_3", "I19_4")) {
            assertTrue(total.contains(activity), activity);
        }
    }

    @Test
    void countsAndSharesNeverAddPointsToTheTotal() {
        Set<String> helpers = Set.of("I1_N", "I1_NC", "C4_articole", "C4_articole_decl", "C4_capitole",
                "C4_capitole_decl", "C5_A1", "C5_A2", "C5_A1_decl", "C5_A2_decl", "C8", "C8_decl", "C10");
        Set<String> total = soc.shortMembers("C.9");
        helpers.forEach(helper -> assertFalse(total.contains(helper), helper + " is summed into the total"));

        Set<String> all = new HashSet<>(total);
        all.addAll(helpers);
        Set<String> inTheReport = new HashSet<>();
        soc.reportIndicatorNames().forEach(name -> inTheReport.add(name.substring("Soc26_".length())));
        assertEquals(inTheReport, all);
        assertEquals(soc.indicatorNames().size(), all.size(), "a Soc26 indicator is not part of the report");
    }

    @Test
    void oneBookAtAnInternationalPublisherIsWorthTwoAtAListedOne() {
        // C.5 — conferențiar: one book, A1 or A2. Professor and habilitation: one A1 book OR two A2 books.
        assertEquals(Set.of("C5_A1", "C5_A1_decl", "C5_A2", "C5_A2_decl"), soc.shortMembers("C.5"));
        assertEquals(2.0, soc.weight("C.5", "C5_A1"));
        assertEquals(2.0, soc.weight("C.5", "C5_A1_decl"));
        assertEquals(1.0, soc.weight("C.5", "C5_A2"));
        assertEquals(1.0, soc.weight("C.5", "C5_A2_decl"));
    }

    @Test
    void perspectivesGroupTheCriteria() {
        assertEquals(5, soc.report().get("perspectives").size());
        assertEquals(List.of(0, 1, 2), soc.criteriaOf(0));
        assertEquals(List.of(3, 4, 5), soc.criteriaOf(1));
        assertEquals(List.of(6, 7), soc.criteriaOf(2));
        assertEquals(List.of(8), soc.criteriaOf(3));
        assertEquals(List.of(9), soc.criteriaOf(4));
    }

    // ------------------------------------------------------------------ indicator kinds and domains

    @Test
    void theRulesOfTheCommissionAreSwitchedOnExactlyWhereTheyApply() {
        assertEquals(Set.of("I1", "I1_N", "I1_NC", "I2", "I3", "I4", "I6", "I8", "I9", "C4_articole", "C4_capitole",
                "C5_A1", "C5_A2", "C8",
                // H143: the declared books, chapters, coordinated books, translations and collections take their
                // publisher's category from the lists of the commission
                "I3_decl", "I4_decl", "I5", "I6_decl", "I8_trad", "I12", "C4_capitole_decl", "C5_A1_decl",
                "C5_A2_decl"), soc.flagged("sociologie2026"));
        assertTrue(soc.flagged("psihologie2026").isEmpty());
        assertTrue(soc.flagged("stiinteEducatiei2026").isEmpty());
    }

    @Test
    void everyAuthorOfAPublicationIsCounted() {
        // The annex divides by n and has no principal/co-author split.
        for (String name : List.of("I1", "I1_N", "I1_NC", "I2", "I3", "I4", "I6", "I8", "C4_articole",
                "C4_capitole", "C5_A1", "C5_A2")) {
            assertEquals("ALL", soc.kindField(name, "role"), name);
        }
    }

    @Test
    void indicatorsUseTheScorersOfTheirKind() {
        for (String name : List.of("I1", "I1_N", "I1_NC")) {
            assertEquals("IMPACT_FACTOR", soc.kindField(name, "strategy"), name);
        }
        for (String name : List.of("I2", "C4_articole")) {
            assertEquals("SOC_INDEXED_JOURNAL", soc.kindField(name, "strategy"), name);
        }
        for (String name : List.of("I3", "I4", "I6", "C4_capitole", "C5_A1", "C5_A2")) {
            assertEquals("PSYCH_BOOK", soc.kindField(name, "strategy"), name);
        }
        assertEquals("INDEXED_PROCEEDINGS", soc.kindField("I8", "strategy"));
        for (String name : List.of("I9", "C8")) {
            assertEquals("CITING_IMPACT_FACTOR", soc.kindField(name, "strategy"), name);
            assertEquals("CANDIDATE_ONLY", soc.kindField(name, "policy"), name + ": self-citations do not count");
        }
    }

    @Test
    void theFirstIndicatorCountsEveryCategoryAndTheSharesOnlyTheirOwn() {
        assertEquals(Set.of("*"), soc.domainCategories("I1"));

        Set<String> core = soc.domainCategories("I1_N");
        Set<String> coreAndRelated = soc.domainCategories("I1_NC");
        assertTrue(coreAndRelated.containsAll(core), "the core is part of core-and-related");
        for (String key : List.of("SOCIOLOGY - SSCI", "SOCIOLOGY - ESCI", "SOCIAL WORK - SSCI", "SOCIAL WORK - ESCI",
                "SOCIAL ISSUES - SSCI", "DEMOGRAPHY - SSCI", "ANTHROPOLOGY - SSCI", "CULTURAL STUDIES - AHCI",
                "CRIMINOLOGY & PENOLOGY - SSCI", "FAMILY STUDIES - SSCI", "GERONTOLOGY - SSCI",
                "INDUSTRIAL RELATIONS & LABOR - SSCI", "MANAGEMENT - SSCI", "MULTIDISCIPLINARY SCIENCES - SCIE",
                "PSYCHOLOGY, SOCIAL - SSCI", "STATISTICS & PROBABILITY - SCIE", "SUBSTANCE ABUSE - SSCI",
                "DEVELOPMENT STUDIES - SSCI", "PLANNING & DEVELOPMENT - SSCI")) {
            assertTrue(core.contains(key), key + " is in the core");
        }
        for (String key : List.of("ECONOMICS - SSCI", "RELIGION - AHCI", "PHILOSOPHY - AHCI", "HISTORY - AHCI",
                "POLITICAL SCIENCE - SSCI", "EDUCATION & EDUCATIONAL RESEARCH - SSCI", "WOMENS STUDIES - SSCI",
                "WOMEN'S STUDIES - SSCI", "PUBLIC ADMINISTRATION - SSCI", "COMMUNICATION - SSCI")) {
            assertTrue(coreAndRelated.contains(key), key + " is a related category");
            assertFalse(core.contains(key), key + " is not in the core");
        }
        // Not on either list of the sociology group.
        for (String key : List.of("LAW - SSCI", "GERIATRICS & GERONTOLOGY - SCIE", "LINGUISTICS - SSCI",
                "PSYCHOLOGY, CLINICAL - SSCI")) {
            assertFalse(coreAndRelated.contains(key), key);
        }
    }

    @Test
    void publicationAndCitationFormulasOnlyUseVariablesTheEngineBinds() {
        for (String name : List.of("I1", "I1_N", "I1_NC", "I2", "I3", "I4", "I6", "I8", "I9", "C4_articole",
                "C4_capitole", "C5_A1", "C5_A2", "C8")) {
            assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(soc.asIndicator(name)), name);
        }
    }

    // ------------------------------------------------------------------ publications

    @Test
    void anArticleWithImpactFactor() {
        // (2 + 4·f) · 2 / n
        assertEquals(16.0, soc.publication("I1", 1.5, 1, "", "ar", M1), 1e-9);
        assertEquals(8.0, soc.publication("I1", 1.5, 2, "", "ar", M1), 1e-9);
        assertEquals((2 + 4 * 0.3) * 2 / 4, soc.publication("I1", 0.3, 4, "", "ar", M1), 1e-9);
        assertEquals(8.0, soc.publication("I1_N", 1.5, 2, "", "ar", M1), 1e-9);
        assertEquals(8.0, soc.publication("I1_NC", 1.5, 2, "", "ar", M1), 1e-9);
        // The coefficient m does not apply to I.1.
        assertEquals(8.0, soc.publication("I1", 1.5, 2, "", "ar", 2.0), 1e-9);
    }

    @Test
    void anArticleWithoutImpactFactor() {
        // The scorer hands in the points: 4 for a Scopus journal, 2 for three recognised databases.
        assertEquals(2.0, soc.publication("I2", 4.0, 2, "SCOPUS", "ar", M1), 1e-9);
        assertEquals(4.0, soc.publication("I2", 4.0, 2, "SCOPUS", "ar", 2.0), 1e-9);
        assertEquals(1.5, soc.publication("I2", 2.0, 2, "BDI3", "ar", 1.5), 1e-9);
        assertEquals(1.0, soc.publication("C4_articole", 4.0, 5, "SCOPUS", "ar", M1), 1e-9);
    }

    @Test
    void booksSplitAtThreeAuthors() {
        assertEquals(10.0, soc.publication("I3", 1.0, 1, "A2", "bk", M1), 1e-9);
        assertEquals(10.0 / 3, soc.publication("I3", 1.0, 3, "A1", "bk", M1), 1e-9);
        assertEquals(0.0, soc.publication("I3", 1.0, 4, "A1", "bk", M1), 1e-9);
        assertEquals(0.0, soc.publication("I4", 1.0, 3, "A1", "bk", M1), 1e-9);
        assertEquals(6.0 / 4, soc.publication("I4", 1.0, 4, "A2", "bk", M1), 1e-9);
        assertEquals(2 * 6.0 / 4, soc.publication("I4", 1.0, 4, "A2", "bk", 2.0), 1e-9);
        // A chapter is neither.
        assertEquals(0.0, soc.publication("I3", 1.0, 1, "A1", "ch", M1), 1e-9);
        assertEquals(0.0, soc.publication("I4", 1.0, 5, "A1", "ch", M1), 1e-9);
    }

    @Test
    void chaptersAreWorthDoubleAtAnInternationalPublisher() {
        assertEquals(6.0 / 2, soc.publication("I6", 1.0, 2, "A1", "ch", M1), 1e-9);
        assertEquals(3.0 / 2, soc.publication("I6", 1.0, 2, "A2", "ch", M1), 1e-9);
        assertEquals(1.5 * 3.0 / 2, soc.publication("I6", 1.0, 2, "A2", "ch", 1.5), 1e-9);
        assertEquals(0.0, soc.publication("I6", 1.0, 2, "A1", "bk", M1), 1e-9);
    }

    @Test
    void proceedingsPapers() {
        assertEquals(1.0, soc.publication("I8", 1.0, 1, "BDI", "cp", M1), 1e-9);
        assertEquals(0.5, soc.publication("I8", 1.0, 4, "BDI", "cp", 2.0), 1e-9);
    }

    @Test
    void booksAndChaptersAreCountedForTheirCriteria() {
        assertEquals(1.0, soc.publication("C4_capitole", 1.0, 7, "A2", "ch", M1), 1e-9);
        assertEquals(0.0, soc.publication("C4_capitole", 1.0, 1, "A2", "bk", M1), 1e-9);

        assertEquals(1.0, soc.publication("C5_A1", 1.0, 3, "A1", "bk", M1), 1e-9);
        assertEquals(0.0, soc.publication("C5_A1", 1.0, 4, "A1", "bk", M1), 1e-9, "more than three authors");
        assertEquals(0.0, soc.publication("C5_A1", 1.0, 1, "A2", "bk", M1), 1e-9);
        assertEquals(0.0, soc.publication("C5_A1", 1.0, 1, "A1", "ch", M1), 1e-9);
        assertEquals(1.0, soc.publication("C5_A2", 1.0, 2, "A2", "bk", M1), 1e-9);
        assertEquals(0.0, soc.publication("C5_A2", 1.0, 2, "A1", "bk", M1), 1e-9);
    }

    @Test
    void aCitationIsPricedByTheCitingJournal() {
        // The scorer hands in 0,2 + 4·f; the formula doubles it and divides by the authors of the cited work.
        assertEquals((0.2 + 4 * 1.5) * 2 / 2, soc.publication("I9", 0.2 + 4 * 1.5, 2, "IF", "ar", M1), 1e-9);
        assertEquals(0.4, soc.publication("I9", 0.2, 1, "NO_IF", "ch", M1), 1e-9);
        assertEquals(1.0, soc.publication("C8", 0.2, 6, "NO_IF", "ch", M1), 1e-9);
    }

    // ------------------------------------------------------------------ what the candidate declares

    @Test
    void declaredArticlesBooksAndChapters() {
        assertEquals(2.0, soc.activity("I2_decl", fields("Coeficient_m", "m = 2", "N_autori", "2")), 1e-9);
        assertEquals(2.0, soc.activity("I2_decl", fields()), 1e-9);
        assertEquals(1.0, soc.activity("C4_articole_decl", fields()), 1e-9);

        // H143: the publisher's category comes from the lists — the annex's A2 list (Polirom), the WoS Master Book List
        // for A1 (Routledge) — and the holdings in six WorldCat libraries count once a head approves the request.
        assertEquals(15.0, soc.activity("I3_decl", fields("Tip", "Carte", "Editura", "Polirom",
                "N_autori", "1", "Coeficient_m", "m = 1.5")), 1e-9);
        assertEquals(0.0, soc.activity("I3_decl", fields("Tip", "Carte", "Editura", "Polirom",
                "N_autori", "4")), 1e-9);
        assertEquals(6.0 / 4, soc.activityWithDecision("I4_decl", fields("Tip", "Carte", "Editura", "Editura Proprie",
                "Incadrare_solicitata", WORLDCAT, "N_autori", "4"), PublisherClaim.Status.APPROVED), 1e-9);
        assertEquals(0.0, soc.activityWithDecision("I4_decl", fields("Tip", "Carte", "Editura", "Editura Proprie",
                "Incadrare_solicitata", WORLDCAT, "N_autori", "4"), PublisherClaim.Status.PENDING), 1e-9,
                "a request counts only once a head approves it");
        assertEquals(0.0, soc.activity("I4_decl", fields("Tip", "Carte", "Editura", "Routledge",
                "N_autori", "2")), 1e-9);
        // A book whose publisher is on no list and in no six libraries is not counted.
        assertEquals(0.0, soc.activity("I3_decl", fields("Tip", "Carte", "Editura", "Editura Proprie", "N_autori", "1")), 1e-9);
        assertEquals(0.0, soc.activity("I3_decl", fields("Tip", "Carte", "N_autori", "1")), 1e-9);

        assertEquals(6.0, soc.activity("I6_decl", fields("Tip", "Capitol în volum colectiv",
                "Editura", "Routledge")), 1e-9);
        assertEquals(3.0, soc.activityWithDecision("I6_decl", fields("Tip", "Capitol în volum colectiv",
                "Editura", "Editura Proprie", "Incadrare_solicitata", WORLDCAT), PublisherClaim.Status.APPROVED), 1e-9);
        assertEquals(6.0, soc.activityWithDecision("I6_decl", fields("Tip", "Capitol în volum colectiv",
                "Editura", "Editura Proprie", "Incadrare_solicitata", A1), PublisherClaim.Status.APPROVED), 1e-9,
                "an international house off the Master Book List, approved as A1");
        assertEquals(0.0, soc.activity("I6_decl", fields("Tip", "Carte", "Editura", "Routledge")), 1e-9);
        assertEquals(1.0, soc.activity("C4_capitole_decl", fields("Tip", "Capitol în volum colectiv",
                "Editura", "Polirom")), 1e-9);
    }

    private static final String A1 = "Editură de prestigiu internațional (Lista A1)";
    private static final String WORLDCAT = "Minimum 6 biblioteci în WorldCat (asimilat Listei A2)";

    @Test
    void declaredBooksAreCountedOnce() {
        var international = fields("Tip", "Carte", "Editura", "Routledge", "N_autori", "2");
        var listed = fields("Tip", "Carte", "Editura", "Editura Polirom, Iași", "N_autori", "3");
        var libraries = fields("Tip", "Carte", "Editura", "Editura Proprie", "Incadrare_solicitata", WORLDCAT);
        assertEquals(1.0, soc.activity("C5_A1_decl", international), 1e-9);
        assertEquals(0.0, soc.activity("C5_A2_decl", international), 1e-9);
        assertEquals(0.0, soc.activity("C5_A1_decl", listed), 1e-9);
        assertEquals(1.0, soc.activity("C5_A2_decl", listed), 1e-9);
        assertEquals(1.0, soc.activityWithDecision("C5_A2_decl", libraries, PublisherClaim.Status.APPROVED), 1e-9);
        assertEquals(0.0, soc.activityWithDecision("C5_A2_decl", libraries, PublisherClaim.Status.REJECTED), 1e-9);
        assertEquals(0.0, soc.activity("C5_A2_decl",
                fields("Tip", "Carte", "Editura", "Polirom", "N_autori", "4")), 1e-9);
        assertEquals(0.0, soc.activity("C5_A2_decl",
                fields("Tip", "Capitol în volum colectiv", "Editura", "Polirom")), 1e-9);
    }

    @Test
    void coordinatedBooksReviewsProceedingsAndTranslations() {
        assertEquals(6.0, soc.activity("I5", fields("Editura", "Humanitas", "N_coordonatori", "2", "Coeficient_m", "m = 2")), 1e-9);
        assertEquals(6.0, soc.activity("I5", fields("Editura", "Polirom")), 1e-9);
        assertEquals(0.0, soc.activity("I5", fields()), 1e-9, "definition [4]: only books at a listed publisher count");

        assertEquals(3.0, soc.activity("I7", fields("Tip", "Recenzie în revistă ISI/WoS")), 1e-9);
        assertEquals(1.0, soc.activity("I7",
                fields("Tip", "Recenzie în revistă indexată în cel puțin 3 baze de date")), 1e-9);
        assertEquals(0.75, soc.activity("I7", fields("Tip",
                "Termen de minimum o pagină în enciclopedie sau dicționar", "N_autori", "2",
                "Coeficient_m", "m = 1.5")), 1e-9);

        assertEquals(1.0, soc.activity("I8_decl", fields()), 1e-9);
        assertEquals(0.5, soc.activity("I8_decl", fields("N_autori", "4", "Coeficient_m", "m = 2")), 1e-9);
        assertEquals(2.0, soc.activity("I8_trad", fields("Tip", "Fără aparat critic", "Editura", "Polirom")), 1e-9);
        assertEquals(4.0, soc.activity("I8_trad", fields("Tip", "Cu aparat critic", "Editura", "Polirom")), 1e-9);
        assertEquals(0.0, soc.activity("I8_trad", fields("Tip", "Cu aparat critic", "Editura", "Editura Proprie")), 1e-9,
                "a translation counts at a publisher of the A2 list");
    }

    @Test
    void declaredCitations() {
        assertEquals((0.2 + 4 * 1.0) * 2 / 2,
                soc.activity("I9_decl", fields("IF_sursa", "1.0", "N_autori", "2")), 1e-9);
        assertEquals(0.4, soc.activity("I9_decl", fields()), 1e-9);
        assertEquals(1.0, soc.activity("C8_decl", fields("IF_sursa", "3")), 1e-9);
    }

    @Test
    void editorialRolesAreCountedPerYearAndGuestIssuesPerIssue() {
        String wos = "ISI/WoS sau SCOPUS";
        String other = "Trei baze de date recunoscute, altele decât ISI/WoS și SCOPUS";
        String editor = "Editor, redactor-șef sau redactor delegat";
        String member = "Membru în comitetul de redacție sau științific";
        String guest = "Editor invitat al unui număr special";

        assertEquals(20.0, soc.activity("I10", fields("Indexare", wos, "Rol", editor,
                "An_inceput", "2020", "An_sfarsit", "2023")), 1e-9);
        assertEquals(12.0, soc.activity("I10", fields("Indexare", wos, "Rol", member,
                "An_inceput", "2020", "An_sfarsit", "2023")), 1e-9);
        assertEquals(3.0, soc.activity("I10", fields("Indexare", wos, "Rol", member)), 1e-9);
        assertEquals(4.0, soc.activity("I10", fields("Indexare", wos, "Rol", guest,
                "N_numere_speciale", "2")), 1e-9);
        assertEquals(2.0, soc.activity("I10", fields("Indexare", wos, "Rol", guest)), 1e-9);
        assertEquals(0.0, soc.activity("I10", fields("Indexare", other, "Rol", editor)), 1e-9);

        assertEquals(8.0, soc.activity("I11", fields("Indexare", other, "Rol", editor,
                "An_inceput", "2020", "An_sfarsit", "2023")), 1e-9);
        assertEquals(4.0, soc.activity("I11", fields("Indexare", other, "Rol", member,
                "An_inceput", "2020", "An_sfarsit", "2023")), 1e-9);
        assertEquals(3.0, soc.activity("I11", fields("Indexare", other, "Rol", guest,
                "N_numere_speciale", "3")), 1e-9);
        assertEquals(0.0, soc.activity("I11", fields("Indexare", wos, "Rol", editor)), 1e-9);
        // One declared entry feeds both indicators; it scores in exactly one of them.
        assertEquals(soc.activityName("I10"), soc.activityName("I11"));
    }

    @Test
    void bookCollections() {
        assertEquals(8.0, soc.activity("I12", fields("Editura", "Routledge", "Rol", "Coordonator")), 1e-9);
        assertEquals(4.0, soc.activity("I12",
                fields("Editura", "Routledge", "Rol", "Membru în comitetul științific")), 1e-9);
        assertEquals(4.0, soc.activity("I12", fields("Editura", "Polirom", "Rol", "Coordonator")), 1e-9);
        assertEquals(2.0, soc.activity("I12",
                fields("Editura", "Polirom", "Rol", "Membru în comitetul științific")), 1e-9);
        assertEquals(0.0, soc.activity("I12", fields("Editura", "Editura Proprie", "Rol", "Coordonator")), 1e-9);
    }

    @Test
    void grantsAreScoredByTheirValue() {
        assertEquals("Grant Cercetare", soc.activityName("I13"));
        String director = "Director (proiect național)";
        assertEquals(5.0, soc.activity("I13", fields("Rol", director, "Buget", "8000")), 1e-9);
        assertEquals(5.0, soc.activity("I13", fields("Rol", director, "Buget", "10000")), 1e-9);
        assertEquals(10.0, soc.activity("I13", fields("Rol", director, "Buget", "15000")), 1e-9);
        assertEquals(15.0, soc.activity("I13", fields("Rol", director, "Buget", "30000")), 1e-9);
        assertEquals(20.0, soc.activity("I13", fields("Rol", director, "Buget", "30001")), 1e-9);
        assertEquals(20.0, soc.activity("I13",
                fields("Rol", "Coordonator local (proiect internațional)", "Buget", "250000")), 1e-9);

        assertEquals(2.0, soc.activity("I13", fields("Rol", "Membru", "Buget", "8000")), 1e-9);
        assertEquals(3.0, soc.activity("I13", fields("Rol", "Membru", "Buget", "15000")), 1e-9);
        assertEquals(4.0, soc.activity("I13", fields("Rol", "Membru", "Buget", "25000")), 1e-9);
    }

    @Test
    void theAnnexGivesNoValueForAMemberOfTheLargestProjects() {
        // "Valoare proiect >30.000,01 Euro coordonare: 20 puncte;" and nothing for a member. The value of the
        // tier below is used, so a larger project never scores less than a smaller one.
        assertEquals(4.0, soc.activity("I13", fields("Rol", "Membru", "Buget", "120000")), 1e-9);
    }

    @Test
    void aGrantWithoutAStatedBudgetIsScoredAtTheFirstTier() {
        assertEquals(5.0, soc.activity("I13", fields("Rol", "Director (proiect național)")), 1e-9);
        assertEquals(2.0, soc.activity("I13", fields("Rol", "Membru")), 1e-9);
        assertEquals(2.0, soc.activity("I13", fields()), 1e-9, "no role stated: counted as a member");
        // The declared interval is the platform's bracket scale; its lower bound is what is known for sure.
        assertEquals(5.0, soc.activity("I13",
                fields("Rol", "Director (proiect național)", "Interval_buget", "sub 50.000 EUR")), 1e-9);
        assertEquals(20.0, soc.activity("I13",
                fields("Rol", "Director (proiect național)", "Interval_buget", "50.000 – 99.999 EUR")), 1e-9);
    }

    @Test
    void visitsConferencesStudiesAndProjects() {
        assertEquals(10.0, soc.activity("I14",
                fields("Tip", "Profesor visiting, universitate din primele 1000")), 1e-9);
        assertEquals(5.0, soc.activity("I14",
                fields("Tip", "Cercetător invitat cel puțin o lună, universitate din primele 1000")), 1e-9);
        assertEquals(0.5, soc.activity("I14", fields("Tip", "Stagiu Erasmus")), 1e-9);
        assertEquals(0.0, soc.activity("I14", fields()), 1e-9);

        assertEquals(1.0, soc.activity("I15", fields("Loc", "În străinătate")), 1e-9);
        assertEquals(0.0, soc.activity("I15", fields("Loc", "În țară")), 1e-9);
        assertEquals(0.5, soc.activity("I16", fields("Loc", "În țară")), 1e-9);
        assertEquals(0.0, soc.activity("I16", fields("Loc", "În străinătate")), 1e-9);
        assertEquals(soc.activityName("I15"), soc.activityName("I16"));

        assertEquals(2.0, soc.activity("I17", fields()), 1e-9);
        assertEquals(4.0, soc.activity("I17", fields("Coeficient_m", "m = 2")), 1e-9);
        assertEquals(1.0, soc.activity("I18", fields()), 1e-9);
    }

    @Test
    void teachingAndTraining() {
        assertEquals(1.0, soc.activity("I19_1", fields("Tip", "BIP (Blended Intensive Program)")), 1e-9);
        assertEquals(1.0, soc.activity("I19_1", fields("Tip", "Școală de vară")), 1e-9);
        assertEquals(4.0, soc.activity("I19_2",
                fields("Tip", "Suport de curs sau de seminar, minimum 20 de pagini")), 1e-9);
        assertEquals(3.0, soc.activity("I19_2", fields("Tip",
                "Suport de curs sau de seminar, minimum 20 de pagini", "N_autori", "2",
                "Coeficient_m", "m = 1.5")), 1e-9);
        assertEquals(1.0, soc.activity("I19_2", fields("Tip", "Capitol în suport de curs sau de seminar")), 1e-9);
        assertEquals(2.0, soc.activity("I19_3", fields()), 1e-9);
        assertEquals(2.0, soc.activity("I19_4", fields()), 1e-9);
    }

    @Test
    void theConditionAfterHabilitationIsMetOnce() {
        assertEquals(1.0, soc.activity("C10", fields("Tip",
                "Coordonarea a cel puțin unui doctorand, cu afiliere la o școală doctorală")), 1e-9);
        assertEquals(1, soc.indicator("C10").get("maxPoints").asInt());
    }

    @Test
    void everyOptionComparedInAFormulaIsAnOptionOfItsActivity() {
        soc.assertComparedOptionsExist();
    }

    // ------------------------------------------------------------------ descriptions

    @Test
    void everyIndicatorHasADescriptionAndNoDescriptionIsOrphaned() throws Exception {
        soc.assertDescribedBy("sociologie-2026.json");
    }
}
