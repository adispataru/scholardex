package ro.uvt.pokedex.core.reporting;

import org.junit.jupiter.api.AfterEach;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import java.util.Map;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
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
        // H143: the coordinated book and the collection too — their publisher's category comes from the lists
        assertEquals(Set.of("I1A", "I1B", "I5", "I2", "I6", "I3A", "I3B", "I4A", "I4B", "I13", "I17", "I21"),
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
    void proceedingsAreCappedAtTwoPerConferenceEditionTogether() {
        for (String proceedings : List.of("I8", "I9")) {
            assertEquals("INDEXED_PROCEEDINGS", edu.kindField(proceedings, "strategy"));
            assertEquals(2, edu.indicator(proceedings).get("selectorSpec").get("n").asInt());
        }
        // H145: "se pot puncta cumulat cel mult două contribuţii / ediţie" — the principal-author papers (I8) take an
        // edition's places first, the co-authored ones (I9) keep what is left
        assertTrue(edu.indicator("I8").get("selectorSpec").get("_class").asText().endsWith("PerForumCap"));
        assertTrue(edu.indicator("I9").get("selectorSpec").get("_class").asText().endsWith("PerEditionCapAfterPrincipal"));
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
        String verified = "Valorile din profilul Google Scholar, verificate";
        assertEquals(16.0, edu.approved("I14", fields("h_GS", "4", "Incadrare_solicitata", verified)), 1e-9);
        assertEquals(49.0, edu.approved("I14", fields("h_GS", "7", "Incadrare_solicitata", verified)), 1e-9);
        assertEquals(4.0, psy.approved("I14", fields("h_GS", "4", "Incadrare_solicitata", verified)), 1e-9);
        assertEquals(0.0, edu.activity("I14", fields("h_GS", "15")), 1e-9, "H145: a typed h-index counts only once approved");
    }

    @Test
    void institutionalGrantsAreWorthDouble() {
        // H145: the funder or awarding body is named and ranked; fellowships and chairs need an international one
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "Fondul Social European", "INTERNATIONAL", "FUNDER", null);
        var fse = named(Activity.ReferenceField.ORGANIZATION_NAME, "Fondul Social European");
        assertEquals(9.0, edu.activityNaming("I25",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Director", "Buget", "60000"), fse), 1e-9);
        assertEquals(4.5, psy.activityNaming("I25",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Director", "Buget", "60000"), fse), 1e-9);
        // The floor and the fellowship/chair values are the annex's, the same for both domains.
        assertEquals(0.0, edu.activityNaming("I25",
                fields("Tip", "Dezvoltare instituțională", "Rol", "Director", "Buget", "19000"), fse), 1e-9);
        assertEquals(4.5, edu.activityNaming("I25", fields("Tip", "Fellowship"), fse), 1e-9);
        assertEquals(9.0, edu.activityNaming("I25", fields("Tip", "Chair"), fse), 1e-9);
        assertEquals(1.5, edu.activityNaming("I26_2",
                fields("Tip", "Cercetare aplicativă", "Rol", "Membru", "Buget", "25000"), fse), 1e-9);
        assertEquals(0.0, edu.activity("I25", fields("Tip", "Chair")), 1e-9, "nothing named");
    }

    @AfterEach
    void resetRegistries() {
        SeedReportDefinition.resetRegistries();
    }

    private static Map<Activity.ReferenceField, String> named(Activity.ReferenceField field, String name) {
        return Map.of(field, name);
    }

    private static Map<Activity.ReferenceField, String> issn(String issn) {
        return Map.of(Activity.ReferenceField.FORUM_ISSN, issn);
    }

    @Test
    void editorialRolesUseTheDomainsOwnThreshold() {
        // A Web of Science journal with IF 0.6: m = 3 here, m = 1 for Psychology (H144: indexing and IF from the lists).
        SeedReportDefinition.journal("1111-1111", false, 0.6, "SSCI", "SCOPUS", "ERIH");
        SeedReportDefinition.journal("2222-2222", false, 0.05, "SSCI", "SCOPUS", "ERIH");
        SeedReportDefinition.journal("3333-3333", false, null, "SCOPUS", "DOAJ");
        assertEquals(36.0, edu.activityNaming("I15", fields("Rol", "Redactor-șef"), issn("1111-1111")), 1e-9);
        assertEquals(12.0, psy.activityNaming("I15", fields("Rol", "Redactor-șef"), issn("1111-1111")), 1e-9);
        assertEquals(4.0, edu.activityNaming("I15", fields("Rol", "Membru"), issn("2222-2222")), 1e-9);
        assertEquals(8.0, edu.activityNaming("I15", fields("Rol", "Editor asociat"), issn("3333-3333")), 1e-9);
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
        assertEquals(2.5, edu.approved("I7", fields("N_autori", "2",
                "Incadrare_solicitata", "Avizat, acreditat sau înregistrat (CPR, MEN, OSIM, ORDA)")), 1e-9);
        assertEquals(0.05 * 300, edu.approved("I12", fields("Citari_GS", "420", "Citari_WoS", "120",
                "Incadrare_solicitata", "Valorile din profilul Google Scholar, verificate")), 1e-9);
        SeedReportDefinition.rank(RegistryKind.SCIENTIFIC_EVENT, "ECER 2024", "INTERNATIONAL", "CONFERENCE", "Cyprus");
        assertEquals(6.0, edu.activityNaming("I16", fields(), named(Activity.ReferenceField.CONFERENCE_NAME, "ECER 2024")), 1e-9);
        assertEquals(12.0, edu.activity("I17", fields("Editura", "Routledge", "N_coordonatori", "2")), 1e-9);
        SeedReportDefinition.journal("4444-4444", false, 1.2, "SSCI", "SCOPUS", "ERIH");
        SeedReportDefinition.journal("5555-5555", true, null, "ESCI", "SCOPUS");
        var asked = fields("Incadrare_solicitata", "Articol punctat la I1A sau I1B, preînregistrat, cu date deschise");
        assertEquals(1.0, edu.approvedNaming("I1A_bonus", asked, issn("4444-4444")), 1e-9);
        assertEquals(1.0, edu.approvedNaming("I1B_bonus", asked, issn("5555-5555")), 1e-9);
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
            assertEquals(12.0, report.activity("I17", fields("Editura", "Routledge", "N_coordonatori", "2")), 1e-9);
            assertEquals(4.0, report.activity("I17", fields("Editura", "Polirom", "N_coordonatori", "2")), 1e-9);
            assertEquals(2.0, report.activity("I17", fields("Editura", "Humanitas", "N_coordonatori", "2")), 1e-9);
            assertEquals(4.0, report.activity("I17", fields("Editura", "Humanitas")), 1e-9);
            // H143: the routes of the standard for one book, once a head approves them, and only then
            String worldCat = "A1 — minimum 25 de biblioteci universitare din UE/OCDE în WorldCat";
            String twoCriteria = "A2 — cel puțin două criterii din ruta complementară";
            assertEquals(24.0, report.activityWithDecision("I17", fields("Editura", "Editura Proprie",
                    "Incadrare_solicitata", worldCat), PublisherClaim.Status.APPROVED), 1e-9);
            assertEquals(0.0, report.activityWithDecision("I17", fields("Editura", "Editura Proprie",
                    "Incadrare_solicitata", worldCat), PublisherClaim.Status.PENDING), 1e-9);
            assertEquals(8.0, report.activityWithDecision("I17", fields("Editura", "Humanitas",
                    "Incadrare_solicitata", twoCriteria), PublisherClaim.Status.APPROVED), 1e-9,
                    "the complementary route raises a listed B book to A2");
            assertEquals(8.0, report.activityWithDecision("I17", fields("Editura", "Polirom",
                    "Incadrare_solicitata", "B — un criteriu din ruta complementară"), PublisherClaim.Status.APPROVED), 1e-9,
                    "a request never lowers what the list gives (A2)");
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
        // H144: the organisation that commissioned the report, ranked in the registry
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "UNESCO", "INTERNATIONAL", "AGENCY", null);
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "Ministerul Educației", "NATIONAL", "AGENCY", "România");
        var unesco = named(Activity.ReferenceField.ORGANIZATION_NAME, "UNESCO");
        assertEquals(24.0, edu.activityNaming("I10", fields("N_autori", "1"), unesco), 1e-9);
        assertEquals(8.0, edu.activityNaming("I10", fields("N_autori", "3"), unesco), 1e-9);
        assertEquals(4.0, edu.activityNaming("I10", fields("N_autori", "2"),
                named(Activity.ReferenceField.ORGANIZATION_NAME, "Ministerul Educației")), 1e-9);
        // H145: an organisation the experts have not ranked counts nothing, nor does a report with no author count
        assertEquals(0.0, edu.activityNaming("I10", fields("N_autori", "2"),
                named(Activity.ReferenceField.ORGANIZATION_NAME, "Inspectoratul Școlar")), 1e-9);
        assertEquals(0.0, edu.activityNaming("I10", fields(), unesco), 1e-9);
    }

    @Test
    void conferenceCommitteesAssociationsAndAwards() {
        SeedReportDefinition.rank(RegistryKind.SCIENTIFIC_EVENT, "ECER 2024", "INTERNATIONAL", "CONFERENCE", "Cyprus");
        assertEquals(3.0, edu.activityNaming("I18", fields("Calitate", "Membru în comitetul științific"),
                named(Activity.ReferenceField.CONFERENCE_NAME, "ECER 2024")), 1e-9);
        assertEquals(1.0, edu.activityNaming("I18", fields("Calitate", "Coordonator de simpozion"),
                named(Activity.ReferenceField.CONFERENCE_NAME, "Simpozionul Educația azi")), 1e-9);

        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "EERA", "INTERNATIONAL", "ASSOCIATION", null);
        var eera = named(Activity.ReferenceField.ORGANIZATION_NAME, "EERA");
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "Asociația Profesorilor din Timiș", "NATIONAL", "ASSOCIATION", "România");
        var local = named(Activity.ReferenceField.ORGANIZATION_NAME, "Asociația Profesorilor din Timiș");
        String board = "Președinte sau membru în comitetul executiv";
        assertEquals(6.0, edu.activityNaming("I19", fields("Rol", board), eera), 1e-9);
        assertEquals(2.0, edu.activityNaming("I19", fields("Rol", board), local), 1e-9);
        assertEquals(2.0, edu.activityNaming("I19", fields("Rol", "Membru"), eera), 1e-9);
        assertEquals(1.0, edu.activityNaming("I19", fields("Rol", "Membru"), local), 1e-9);
        // H145: an association the experts have not ranked, or a role nobody states, counts nothing
        assertEquals(0.0, edu.activityNaming("I19", fields("Rol", board),
                named(Activity.ReferenceField.ORGANIZATION_NAME, "Asociația nouă")), 1e-9);
        assertEquals(0.0, edu.activityNaming("I19", fields(), eera), 1e-9);

        SeedReportDefinition.rank(RegistryKind.AWARD, "EERA Best Paper Award", "INTERNATIONAL", "SCIENTIFIC", null);
        SeedReportDefinition.rank(RegistryKind.AWARD, "Premiul Academiei Române", "NATIONAL", "SCIENTIFIC", "România");
        SeedReportDefinition.rank(RegistryKind.AWARD, "Profesorul anului", "INTERNATIONAL", "DIDACTIC", null);
        assertEquals(12.0, edu.activityNaming("I20", fields(), named(Activity.ReferenceField.AWARD_NAME, "EERA Best Paper Award")), 1e-9);
        assertEquals(4.0, edu.activityNaming("I20", fields(), named(Activity.ReferenceField.AWARD_NAME, "Premiul Academiei Române")), 1e-9);
        assertEquals(4.0, edu.activityNaming("I20", fields(), named(Activity.ReferenceField.AWARD_NAME, "Profesorul anului")), 1e-9,
                "a teaching award counts 4 whatever its reach");
        assertEquals(0.0, edu.activityNaming("I20", fields(), named(Activity.ReferenceField.AWARD_NAME, "Un premiu nou")), 1e-9,
                "H145: the standard counts prestigious awards — one the experts have not ranked counts nothing");
        assertEquals(0.0, edu.activity("I20", fields()), 1e-9, "no award named");
    }

    @Test
    void collectionsReviewingAndVisitingPositions() {
        assertEquals(6.0, edu.activity("I21", fields("Editura", "Polirom")), 1e-9);
        assertEquals(0.0, edu.activity("I21", fields()), 1e-9, "a collection counts at a classified publisher");

        SeedReportDefinition.journal("1111-1111", false, null, "ESCI", "SCOPUS"); // ESCI counts as Web of Science here
        SeedReportDefinition.journal("2222-2222", false, null, "DOAJ");
        SeedReportDefinition.journal("3333-3333", false, null);
        assertEquals(0.3, edu.activityNaming("I22", fields(), issn("1111-1111")), 1e-9);
        assertEquals(1.5, edu.activityNaming("I22", fields("N_articole", "5"), issn("1111-1111")), 1e-9);
        assertEquals(0.4, edu.activityNaming("I22", fields("N_articole", "2"), issn("2222-2222")), 1e-9);
        assertEquals(0.0, edu.activityNaming("I22", fields("N_articole", "2"), issn("3333-3333")), 1e-9,
                "a journal in no database the lists know needs an approved request");

        // H144: the URAP position of the named university, the level of the named association
        SeedReportDefinition.university("University of Helsinki", 101, "Finland");
        SeedReportDefinition.university("Universitatea din Craiova", 1450, "Romania");
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "EERA", "INTERNATIONAL", "ASSOCIATION", null);
        assertEquals(1.5, edu.activityNaming("I23", fields(), named(Activity.ReferenceField.UNIVERSITY_NAME, "University of Helsinki")), 1e-9);
        assertEquals(0.5, edu.activityNaming("I23", fields(), named(Activity.ReferenceField.UNIVERSITY_NAME, "Universitatea din Craiova")), 1e-9);
        assertEquals(0.5, edu.activityNaming("I23", fields(), named(Activity.ReferenceField.ORGANIZATION_NAME, "EERA")), 1e-9);
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "Asociația Profesorilor din Timiș", "NATIONAL", "ASSOCIATION", "România");
        assertEquals(0.25, edu.activityNaming("I23", fields(),
                named(Activity.ReferenceField.ORGANIZATION_NAME, "Asociația Profesorilor din Timiș")), 1e-9);
        // H145: a university no ranking knows, or an association the experts have not ranked, counts nothing
        assertEquals(0.0, edu.activityNaming("I23", fields(),
                named(Activity.ReferenceField.UNIVERSITY_NAME, "Universitatea Inventată")), 1e-9);
        assertEquals(0.0, edu.activityNaming("I23", fields(),
                named(Activity.ReferenceField.ORGANIZATION_NAME, "Asociația nouă")), 1e-9);
        assertEquals(0.0, edu.activity("I23", fields()), 1e-9, "nothing named");
    }

    @Test
    void centresEvaluationExpertGroupsAndTraining() {
        assertEquals(2.0, edu.activity("I28", fields()), 1e-9);
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "European Research Council", "INTERNATIONAL", "FUNDER", null);
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "UEFISCDI", "NATIONAL", "FUNDER", "România");
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "UNESCO", "INTERNATIONAL", "AGENCY", null);
        SeedReportDefinition.rank(RegistryKind.ORGANIZATION, "ARACIS", "NATIONAL", "AGENCY", "România");
        assertEquals(3.0, edu.activityNaming("I29", fields(), named(Activity.ReferenceField.ORGANIZATION_NAME, "European Research Council")), 1e-9);
        assertEquals(1.0, edu.activityNaming("I29", fields(), named(Activity.ReferenceField.ORGANIZATION_NAME, "UEFISCDI")), 1e-9);
        assertEquals(3.0, edu.activityNaming("I30", fields(), named(Activity.ReferenceField.ORGANIZATION_NAME, "UNESCO")), 1e-9);
        assertEquals(1.0, edu.activityNaming("I30", fields(), named(Activity.ReferenceField.ORGANIZATION_NAME, "ARACIS")), 1e-9);
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
