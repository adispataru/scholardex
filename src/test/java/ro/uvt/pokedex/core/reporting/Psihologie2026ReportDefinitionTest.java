package ro.uvt.pokedex.core.reporting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaVariableContract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ro.uvt.pokedex.core.reporting.SeedReportDefinition.fields;

/**
 * Pins the committed "FV Psihologie 2026" definition (seed/precious-config) to OM 3.019/2025, COMISIA 28,
 * domeniul Psihologie: the thresholds of the three ranks, which indicators feed which criterion, and what
 * every formula is worth. The definition is data, so nothing else in the suite would notice a typo in a
 * threshold or a role label that no longer matches its activity's options.
 */
class Psihologie2026ReportDefinitionTest {

    private static SeedReportDefinition psy;

    @BeforeAll
    static void load() {
        psy = new SeedReportDefinition("FV Psihologie 2026", "Psiho26_");
    }

    // ------------------------------------------------------------------ criteria and thresholds

    @Test
    void thresholdsAreTheOnesOfTheStandardForTheThreeRanks() {
        psy.assertThresholds("C1", null, 15.0, 15.0);
        psy.assertThresholds("C2", 12.0, 30.0, 30.0);
        psy.assertThresholds("C3", 60.0, 100.0, 90.0);
        psy.assertThresholds("C4", 10.0, 38.0, 38.0);
        psy.assertThresholds("C5", 9.0, 64.0, 64.0);
        psy.assertThresholds("C6", 24.0, 120.0, 103.0);
        psy.assertThresholds("C7", null, 27.0, 27.0);
        psy.assertThresholds("C8", 1.0, 30.0, 27.0);
        psy.assertThresholds("CTOTAL", 85.0, 250.0, 220.0);
        assertEquals(9, psy.report().get("criteria").size());
    }

    @Test
    void theStandardSetsNoThresholdsBelowConferentiar() {
        psy.report().get("criteria").forEach(criterion -> criterion.get("thresholds").forEach(threshold ->
                assertTrue(Set.of("CONF_UNIV", "PROF_UNIV", "HABIL").contains(threshold.get("position").asText()),
                        criterion.get("name").asText() + " carries a threshold for " + threshold.get("position"))));
    }

    @Test
    void criteriaSumTheIndicatorsTheStandardNames() {
        assertEquals(Set.of("I1A", "I1A_bonus"), psy.shortMembers("C1"));
        assertEquals(Set.of("I1A", "I1A_bonus", "I1B", "I1B_bonus"), psy.shortMembers("C2"));
        assertEquals(Set.of("I11"), psy.shortMembers("C4"));
        assertEquals(Set.of("I13"), psy.shortMembers("C5"));
        assertEquals(Set.of("I24"), psy.shortMembers("C7"));

        Set<String> a1 = psy.members("C3");
        Set<String> a2 = psy.members("C6");
        Set<String> a3 = psy.members("C8");
        assertEquals(12, a1.size());
        assertEquals(7, a2.size());
        assertEquals(11, a3.size());
        assertTrue(a1.containsAll(psy.members("C2")));

        // The three areas partition the report, and the total is exactly their union.
        Set<String> union = new HashSet<>(a1);
        union.addAll(a2);
        union.addAll(a3);
        assertEquals(a1.size() + a2.size() + a3.size(), union.size(), "an indicator sits in two areas");
        assertEquals(union, psy.members("CTOTAL"));
        assertEquals(new HashSet<>(psy.reportIndicatorNames()), union);
        assertEquals(psy.indicatorNames(), union, "a Psiho26 indicator is not part of the report");
    }

    @Test
    void perspectivesGroupTheCriteriaByArea() {
        assertEquals(4, psy.report().get("perspectives").size());
        assertEquals(List.of(0, 1, 2), psy.criteriaOf(0));
        assertEquals(List.of(3, 4, 5), psy.criteriaOf(1));
        assertEquals(List.of(6, 7), psy.criteriaOf(2));
        assertEquals(List.of(8), psy.criteriaOf(3));
    }

    // ------------------------------------------------------------------ indicator kinds

    @Test
    void principalAndCoauthorIndicatorsUseComplementaryRoles() {
        for (String principal : List.of("I1A", "I1B", "I2")) {
            assertEquals("FIRST_OR_CORRESPONDING", psy.kindField(principal, "role"), principal);
        }
        for (String coauthor : List.of("I5", "I6")) {
            assertEquals("NOT_FIRST_NOR_CORRESPONDING", psy.kindField(coauthor, "role"), coauthor);
        }
        // Books and chapters are scored "în calitate de autor / co-autor": no role split for Psychology.
        for (String book : List.of("I3A", "I3B", "I4A", "I4B")) {
            assertEquals("ALL", psy.kindField(book, "role"), book);
        }
    }

    @Test
    void theRulesThatChangedIn2026AreSwitchedOnExactlyWhereTheyApply() {
        // Every indicator that reads journals, publishers or Web of Science venues. The strict journal
        // indicators are in: without the flag they would skip the ESCI edition of the domain's categories.
        // H143: the coordinated book too — its publisher's category comes from the domain's lists
        assertEquals(Set.of("I1A", "I1B", "I5", "I2", "I6", "I3A", "I3B", "I4A", "I4B", "I13", "I17"),
                psy.flagged("psihologie2026"));
        assertTrue(psy.flagged("stiinteEducatiei2026").isEmpty());
        assertEquals("PSYCH_BDI_JOURNAL", psy.kindField("I2", "strategy"));
        assertEquals("PSYCH_BOOK", psy.kindField("I3A", "strategy"));
    }

    @Test
    void citationsAndHirschIndexReadWebOfScience() {
        assertEquals("WOS_INDEXED", psy.kindField("I11", "strategy"));
        assertEquals("CANDIDATE_ONLY", psy.kindField("I11", "policy"));
        assertEquals("WOS_VENUE", psy.kindField("I13", "source"));
        assertEquals("false", psy.kindField("I13", "excludeSelf"));
    }

    @Test
    void cappedIndicatorsCarryTheCapsOfTheStandard() {
        assertEquals(10, psy.indicator("I27_4").get("maxPoints").asInt());
        assertEquals(2, psy.indicator("I27_5").get("maxPoints").asInt());
        assertEquals(2, psy.indicatorNames().stream()
                .filter(name -> psy.indicator(name.substring("Psiho26_".length())).hasNonNull("maxPoints")).count());
    }

    @Test
    void publicationAndCitationFormulasOnlyUseVariablesTheEngineBinds() {
        for (String name : List.of("I1A", "I1B", "I2", "I3A", "I3B", "I4A", "I4B", "I5", "I6", "I11", "I13")) {
            assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(psy.asIndicator(name)), name);
        }
    }

    // ------------------------------------------------------------------ publication formulas

    @Test
    void principalAuthorArticlesSplitByPublicationFee() {
        // IF 2.0 >= p: 3 + 3 × IF = 9, counted at I1A for a non-fee journal and at I1B for a fee journal.
        assertEquals(9.0, psy.publication("I1A", 2.0, "Q3", false, 4, "", "ar"), 1e-9);
        assertEquals(0.0, psy.publication("I1B", 2.0, "Q3", false, 4, "", "ar"), 1e-9);
        assertEquals(0.0, psy.publication("I1A", 2.0, "Q3", true, 4, "", "ar"), 1e-9);
        assertEquals(9.0, psy.publication("I1B", 2.0, "Q3", true, 4, "", "ar"), 1e-9);
        // The Psychology exception: below p but above the category median (Q1/Q2) still counts.
        assertEquals(5.4, psy.publication("I1A", 0.8, "Q2", false, 1, "", "ar"), 1e-9);
        assertEquals(0.0, psy.publication("I1A", 0.8, "Q3", false, 1, "", "ar"), 1e-9);
        // The threshold itself qualifies ("mai mare sau egal cu p").
        assertEquals(6.0, psy.publication("I1A", 1.0, "Q4", false, 1, "", "ar"), 1e-9);
    }

    @Test
    void coauthorArticlesDivideOnlyTheImpactFactorPart() {
        // I5 = 3 + (3 × IF) / n — not (3 + 3 × IF) / n.
        assertEquals(3 + 6.0 / 4, psy.publication("I5", 2.0, "Q3", false, 4, "", "ar"), 1e-9);
        // I6 = (3 + IF) / n; the scorer hands 3 + IF over as S.
        assertEquals(3.5 / 5, psy.publication("I6", 3.5, null, false, 5, "WOS", "ar"), 1e-9);
        assertEquals(3.5, psy.publication("I2", 3.5, null, false, 5, "WOS", "ar"), 1e-9);
    }

    @Test
    void booksAndChaptersUseTheNewBasesAndNeverDivideByAuthors() {
        assertEquals(48.0, psy.publication("I3A", 3.0, null, false, 6, "A1", "bk"), 1e-9);
        assertEquals(16.0, psy.publication("I3A", 1.0, null, false, 6, "A2", "bk"), 1e-9);
        assertEquals(8.0, psy.publication("I3B", 0.5, null, false, 6, "B", "bk"), 1e-9);
        assertEquals(12.0, psy.publication("I4A", 3.0, null, false, 6, "A1", "ch"), 1e-9);
        assertEquals(4.0, psy.publication("I4A", 1.0, null, false, 6, "A2", "ch"), 1e-9);
        assertEquals(2.0, psy.publication("I4B", 0.5, null, false, 6, "B", "ch"), 1e-9);
        // Each item lands on exactly one of the four indicators.
        assertEquals(0.0, psy.publication("I3A", 0.5, null, false, 1, "B", "bk"), 1e-9);
        assertEquals(0.0, psy.publication("I3B", 1.0, null, false, 1, "A2", "bk"), 1e-9);
        assertEquals(0.0, psy.publication("I3A", 1.0, null, false, 1, "A2", "ch"), 1e-9);
        assertEquals(0.0, psy.publication("I4A", 1.0, null, false, 1, "A2", "bk"), 1e-9);
    }

    @Test
    void citationsAndHirschIndexFormulas() {
        assertEquals(0.5, psy.onScore("I11", 1.0), 1e-9);
        assertEquals(64.0, psy.onScore("I13", 8.0), 1e-9);
    }

    // ------------------------------------------------------------------ activity formulas

    @Test
    void preregistrationBonusFollowsTheJournalType() {
        assertEquals(1.0, psy.activity("I1A_bonus", fields("Tip_revista", "Fără taxă de publicare")), 1e-9);
        assertEquals(0.0, psy.activity("I1B_bonus", fields("Tip_revista", "Fără taxă de publicare")), 1e-9);
        assertEquals(1.0, psy.activity("I1B_bonus", fields("Tip_revista", "Cu taxă de publicare")), 1e-9);
        assertEquals(0.0, psy.activity("I1A_bonus", fields("Tip_revista", "Cu taxă de publicare")), 1e-9);
    }

    @Test
    void registeredMaterialsDivideFiveByTheAuthors() {
        assertEquals(2.5, psy.activity("I7", fields("N_autori", "2")), 1e-9);
        assertEquals(5.0, psy.activity("I7", fields()), 1e-9);
    }

    @Test
    void googleScholarCountsOnlyWhatWebOfScienceDoesNot() {
        // 0.05 × (GS − WoS)
        assertEquals(0.05 * 300, psy.activity("I12", fields("Citari_GS", "420", "Citari_WoS", "120")), 1e-9);
        assertEquals(0.05 * 420, psy.activity("I12", fields("Citari_GS", "420")), 1e-9);
        assertEquals(0.0, psy.activity("I12", fields("Citari_GS", "50", "Citari_WoS", "80")), 1e-9);
        assertEquals(0.0, psy.activity("I12", fields()), 1e-9);
        // (hGS × hGS) × 0.25
        assertEquals(25.0, psy.activity("I14", fields("h_GS", "10")), 1e-9);
        assertEquals(0.0, psy.activity("I14", fields()), 1e-9);
    }

    @Test
    void editorialRolesMultiplyRoleAndJournalWeight() {
        // m = 3 only for a Web of Science journal whose impact factor reaches p = 1,00.
        assertEquals(36.0, psy.activity("I15",
                fields("Rol", "Redactor-șef", "Indexare", "Web of Science", "IF_revista", "2.4")), 1e-9);
        assertEquals(24.0, psy.activity("I15",
                fields("Rol", "Editor asociat", "Indexare", "Web of Science", "IF_revista", "1")), 1e-9);
        assertEquals(12.0, psy.activity("I15",
                fields("Rol", "Membru", "Indexare", "Web of Science", "IF_revista", "1.0")), 1e-9);
        assertEquals(12.0, psy.activity("I15",
                fields("Rol", "Redactor-șef", "Indexare", "Web of Science", "IF_revista", "0.6")), 1e-9);
        assertEquals(12.0, psy.activity("I15",
                fields("Rol", "Redactor-șef", "Indexare", "Web of Science")), 1e-9);
        assertEquals(4.0, psy.activity("I15",
                fields("Rol", "Membru", "Indexare", "Altă BDI recunoscută", "IF_revista", "3")), 1e-9);
    }

    @Test
    void keynotesAndCoordinatedBooks() {
        assertEquals(6.0, psy.activity("I16", fields("Nivel", "Internațională")), 1e-9);
        assertEquals(2.0, psy.activity("I16", fields("Nivel", "Națională")), 1e-9);
        // H143: the category comes from the publisher typed — the Master Book List (A1), the 2026 list (A2, B)
        assertEquals(12.0, psy.activity("I17", fields("Editura", "Routledge", "N_coordonatori", "2")), 1e-9);
        assertEquals(8.0, psy.activity("I17", fields("Editura", "Polirom")), 1e-9);
        assertEquals(1.0, psy.activity("I17", fields("Editura", "Humanitas", "N_coordonatori", "4")), 1e-9);
        assertEquals(0.0, psy.activity("I17", fields("Editura", "Editura Proprie")), 1e-9,
                "a publisher on no list and without an approved route scores nothing");
    }

    @Test
    void grantLeadershipFollowsTheFourRolesOfTheStandard() {
        String budget = "100.000 – 199.999 EUR";
        assertEquals(81.0, psy.activity("I24",
                fields("Rol", "Director (proiect internațional)", "Interval_buget", budget)), 1e-9);
        assertEquals(27.0, psy.activity("I24",
                fields("Rol", "Director (proiect național)", "Interval_buget", budget)), 1e-9);
        assertEquals(27.0, psy.activity("I24",
                fields("Rol", "Coordonator local (proiect internațional)", "Interval_buget", budget)), 1e-9);
        assertEquals(8.991, psy.activity("I24",
                fields("Rol", "Coordonator local (proiect național)", "Interval_buget", budget)), 1e-9);
        assertEquals(0.0, psy.activity("I24", fields("Rol", "Membru", "Interval_buget", budget)), 1e-9);
        // A team member scores at I26.1 instead, so a grant never counts twice.
        assertEquals(3.0, psy.activity("I26_1", fields("Rol", "Membru", "Interval_buget", budget)), 1e-9);
        assertEquals(0.0, psy.activity("I26_1",
                fields("Rol", "Director (proiect național)", "Interval_buget", budget)), 1e-9);
    }

    @Test
    void aGrantCountsFromTwentyThousandEuroUpward() {
        String director = "Director (proiect național)";
        String lowest = "sub 50.000 EUR";
        // In the lowest bracket the exact amount decides.
        assertEquals(27.0, psy.activity("I24",
                fields("Rol", director, "Interval_buget", lowest, "Buget", "20000")), 1e-9);
        assertEquals(0.0, psy.activity("I24",
                fields("Rol", director, "Interval_buget", lowest, "Buget", "19999")), 1e-9);
        // The bracket alone says "under 50.000", which does not prove 20.000.
        assertEquals(0.0, psy.activity("I24", fields("Rol", director, "Interval_buget", lowest)), 1e-9);
        // Nothing declared about the budget: not counted.
        assertEquals(0.0, psy.activity("I24", fields("Rol", director)), 1e-9);
        assertEquals(0.0, psy.activity("I26_1", fields("Rol", "Membru")), 1e-9);
        // Any higher bracket is above the floor by itself.
        assertEquals(27.0, psy.activity("I24",
                fields("Rol", director, "Interval_buget", "50.000 – 99.999 EUR")), 1e-9);
    }

    @Test
    void institutionalGrantsFellowshipsAndChairs() {
        assertEquals(4.5, psy.activity("I25",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Director", "Buget", "60000")), 1e-9);
        assertEquals(4.5, psy.activity("I25",
                fields("Tip", "Cercetare aplicativă", "Rol", "Coordonator partener", "Buget", "20000")), 1e-9);
        assertEquals(0.0, psy.activity("I25",
                fields("Tip", "Cercetare aplicativă", "Rol", "Coordonator partener", "Buget", "15000")), 1e-9);
        assertEquals(0.0, psy.activity("I25", fields("Tip", "Dezvoltare instituțională", "Rol", "Director")), 1e-9);
        assertEquals(0.0, psy.activity("I25",
                fields("Tip", "Cercetare aplicativă", "Rol", "Membru", "Buget", "60000")), 1e-9);
        // Fellowships and chairs are not grants: no floor.
        assertEquals(4.5, psy.activity("I25", fields("Tip", "Fellowship")), 1e-9);
        assertEquals(9.0, psy.activity("I25", fields("Tip", "Chair")), 1e-9);
        assertEquals(1.5, psy.activity("I26_2",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Membru", "Buget", "60000")), 1e-9);
        assertEquals(0.0, psy.activity("I26_2",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Membru", "Buget", "5000")), 1e-9);
        assertEquals(0.0, psy.activity("I26_2",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Director", "Buget", "60000")), 1e-9);
        assertEquals(0.0, psy.activity("I26_2", fields("Tip", "Fellowship", "Rol", "Membru")), 1e-9);
    }

    @Test
    void coordinationActivitiesScoreFlatPoints() {
        assertEquals(2.0, psy.activity("I27_1", fields()), 1e-9);
        assertEquals(2.0, psy.activity("I27_2", fields()), 1e-9);
        assertEquals(2.0, psy.activity("I27_2_digital", fields()), 1e-9);
        assertEquals(0.5, psy.activity("I27_3", fields()), 1e-9);
        assertEquals(0.5, psy.activity("I27_4", fields("Rol", "Conducător științific")), 1e-9);
        assertEquals(1.0, psy.activity("I27_5", fields()), 1e-9);
        assertEquals(1.0, psy.activity("I27_6", fields()), 1e-9);
    }

    @Test
    void everyOptionComparedInAFormulaIsAnOptionOfItsActivity() {
        psy.assertComparedOptionsExist();
    }

    @Test
    void activityTypesAreNamedForTheWholeCommission() {
        // They are shared with the other domains of the annex: one entry scores in every report.
        for (String name : psy.indicatorNames()) {
            String shortName = name.substring("Psiho26_".length());
            if (psy.indicator(shortName).hasNonNull("activity")) {
                String activity = psy.activityName(shortName);
                assertTrue(!activity.contains("Psihologie 2026"), shortName + " is bound to " + activity);
            }
        }
    }

    // ------------------------------------------------------------------ descriptions

    @Test
    void everyIndicatorHasADescriptionAndNoDescriptionIsOrphaned() throws Exception {
        psy.assertDescribedBy("psihologie-2026.json");
    }
}
