package ro.uvt.pokedex.core.reporting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * Pins the committed "FV Științe ale Educației 2026" definition to OM 3.019/2025, COMISIA 28, domeniul
 * Științe ale Educației. The annex is the one Psychology uses; what this test guards is everything that
 * differs — the thresholds and the shape of the criteria, p = 0,10 without the above-median exception, the
 * two coefficients that change, the indicators only this domain has — and that the activity types are the
 * very ones the Psychology report reads, so one declared entry scores in both.
 */
class StiinteEducatiei2026ReportDefinitionTest {

    private static SeedReportDefinition edu;
    private static SeedReportDefinition psy;

    @BeforeAll
    static void load() {
        edu = new SeedReportDefinition("FV Științe ale Educației 2026", "Edu26_");
        psy = new SeedReportDefinition("FV Psihologie 2026", "Psiho26_");
    }

    // ------------------------------------------------------------------ criteria and thresholds

    @Test
    void thresholdsAreTheOnesOfTheStandardForTheThreeRanks() {
        edu.assertThresholds("C1", null, 3.0, 3.0);
        edu.assertThresholds("C2", 3.0, 6.0, 6.0);
        edu.assertThresholds("C3", 20.0, 30.0, 30.0);
        edu.assertThresholds("C4", 60.0, 80.0, 75.0);
        edu.assertThresholds("C5", 2.0, 4.0, 4.0);
        edu.assertThresholds("C6", 16.0, 49.0, 49.0);
        edu.assertThresholds("C7", 40.0, 100.0, 90.0);
        edu.assertThresholds("C8", null, 12.0, 12.0);
        edu.assertThresholds("C9", 5.0, 20.0, 15.0);
        edu.assertThresholds("CTOTAL", 105.0, 200.0, 180.0);
        assertEquals(10, edu.report().get("criteria").size());
    }

    @Test
    void theStandardSetsNoThresholdsBelowConferentiar() {
        edu.report().get("criteria").forEach(criterion -> criterion.get("thresholds").forEach(threshold ->
                assertTrue(Set.of("CONF_UNIV", "PROF_UNIV", "HABIL").contains(threshold.get("position").asText()),
                        criterion.get("name").asText() + " carries a threshold for " + threshold.get("position"))));
    }

    @Test
    void criteriaSumTheIndicatorsTheStandardNames() {
        assertEquals(Set.of("I1A", "I1A_bonus"), edu.shortMembers("C1"));
        assertEquals(Set.of("I1A", "I1A_bonus", "I1B", "I1B_bonus"), edu.shortMembers("C2"));
        // Books and chapters at A1/A2 publishers are a criterion of their own here; tier B is not in it.
        assertEquals(Set.of("I3A", "I4A"), edu.shortMembers("C3"));
        assertEquals(Set.of("I11"), edu.shortMembers("C5"));
        // The mandatory Hirsch index is the Google Scholar one, not the Web of Science one.
        assertEquals(Set.of("I14"), edu.shortMembers("C6"));
        assertEquals(Set.of("I24", "I25"), edu.shortMembers("C8"));

        Set<String> a1 = edu.members("C4");
        Set<String> a2 = edu.members("C7");
        Set<String> a3 = edu.members("C9");
        assertEquals(15, a1.size());
        assertEquals(13, a2.size());
        assertEquals(14, a3.size());
        assertTrue(a1.containsAll(edu.members("C2")));
        assertTrue(a1.containsAll(edu.members("C3")));
        assertTrue(a2.containsAll(edu.members("C6")));
        assertTrue(a3.containsAll(edu.members("C8")));

        Set<String> union = new HashSet<>(a1);
        union.addAll(a2);
        union.addAll(a3);
        assertEquals(a1.size() + a2.size() + a3.size(), union.size(), "an indicator sits in two areas");
        assertEquals(union, edu.members("CTOTAL"));
        assertEquals(new HashSet<>(edu.reportIndicatorNames()), union);
        assertEquals(edu.indicatorNames(), union, "an Edu26 indicator is not part of the report");
    }

    @Test
    void perspectivesGroupTheCriteriaByArea() {
        assertEquals(4, edu.report().get("perspectives").size());
        assertEquals(List.of(0, 1, 2, 3), edu.criteriaOf(0));
        assertEquals(List.of(4, 5, 6), edu.criteriaOf(1));
        assertEquals(List.of(7, 8), edu.criteriaOf(2));
        assertEquals(List.of(9), edu.criteriaOf(3));
    }

    @Test
    void indicatorsOfTheOtherDomainsAreNotInTheReport() {
        // I27.6 is the psychologist's (and the physiotherapist's) licence; I32 belongs to sport.
        assertFalse(edu.indicatorNames().contains("Edu26_I27_6"));
        assertFalse(edu.indicatorNames().stream().anyMatch(name -> name.startsWith("Edu26_I32")));
        assertTrue(psy.indicatorNames().contains("Psiho26_I27_6"));
    }

    // ------------------------------------------------------------------ indicator kinds

    @Test
    void theEducationalSciencesRulesAreSwitchedOnExactlyWhereTheyApply() {
        assertEquals(Set.of("I1A", "I1B", "I5", "I2", "I6", "I3A", "I3B", "I4A", "I4B", "I13"),
                edu.flagged("stiinteEducatiei2026"));
        assertTrue(edu.flagged("psihologie2026").isEmpty(), "an indicator would read Psychology's values");
    }

    @Test
    void principalAndCoauthorIndicatorsUseComplementaryRoles() {
        for (String principal : List.of("I1A", "I1B", "I2", "I8")) {
            assertEquals("FIRST_OR_CORRESPONDING", edu.kindField(principal, "role"), principal);
        }
        for (String coauthor : List.of("I5", "I6", "I9")) {
            assertEquals("NOT_FIRST_NOR_CORRESPONDING", edu.kindField(coauthor, "role"), coauthor);
        }
    }

    @Test
    void proceedingsAreCappedAtTwoPerConferenceEdition() {
        for (String proceedings : List.of("I8", "I9")) {
            assertEquals("INDEXED_PROCEEDINGS", edu.kindField(proceedings, "strategy"));
            assertTrue(edu.indicator(proceedings).get("selectorSpec").get("_class").asText().endsWith("PerForumCap"));
            assertEquals(2, edu.indicator(proceedings).get("selectorSpec").get("n").asInt());
        }
    }

    @Test
    void cappedIndicatorsCarryTheCapsOfTheStandard() {
        assertEquals(10, edu.indicator("I27_4").get("maxPoints").asInt());
        assertEquals(2, edu.indicator("I27_5").get("maxPoints").asInt());
    }

    @Test
    void publicationAndCitationFormulasOnlyUseVariablesTheEngineBinds() {
        for (String name : List.of("I1A", "I1B", "I2", "I3A", "I3B", "I4A", "I4B", "I5", "I6", "I8", "I9",
                "I11", "I13")) {
            assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(edu.asIndicator(name)), name);
        }
    }

    // ------------------------------------------------------------------ what differs from Psychology

    @Test
    void theImpactFactorThresholdIsOneTenthAndHasNoExceptionBelowIt() {
        assertEquals(3.3, edu.publication("I1A", 0.1, "Q4", false, 1, "", "ar"), 1e-9);
        assertEquals(4.2, edu.publication("I1A", 0.4, "Q4", false, 1, "", "ar"), 1e-9);
        assertEquals(0.0, edu.publication("I1A", 0.09, "Q1", false, 1, "", "ar"), 1e-9);
        // The same journal under Psychology's values: below p = 1,00 unless it is above the median.
        assertEquals(0.0, psy.publication("I1A", 0.4, "Q4", false, 1, "", "ar"), 1e-9);
        assertEquals(3.27, psy.publication("I1A", 0.09, "Q1", false, 1, "", "ar"), 1e-9);
    }

    @Test
    void articlesSplitByPublicationFeeAndByAuthorRole() {
        assertEquals(9.0, edu.publication("I1A", 2.0, null, false, 4, "", "ar"), 1e-9);
        assertEquals(0.0, edu.publication("I1B", 2.0, null, false, 4, "", "ar"), 1e-9);
        assertEquals(9.0, edu.publication("I1B", 2.0, null, true, 4, "", "ar"), 1e-9);
        assertEquals(3 + 6.0 / 4, edu.publication("I5", 2.0, null, true, 4, "", "ar"), 1e-9);
        assertEquals(0.0, edu.publication("I5", 0.05, "Q1", false, 4, "", "ar"), 1e-9);
        assertEquals(3.5 / 5, edu.publication("I6", 3.5, null, false, 5, "WOS", "ar"), 1e-9);
    }

    @Test
    void theGoogleScholarHirschIndexIsNotReducedToAQuarter() {
        assertEquals(16.0, edu.activity("I14", fields("h_GS", "4")), 1e-9);
        assertEquals(49.0, edu.activity("I14", fields("h_GS", "7")), 1e-9);
        assertEquals(4.0, psy.activity("I14", fields("h_GS", "4")), 1e-9);
    }

    @Test
    void institutionalGrantsAreWorthDouble() {
        assertEquals(9.0, edu.activity("I25",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Director", "Buget", "60000")), 1e-9);
        assertEquals(4.5, psy.activity("I25",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Director", "Buget", "60000")), 1e-9);
        // The floor and the fellowship/chair values are the annex's, the same for both domains.
        assertEquals(0.0, edu.activity("I25",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Director", "Buget", "19000")), 1e-9);
        assertEquals(4.5, edu.activity("I25", fields("Tip", "Fellowship")), 1e-9);
        assertEquals(9.0, edu.activity("I25", fields("Tip", "Chair")), 1e-9);
        assertEquals(1.5, edu.activity("I26_2",
                fields("Tip", "Cercetare aplicativă", "Rol", "Membru", "Buget", "25000")), 1e-9);
    }

    @Test
    void editorialRolesUseTheDomainsOwnThreshold() {
        // A Web of Science journal with IF 0.6: m = 3 here, m = 1 for Psychology.
        assertEquals(36.0, edu.activity("I15",
                fields("Rol", "Redactor-șef", "Indexare", "Web of Science", "IF_revista", "0.6")), 1e-9);
        assertEquals(12.0, psy.activity("I15",
                fields("Rol", "Redactor-șef", "Indexare", "Web of Science", "IF_revista", "0.6")), 1e-9);
        assertEquals(4.0, edu.activity("I15",
                fields("Rol", "Membru", "Indexare", "Web of Science", "IF_revista", "0.05")), 1e-9);
        assertEquals(8.0, edu.activity("I15", fields("Rol", "Editor asociat", "Indexare", "Altă BDI recunoscută")), 1e-9);
    }

    // ------------------------------------------------------------------ what both domains share

    @Test
    void sharedFormulasGiveTheSamePoints() {
        assertEquals(48.0, edu.publication("I3A", 3.0, null, false, 6, "A1", "bk"), 1e-9);
        assertEquals(8.0, edu.publication("I3B", 0.5, null, false, 6, "B", "bk"), 1e-9);
        assertEquals(4.0, edu.publication("I4A", 1.0, null, false, 6, "A2", "ch"), 1e-9);
        assertEquals(2.0, edu.publication("I4B", 0.5, null, false, 6, "B", "ch"), 1e-9);
        assertEquals(0.5, edu.onScore("I11", 1.0), 1e-9);
        assertEquals(49.0, edu.onScore("I13", 7.0), 1e-9);
        assertEquals(2.5, edu.activity("I7", fields("N_autori", "2")), 1e-9);
        assertEquals(0.05 * 300, edu.activity("I12", fields("Citari_GS", "420", "Citari_WoS", "120")), 1e-9);
        assertEquals(6.0, edu.activity("I16", fields("Nivel", "Internațională")), 1e-9);
        assertEquals(12.0, edu.activity("I17", fields("Categorie_editura", "A1", "N_coordonatori", "2")), 1e-9);
        assertEquals(1.0, edu.activity("I1A_bonus", fields("Tip_revista", "Fără taxă de publicare")), 1e-9);
        assertEquals(1.0, edu.activity("I1B_bonus", fields("Tip_revista", "Cu taxă de publicare")), 1e-9);
        String budget = "50.000 – 99.999 EUR";
        assertEquals(81.0, edu.activity("I24",
                fields("Rol", "Director (proiect internațional)", "Interval_buget", budget)), 1e-9);
        assertEquals(8.991, edu.activity("I24",
                fields("Rol", "Coordonator local (proiect național)", "Interval_buget", budget)), 1e-9);
        assertEquals(0.0, edu.activity("I24", fields("Rol", "Director (proiect național)")), 1e-9);
        assertEquals(3.0, edu.activity("I26_1", fields("Rol", "Membru", "Interval_buget", budget)), 1e-9);
        assertEquals(2.0, edu.activity("I27_1", fields()), 1e-9);
        assertEquals(2.0, edu.activity("I27_2", fields()), 1e-9);
        assertEquals(2.0, edu.activity("I27_2_digital", fields()), 1e-9);
        assertEquals(0.5, edu.activity("I27_3", fields()), 1e-9);
        assertEquals(0.5, edu.activity("I27_4", fields("Rol", "Membru comisie de evaluare")), 1e-9);
        assertEquals(1.0, edu.activity("I27_5", fields()), 1e-9);
    }

    @Test
    void oneDeclaredEntryScoresInBothReports() {
        for (String shared : List.of("I1A_bonus", "I1B_bonus", "I7", "I12", "I14", "I15", "I16", "I17", "I24", "I25",
                "I26_1", "I26_2", "I27_1", "I27_2", "I27_2_digital", "I27_3", "I27_4", "I27_5")) {
            assertEquals(psy.activityName(shared), edu.activityName(shared), shared);
        }
    }

    @Test
    void aCoordinatedBookScoresInEveryPublisherTier() {
        // Every tier, the decimal one included: see SeedFormulaLiteralTypesTest for why that is worth pinning.
        for (SeedReportDefinition report : List.of(edu, psy)) {
            assertEquals(12.0, report.activity("I17", fields("Categorie_editura", "A1", "N_coordonatori", "2")), 1e-9);
            assertEquals(4.0, report.activity("I17", fields("Categorie_editura", "A2", "N_coordonatori", "2")), 1e-9);
            assertEquals(2.0, report.activity("I17", fields("Categorie_editura", "B", "N_coordonatori", "2")), 1e-9);
            assertEquals(4.0, report.activity("I17", fields("Categorie_editura", "B")), 1e-9);
        }
    }

    // ------------------------------------------------------------------ indicators only this domain has

    @Test
    void proceedingsPapers() {
        assertEquals(1.0, edu.publication("I8", 1.0, null, false, 4, "BDI", "cp"), 1e-9);
        assertEquals(0.25, edu.publication("I9", 1.0, null, false, 4, "BDI", "cp"), 1e-9);
    }

    @Test
    void policyReports() {
        assertEquals(24.0, edu.activity("I10", fields("Nivel", "Internațional")), 1e-9);
        assertEquals(8.0, edu.activity("I10", fields("Nivel", "Internațional", "N_autori", "3")), 1e-9);
        assertEquals(4.0, edu.activity("I10", fields("Nivel", "Național", "N_autori", "2")), 1e-9);
    }

    @Test
    void conferenceCommitteesAssociationsAndAwards() {
        assertEquals(3.0, edu.activity("I18",
                fields("Calitate", "Membru în comitetul științific", "Nivel", "Internațională")), 1e-9);
        assertEquals(1.0, edu.activity("I18",
                fields("Calitate", "Coordonator de simpozion", "Nivel", "Națională")), 1e-9);

        String board = "Președinte sau membru în comitetul executiv";
        assertEquals(6.0, edu.activity("I19", fields("Rol", board, "Nivel", "Internațională")), 1e-9);
        assertEquals(2.0, edu.activity("I19", fields("Rol", board, "Nivel", "Națională")), 1e-9);
        assertEquals(2.0, edu.activity("I19", fields("Rol", "Membru", "Nivel", "Internațională")), 1e-9);
        assertEquals(1.0, edu.activity("I19", fields("Rol", "Membru", "Nivel", "Națională")), 1e-9);

        assertEquals(12.0, edu.activity("I20", fields("Tip", "Științific internațional")), 1e-9);
        assertEquals(4.0, edu.activity("I20", fields("Tip", "Științific național")), 1e-9);
        assertEquals(4.0, edu.activity("I20", fields("Tip", "Didactic")), 1e-9);
        assertEquals(4.0, edu.activity("I20", fields("Tip", "Promovarea țării")), 1e-9);
    }

    @Test
    void collectionsReviewingAndVisitingPositions() {
        assertEquals(6.0, edu.activity("I21", fields()), 1e-9);

        assertEquals(0.3, edu.activity("I22", fields("Indexare", "Web of Science")), 1e-9);
        assertEquals(1.5, edu.activity("I22", fields("Indexare", "Web of Science", "N_articole", "5")), 1e-9);
        assertEquals(0.4, edu.activity("I22", fields("Indexare", "Altă BDI recunoscută", "N_articole", "2")), 1e-9);

        assertEquals(1.5, edu.activity("I23", fields("Tip", "Universitate din TOP 500 URAP")), 1e-9);
        assertEquals(0.5, edu.activity("I23", fields("Tip", "Altă universitate, invitație nominală")), 1e-9);
        assertEquals(0.5, edu.activity("I23", fields("Tip", "Asociație profesională internațională")), 1e-9);
        assertEquals(0.25, edu.activity("I23", fields("Tip", "Asociație profesională națională")), 1e-9);
    }

    @Test
    void centresEvaluationExpertGroupsAndTraining() {
        assertEquals(2.0, edu.activity("I28", fields()), 1e-9);
        assertEquals(3.0, edu.activity("I29", fields("Nivel", "Internațională")), 1e-9);
        assertEquals(1.0, edu.activity("I29", fields("Nivel", "Națională")), 1e-9);
        assertEquals(3.0, edu.activity("I30", fields("Nivel", "Internațional")), 1e-9);
        assertEquals(1.0, edu.activity("I30", fields("Nivel", "Național")), 1e-9);
        assertEquals(0.5, edu.activity("I31", fields()), 1e-9);
    }

    @Test
    void everyOptionComparedInAFormulaIsAnOptionOfItsActivity() {
        edu.assertComparedOptionsExist();
    }

    // ------------------------------------------------------------------ descriptions

    @Test
    void everyIndicatorHasADescriptionAndNoDescriptionIsOrphaned() throws Exception {
        edu.assertDescribedBy("stiinte-educatiei-2026.json");
    }
}
