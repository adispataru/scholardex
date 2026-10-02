package ro.uvt.pokedex.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingBlock;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;
import ro.uvt.pokedex.core.service.reporting.ScoringReferenceYearContext;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ro.uvt.pokedex.core.reporting.SeedReportDefinition.fields;

/**
 * Pins the committed "FV Muzică 2026" definition to OM 3.019/2025, COMISIA 35, domain Music (H142): the three
 * tables and the total with their thresholds for the three ranks, the minimum counts of the theoretician, composer
 * and performer, the labelled routes that join them, the points of every item, and the readings the platform chose
 * where the annex is silent (visibility from the registry of artistic events, a performance without a role counts,
 * a book needs its publisher category).
 */
class Muzica2026ReportDefinitionTest {

    private static SeedReportDefinition muz;

    @BeforeAll
    static void load() {
        muz = new SeedReportDefinition("FV Muzică 2026", "Muz26_");
    }

    @AfterEach
    void resetRegistry() {
        ArtisticEventRankSupport.reset();
    }

    // ------------------------------------------------------------------ criteria and thresholds

    @Test
    void theThreeTablesAndTheTotalHaveTheThresholdsOfTheStandard() {
        muz.assertThresholds("Tabelul 1", 70.0, 100.0, 85.0);
        muz.assertThresholds("Tabelul 2", 180.0, 240.0, 210.0);
        muz.assertThresholds("Tabelul 3", 50.0, 100.0, 75.0);
        muz.assertThresholds("Punctaj total", 300.0, 440.0, 370.0);
        assertEquals(16, muz.report().get("criteria").size());
    }

    @Test
    void theMinimumCountsDependOnTheProfile() {
        muz.assertThresholds("Cărți (DID 1.1) — teoreticieni", 1.0, 2.0, 2.0);
        muz.assertThresholds("Cărți (DID 1.1) — compozitori și interpreți", 1.0, 1.0, 1.0);
        muz.assertThresholds("Manuale, cursuri (DID 1.3) — teoreticieni", 2.0, 2.0, 2.0);
        muz.assertThresholds("Manuale, cursuri (DID 1.3) — compozitori și interpreți", 1.0, 1.0, 1.0);
        muz.assertThresholds("Înregistrări (DID 2.1)", 1.0, 2.0, 1.0);
        muz.assertThresholds("Concerte de vizibilitate", 5.0, 8.0, 7.0);
        muz.assertThresholds("Articole indexate (CS 2.1) — teoreticieni", 5.0, 8.0, 6.0);
        muz.assertThresholds("Articole indexate (CS 2.1) — compozitori", 1.0, 2.0, 1.0);
        muz.assertThresholds("Articole indexate (CS 2.1) — interpreți", null, 1.0, 1.0);
        muz.assertThresholds("Comunicări (CS 2.3) — teoreticieni", 5.0, 8.0, 6.0);
        muz.assertThresholds("Comunicări (CS 2.3) — compozitori", 1.0, 2.0, 1.0);
        muz.assertThresholds("Comunicări (CS 2.3) — interpreți", null, 1.0, 1.0);
    }

    @Test
    void theStandardSetsNoThresholdsBelowConferentiar() {
        muz.report().get("criteria").forEach(criterion -> criterion.get("thresholds").forEach(threshold ->
                assertTrue(Set.of("CONF_UNIV", "PROF_UNIV", "HABIL").contains(threshold.get("position").asText()),
                        criterion.get("name").asText() + " carries a threshold for " + threshold.get("position"))));
    }

    @Test
    void eachTableAddsUpItsOwnItemsAndTheTotalAddsUpAllOfThem() {
        assertEquals(Set.of("DID_1_1", "DID_1_2", "DID_1_3", "DID_1_4", "DID_2_1"), muz.shortMembers("Tabelul 1"));
        assertEquals(Set.of("CS_1_1", "CS_1_2", "CS_2_1", "CS_2_1_decl", "CS_2_2", "CS_2_3", "CS_3_1", "CS_4_1"),
                muz.shortMembers("Tabelul 2"));
        Set<String> ria = muz.shortMembers("Tabelul 3");
        assertEquals(15, ria.size());
        for (String item : List.of("RIA_1_1", "RIA_1_2", "RIA_1_3", "RIA_1_4", "RIA_1_5", "RIA_2_1", "RIA_2_2", "RIA_2_3",
                "RIA_3_1", "RIA_3_2", "RIA_3_3", "RIA_3_4", "RIA_3_5", "RIA_3_6", "RIA_3_7")) {
            assertTrue(ria.contains(item), item);
        }
        Set<String> total = new LinkedHashSet<>(muz.shortMembers("Tabelul 1"));
        total.addAll(muz.shortMembers("Tabelul 2"));
        total.addAll(ria);
        assertEquals(total, muz.shortMembers("Punctaj total"));
    }

    @Test
    void countsNeverAddPointsToATable() {
        Set<String> counted = Set.of("N_carti", "N_manuale", "N_inregistrari", "N_concerte_varf", "N_articole",
                "N_articole_decl", "N_comunicari");
        for (String table : List.of("Tabelul 1", "Tabelul 2", "Tabelul 3", "Punctaj total")) {
            for (String member : muz.shortMembers(table)) {
                assertTrue(!counted.contains(member), table + " adds up the count " + member);
            }
        }
        assertEquals(Set.of("N_articole", "N_articole_decl"), muz.shortMembers("Articole indexate (CS 2.1) — teoreticieni"));
        assertEquals(Set.of("N_concerte_varf"), muz.shortMembers("Concerte de vizibilitate"));
    }

    @Test
    void theMinimumActivitiesAreThreeLabelledRoutesAndTheVerdictNeedsEverything() {
        List<JsonNode> perspectives = new ArrayList<>();
        muz.report().get("perspectives").forEach(perspectives::add);
        assertEquals(6, perspectives.size());
        JsonNode minimum = perspectives.get(4);
        assertEquals("Activități minimale obligatorii", minimum.get("name").asText());
        JsonNode routes = minimum.get("composition").get("any");
        assertEquals(3, routes.size());
        Map<String, Set<String>> byRoute = Map.of(
                "Teoretician", Set.of("Cărți (DID 1.1) — teoreticieni", "Manuale, cursuri (DID 1.3) — teoreticieni",
                        "Articole indexate (CS 2.1) — teoreticieni", "Comunicări (CS 2.3) — teoreticieni"),
                "Compozitor", Set.of("Cărți (DID 1.1) — compozitori și interpreți",
                        "Manuale, cursuri (DID 1.3) — compozitori și interpreți",
                        "Înregistrări (DID 2.1) — compozitori și interpreți",
                        "Concerte de vizibilitate internațională sau națională de vârf (CS 1.1) — compozitori și interpreți",
                        "Articole indexate (CS 2.1) — compozitori", "Comunicări (CS 2.3) — compozitori"),
                "Interpret", Set.of("Cărți (DID 1.1) — compozitori și interpreți",
                        "Manuale, cursuri (DID 1.3) — compozitori și interpreți",
                        "Înregistrări (DID 2.1) — compozitori și interpreți",
                        "Concerte de vizibilitate internațională sau națională de vârf (CS 1.1) — compozitori și interpreți",
                        "Articole indexate (CS 2.1) — interpreți", "Comunicări (CS 2.3) — interpreți"));
        JsonNode criteria = muz.report().get("criteria");
        for (JsonNode route : routes) {
            Set<String> named = new LinkedHashSet<>();
            route.get("all").forEach(leaf -> named.add(criteria.get(leaf.get("criterion").asInt()).get("name").asText()));
            assertEquals(byRoute.get(route.get("label").asText()), named, route.get("label").asText());
        }
        JsonNode verdict = perspectives.get(5).get("composition").get("all");
        Set<Integer> referenced = new LinkedHashSet<>();
        verdict.forEach(leaf -> referenced.add(leaf.get("perspective").asInt()));
        assertEquals(Set.of(0, 1, 2, 3, 4), referenced);
    }

    // ------------------------------------------------------------------ points of every item

    @Test
    void didItems() {
        // H143: "publicat" = a publisher CNCS classifies A or B (Music first, else any domain), or an equivalent
        // foreign one; the category comes from the lists, matched on the publisher the researcher types
        assertEquals(30.0, muz.activity("DID_1_1", fields("Editura", "Editura Universității Naționale de Muzică București")), 1e-9);
        assertEquals(30.0, muz.activity("DID_1_1", fields("Editura", "Eurostampa")), 1e-9);
        assertEquals(30.0, muz.activity("DID_1_1", fields("Editura", "Editura Universității de Vest")), 1e-9,
                "classified B and A in other domains, not in Music");
        assertEquals(30.0, muz.activity("DID_1_1", fields("Editura", "Bärenreiter")), 1e-9);
        assertEquals(0.0, muz.activity("DID_1_1", fields("Editura", "Universitaria")), 1e-9,
                "rated C in Music by both lists, whatever it is elsewhere");
        assertEquals(0.0, muz.activity("DID_1_1", fields("Editura", "Lambert Academic Publishing")), 1e-9);
        assertEquals(0.0, muz.activity("DID_1_1", fields("Editura", "Editura Proprie")), 1e-9);
        assertEquals(0.0, muz.activity("DID_1_1", fields()), 1e-9, "a book needs its publisher");
        String foreign = "Editură străină echivalentă (categoria A sau B)";
        assertEquals(30.0, muz.activityWithDecision("DID_1_1", fields("Editura", "Schott Music",
                "Incadrare_solicitata", foreign), PublisherClaim.Status.APPROVED), 1e-9);
        assertEquals(0.0, muz.activityWithDecision("DID_1_1", fields("Editura", "Schott Music",
                "Incadrare_solicitata", foreign), PublisherClaim.Status.PENDING), 1e-9);
        assertEquals(15.0, muz.activity("DID_1_2", fields()), 1e-9);
        assertEquals(10.0, muz.activity("DID_1_3", fields("Tip", "Suport de curs")), 1e-9);
        assertEquals(20.0, muz.activity("DID_1_4", fields("Tip", "Ediție critică")), 1e-9);
        assertEquals(30.0, muz.activity("DID_2_1", fields("Suport", "CD")), 1e-9);
        assertEquals(30.0, muz.activity("DID_2_1", fields("Suport", "DVD", "Durata_minute", "60")), 1e-9);
        assertEquals(0.0, muz.activity("DID_2_1", fields("Suport", "CD", "Durata_minute", "30")), 1e-9,
                "a programme shorter than 45 minutes");
    }

    @Test
    void concertsTakeTheirVisibilityFromTheRankTheExpertsGaveTheEvent() {
        ArtisticEvent filarmonica = event("Stagiunea Filarmonicii „George Enescu” (București)", ArtisticEvent.Rank.NATIONAL_TOP);
        filarmonica.getAliases().add("Filarmonica George Enescu");
        ArtisticEventRankSupport.register(List.of(event("Festivalul „George Enescu” (România)", ArtisticEvent.Rank.INTERNATIONAL_TOP),
                event("Festivalul de muzică veche (Timișoara)", ArtisticEvent.Rank.NATIONAL), filarmonica,
                event("Serile muzicale din parc", ArtisticEvent.Rank.LOCAL)));
        assertEquals(20.0, muz.activity("CS_1_1", fields("Rol", "Solist"), "Festivalul George Enescu (România)"), 1e-9);
        assertEquals(0.0, muz.activity("CS_1_2", fields("Rol", "Solist"), "Festivalul George Enescu (România)"), 1e-9);
        assertEquals(10.0, muz.activity("CS_1_2", fields("Rol", "Dirijor"), "Festivalul de muzică veche (Timișoara)"), 1e-9);
        assertEquals(20.0, muz.activity("CS_1_1", fields("Rol", "Solist"), "Filarmonica George Enescu"), 1e-9,
                "a spelling of a Romanian institution the experts ranked national-top");
        assertEquals(10.0, muz.activity("CS_1_2", fields("Rol", "Solist"), "Serile muzicale din parc"), 1e-9);
        // nobody has ranked it yet: regional/local until an expert of the domain does
        assertEquals(0.0, muz.activity("CS_1_1", fields(), "Stagiunea unei filarmonici din străinătate"), 1e-9);
        assertEquals(10.0, muz.activity("CS_1_2", fields(), "Stagiunea unei filarmonici din străinătate"), 1e-9);
        assertEquals(10.0, muz.activity("CS_1_2", fields(), "Concert în aula universității"), 1e-9);
        assertEquals(0.0, muz.activity("CS_1_2", fields("Rol", "Membru într-un ansamblu de peste 10 persoane"),
                "Concert în aula universității"), 1e-9);
        assertEquals(1.0, muz.activity("N_concerte_varf", fields("Rol", "Concert-maestru"),
                "Festivalul George Enescu (România)"), 1e-9);
        assertEquals(0.0, muz.activity("N_concerte_varf", fields(), "Concert în aula universității"), 1e-9);
    }

    @Test
    void aPrizeAtACompetitionIsRecognitionNotAConcert() {
        assertEquals(40.0, muz.activity("RIA_2_3", fields("Rezultat", "Premiu"), "Concursul Remember Enescu"), 1e-9);
        assertEquals(40.0, muz.activity("RIA_2_3", fields("Tip", "Premiu individual"), "Concursul Remember Enescu"), 1e-9);
        assertEquals(0.0, muz.activity("CS_1_2", fields("Rezultat", "Premiu"), "Concursul Remember Enescu"), 1e-9);
        assertEquals(0.0, muz.activity("RIA_2_3", fields("Rezultat", "Nominalizare"), "Gala UCMR"), 1e-9);
        assertEquals(0.0, muz.activity("CS_1_2", fields("Rezultat", "Nominalizare"), "Gala UCMR"), 1e-9);
    }

    @Test
    void csItems() {
        assertEquals(15.0, muz.onScore("CS_2_1", 1.0), 1e-9);
        assertEquals(1.0, muz.onScore("N_articole", 1.0), 1e-9);
        assertEquals(15.0, muz.activity("CS_2_1_decl", fields("Baza_de_date", "CEEOL")), 1e-9);
        assertEquals(10.0, muz.activity("CS_2_2", fields("Tip", "Rezumat pentru RIPM, RILM, RISM sau RIDIM")), 1e-9);
        assertEquals(15.0, muz.activity("CS_2_3", fields("Loc", "În străinătate")), 1e-9);
        assertEquals(10.0, muz.activity("CS_3_1", fields("Rol", "Membru")), 1e-9);
        assertEquals(0.0, muz.activity("CS_3_1", fields("Rol", "Director (proiect național)")), 1e-9);
        assertEquals(15.0, muz.activity("CS_4_1", fields()), 1e-9);
    }

    @Test
    void riaItems() {
        assertEquals(50.0, muz.activity("RIA_1_1", fields("An_inceput", "2016", "An_sfarsit", "2020")), 1e-9);
        double held = ScoringReferenceYearContext.with(2026,
                () -> muz.activity("RIA_1_1", fields("Functia", "Prodecan", "An_inceput", "2024")));
        assertEquals(30.0, held, 1e-9, "2024 to 2026 while still held");
        assertEquals(30.0, muz.activity("RIA_1_2", fields("Rol", "Director (proiect național)")), 1e-9);
        assertEquals(30.0, muz.activity("RIA_1_2", fields("Rol", "Coordonator local (proiect internațional)")), 1e-9);
        assertEquals(0.0, muz.activity("RIA_1_2", fields("Rol", "Membru")), 1e-9);
        assertEquals(10.0, muz.activity("RIA_1_3", fields("Rol", "Recenzor")), 1e-9);
        assertEquals(30.0, muz.activity("RIA_1_4", fields("Nivel", "Internațional")), 1e-9);
        assertEquals(0.0, muz.activity("RIA_1_5", fields("Nivel", "Internațional")), 1e-9);
        assertEquals(10.0, muz.activity("RIA_1_5", fields("Nivel", "Național")), 1e-9);
        assertEquals(10.0, muz.activity("RIA_1_5", fields()), 1e-9, "no level declared: national");
        assertEquals(40.0, muz.activity("RIA_2_1", fields()), 1e-9);
        assertEquals(30.0, muz.activity("RIA_2_2", fields()), 1e-9);
        assertEquals(5.0, muz.activity("RIA_3_1", fields("Rol", "Membru")), 1e-9);
        assertEquals(0.0, muz.activity("RIA_3_1", fields("Rol", "Funcție de conducere", "An_inceput", "2020")), 1e-9);
        assertEquals(60.0, muz.activity("RIA_3_2", fields("Rol", "Funcție de conducere", "An_inceput", "2019",
                "An_sfarsit", "2024")), 1e-9);
        assertEquals(10.0, muz.activity("RIA_3_3", fields("Nivel", "Internațional")), 1e-9);
        assertEquals(10.0, muz.activity("RIA_3_4", fields("Beneficiar", "UCMR")), 1e-9);
        assertEquals(20.0, muz.activity("RIA_3_5", fields("Loc", "În țară")), 1e-9);
        assertEquals(30.0, muz.activity("RIA_3_5", fields("Loc", "În străinătate, în limbă străină")), 1e-9);
        assertEquals(5.0, muz.activity("RIA_3_6", fields("Difuzare", "Națională")), 1e-9);
        assertEquals(20.0, muz.activity("RIA_3_7", fields("Nivel", "Național")), 1e-9);
        assertEquals(30.0, muz.activity("RIA_3_7", fields("Nivel", "Internațional")), 1e-9);
    }

    @Test
    void aFilledGridOfTheFacultyScoresTheTotalsItClaims() {
        // The counts of a grid the faculty sent (a lecturer, conferențiar thresholds): DID 400, CS 1700, RIA 770.
        double did = muz.activity("DID_1_1", fields("Editura", "Editura Universității Naționale de Muzică București"))
                + muz.activity("DID_1_3", fields("Tip", "Suport de curs"))
                + 12 * muz.activity("DID_2_1", fields("Suport", "Streaming (înregistrare video din concert public)"));
        // the experts ranked the festival abroad (the grid's own CS 1.1 row is only their suggestion)
        ArtisticEventRankSupport.register(List.of(event("Festival din străinătate", ArtisticEvent.Rank.INTERNATIONAL)));
        double cs = 14 * muz.activity("CS_1_1", fields("Rol", "Dirijor"), "Festival din străinătate")
                + 139 * muz.activity("CS_1_2", fields("Rol", "Dirijor"), "Concert local")
                + 2 * muz.activity("CS_2_3", fields());
        double ria = ScoringReferenceYearContext.with(2026, () -> 12 * muz.activity("RIA_2_2", fields())
                + 8 * muz.activity("RIA_2_3", fields("Rezultat", "Premiu"), "Concurs")
                + muz.activity("RIA_3_2", fields("Rol", "Funcție de conducere", "An_inceput", "2021"))
                + 3 * muz.activity("RIA_3_3", fields()));
        assertEquals(400.0, did, 1e-9);
        assertEquals(1700.0, cs, 1e-9);
        assertEquals(770.0, ria, 1e-9);
    }

    // ------------------------------------------------------------------ consistency

    @Test
    void formulasCompareOnlyAgainstOptionsTheActivitiesOffer() {
        muz.assertComparedOptionsExist();
    }

    @Test
    void theCommittedDescriptionsAreTheOnesTheSeedCarries() throws IOException {
        muz.assertDescribedBy("muzica-2026.json");
    }

    @Test
    void theRunExportsToTheFacultysGridEachScoringIndicatorOnItsOwnRow() {
        JsonNode report = muz.report();
        assertEquals("muzica-2026", report.get("reportTypeKey").asText());
        assertTrue(report.get("importEnabled").asBoolean(), "a filled grid can be verified against the run");
        TemplateBinding binding = new TemplateBindingLoader(new com.fasterxml.jackson.databind.ObjectMapper())
                .load("report-templates/muzica-2026/binding.json");
        Map<String, String> roleOfBlock = new java.util.HashMap<>();
        for (BindingRole role : binding.getRoles()) {
            for (BindingBlock block : role.getBlocks()) roleOfBlock.put(block.getActivityName(), role.getRoleKey());
        }
        Set<String> fedBlocks = new java.util.TreeSet<>();
        JsonNode refs = report.get("indicators");
        for (int i = 0; i < refs.size(); i++) {
            String id = refs.get(i).get("$id").get("$oid").asText();
            String name = muz.reportIndicatorNames().get(i);
            String role = report.get("indicatorRolesByIndicatorId").get(id).asText();
            String block = report.get("blockByIndicatorId").get(id).asText();
            if (name.startsWith("Muz26_N_")) {
                assertEquals("__not_exported__", role, name + " only counts, it has no row");
                assertEquals("__not_exported__", block, name + " only counts, it has no row");
                continue;
            }
            String row = name.replace("Muz26_", "").replace("_decl", "");
            String expected = row.substring(0, row.indexOf('_')) + " " + row.substring(row.indexOf('_') + 1).replace('_', '.');
            assertEquals(expected, block, name);
            assertEquals(roleOfBlock.get(block), role, name + " sits in the table of its row");
            fedBlocks.add(block);
        }
        assertEquals(new java.util.TreeSet<>(roleOfBlock.keySet()), fedBlocks, "every row of the grid is fed by the run");
    }

    @Test
    void everyIndicatorIsInTheReport() {
        assertEquals(muz.indicatorNames(), Set.copyOf(muz.reportIndicatorNames()));
        assertEquals(35, muz.reportIndicatorNames().size());
    }

    private static ArtisticEvent event(String name, ArtisticEvent.Rank rank) {
        ArtisticEvent e = new ArtisticEvent();
        e.setName(name);
        e.setRank(rank);
        return e;
    }
}
