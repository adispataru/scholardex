package ro.uvt.pokedex.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaContext;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaVariableContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the committed "FV Psihologie 2026" definition (seed/precious-config) to OM 3.019/2025, COMISIA 28,
 * domeniul Psihologie: the thresholds of the three ranks, which indicators feed which criterion, and what
 * every formula is worth. The definition is data, so nothing else in the suite would notice a typo in a
 * threshold or a role label that no longer matches its activity's options.
 */
class Psihologie2026ReportDefinitionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SEED = Path.of("seed", "precious-config");
    private static final FormulaEvaluator EVALUATOR = new FormulaEvaluator();

    private static JsonNode report;
    private static final Map<String, JsonNode> indicatorsById = new HashMap<>();
    private static final Map<String, JsonNode> indicatorsByName = new LinkedHashMap<>();
    private static final Map<String, JsonNode> activitiesById = new HashMap<>();
    private static final List<String> reportIndicatorNames = new ArrayList<>();

    @BeforeAll
    static void load() throws Exception {
        for (JsonNode indicator : JSON.readTree(Files.readString(SEED.resolve("indicators.json")))) {
            indicatorsById.put(indicator.get("_id").get("$oid").asText(), indicator);
            if (indicator.get("name").asText().startsWith("Psiho26_")) {
                indicatorsByName.put(indicator.get("name").asText(), indicator);
            }
        }
        for (JsonNode activity : JSON.readTree(Files.readString(SEED.resolve("activities.json")))) {
            activitiesById.put(activity.get("_id").get("$oid").asText(), activity);
        }
        for (JsonNode candidate : JSON.readTree(Files.readString(SEED.resolve("individualReports.json")))) {
            if ("FV Psihologie 2026".equals(candidate.get("title").asText())) {
                report = candidate;
            }
        }
        assertNotNull(report, "FV Psihologie 2026 is missing from the seed");
        for (JsonNode ref : report.get("indicators")) {
            reportIndicatorNames.add(indicatorsById.get(ref.get("$id").get("$oid").asText()).get("name").asText());
        }
    }

    // ------------------------------------------------------------------ criteria and thresholds

    private static JsonNode criterion(String prefix) {
        for (JsonNode criterion : report.get("criteria")) {
            if (criterion.get("name").asText().startsWith(prefix + ":")) {
                return criterion;
            }
        }
        throw new AssertionError("no criterion " + prefix);
    }

    private static Double threshold(String criterionPrefix, String position) {
        for (JsonNode threshold : criterion(criterionPrefix).get("thresholds")) {
            if (position.equals(threshold.get("position").asText())) {
                return threshold.get("value").asDouble();
            }
        }
        return null;
    }

    private static Set<String> members(String criterionPrefix) {
        Set<String> names = new HashSet<>();
        for (JsonNode index : criterion(criterionPrefix).get("indicatorIndices")) {
            names.add(reportIndicatorNames.get(index.asInt()));
        }
        return names;
    }

    private static void assertThresholds(String criterion, Double conf, Double prof, Double habil) {
        assertEquals(conf, threshold(criterion, "CONF_UNIV"), criterion + " conferențiar");
        assertEquals(prof, threshold(criterion, "PROF_UNIV"), criterion + " profesor");
        assertEquals(habil, threshold(criterion, "HABIL"), criterion + " abilitare");
    }

    @Test
    void thresholdsAreTheOnesOfTheStandardForTheThreeRanks() {
        assertThresholds("C1", null, 15.0, 15.0);
        assertThresholds("C2", 12.0, 30.0, 30.0);
        assertThresholds("C3", 60.0, 100.0, 90.0);
        assertThresholds("C4", 10.0, 38.0, 38.0);
        assertThresholds("C5", 9.0, 64.0, 64.0);
        assertThresholds("C6", 24.0, 120.0, 103.0);
        assertThresholds("C7", null, 27.0, 27.0);
        assertThresholds("C8", 1.0, 30.0, 27.0);
        assertThresholds("CTOTAL", 85.0, 250.0, 220.0);
        assertEquals(9, report.get("criteria").size());
    }

    @Test
    void theStandardSetsNoThresholdsBelowConferentiar() {
        for (JsonNode criterion : report.get("criteria")) {
            for (JsonNode threshold : criterion.get("thresholds")) {
                String position = threshold.get("position").asText();
                assertTrue(Set.of("CONF_UNIV", "PROF_UNIV", "HABIL").contains(position),
                        criterion.get("name").asText() + " carries a threshold for " + position);
            }
        }
    }

    @Test
    void criteriaSumTheIndicatorsTheStandardNames() {
        assertEquals(Set.of("Psiho26_I1A", "Psiho26_I1A_bonus"), members("C1"));
        assertEquals(Set.of("Psiho26_I1A", "Psiho26_I1A_bonus", "Psiho26_I1B", "Psiho26_I1B_bonus"), members("C2"));
        assertEquals(Set.of("Psiho26_I11"), members("C4"));
        assertEquals(Set.of("Psiho26_I13"), members("C5"));
        assertEquals(Set.of("Psiho26_I24"), members("C7"));

        Set<String> a1 = members("C3");
        Set<String> a2 = members("C6");
        Set<String> a3 = members("C8");
        assertEquals(12, a1.size());
        assertEquals(7, a2.size());
        assertEquals(11, a3.size());
        assertTrue(a1.containsAll(members("C2")));
        assertTrue(a2.containsAll(List.of("Psiho26_I11", "Psiho26_I12", "Psiho26_I13", "Psiho26_I14")));
        assertTrue(a3.contains("Psiho26_I24"));

        // The three areas partition the report, and the total is exactly their union.
        Set<String> union = new HashSet<>(a1);
        union.addAll(a2);
        union.addAll(a3);
        assertEquals(a1.size() + a2.size() + a3.size(), union.size(), "an indicator sits in two areas");
        assertEquals(union, members("CTOTAL"));
        assertEquals(new HashSet<>(reportIndicatorNames), union);
        assertEquals(indicatorsByName.keySet(), union, "a Psiho26 indicator is not part of the report");
    }

    @Test
    void perspectivesGroupTheCriteriaByArea() {
        JsonNode perspectives = report.get("perspectives");
        assertEquals(4, perspectives.size());
        assertEquals(List.of(0, 1, 2), criteriaOf(perspectives.get(0)));
        assertEquals(List.of(3, 4, 5), criteriaOf(perspectives.get(1)));
        assertEquals(List.of(6, 7), criteriaOf(perspectives.get(2)));
        assertEquals(List.of(8), criteriaOf(perspectives.get(3)));
    }

    private static List<Integer> criteriaOf(JsonNode perspective) {
        List<Integer> indices = new ArrayList<>();
        perspective.get("composition").get("all").forEach(leaf -> indices.add(leaf.get("criterion").asInt()));
        return indices;
    }

    // ------------------------------------------------------------------ indicator kinds

    private static JsonNode indicator(String name) {
        JsonNode indicator = indicatorsByName.get(name);
        assertNotNull(indicator, name + " is missing from the seed");
        return indicator;
    }

    private static String kindField(String name, String field) {
        JsonNode value = indicator(name).get("kind").get(field);
        return value == null ? null : value.asText();
    }

    @Test
    void principalAndCoauthorIndicatorsUseComplementaryRoles() {
        for (String principal : List.of("Psiho26_I1A", "Psiho26_I1B", "Psiho26_I2")) {
            assertEquals("FIRST_OR_CORRESPONDING", kindField(principal, "role"), principal);
        }
        for (String coauthor : List.of("Psiho26_I5", "Psiho26_I6")) {
            assertEquals("NOT_FIRST_NOR_CORRESPONDING", kindField(coauthor, "role"), coauthor);
        }
        // Books and chapters are scored "în calitate de autor / co-autor": no role split for Psychology.
        for (String book : List.of("Psiho26_I3A", "Psiho26_I3B", "Psiho26_I4A", "Psiho26_I4B")) {
            assertEquals("ALL", kindField(book, "role"), book);
        }
    }

    @Test
    void theRulesThatChangedIn2026AreSwitchedOnExactlyWhereTheyApply() {
        Set<String> flagged = new HashSet<>();
        indicatorsByName.forEach((name, node) -> {
            if (node.path("psihologie2026").asBoolean(false)) {
                flagged.add(name);
            }
        });
        // Every indicator that reads journals, publishers or Web of Science venues. The strict journal
        // indicators are in: without the flag they would skip the ESCI edition of the domain's categories.
        assertEquals(Set.of("Psiho26_I1A", "Psiho26_I1B", "Psiho26_I5", "Psiho26_I2", "Psiho26_I6",
                "Psiho26_I3A", "Psiho26_I3B", "Psiho26_I4A", "Psiho26_I4B", "Psiho26_I13"), flagged);
        assertEquals("PSYCH_BDI_JOURNAL", kindField("Psiho26_I2", "strategy"));
        assertEquals("PSYCH_BOOK", kindField("Psiho26_I3A", "strategy"));
    }

    @Test
    void citationsAndHirschIndexReadWebOfScience() {
        assertEquals("WOS_INDEXED", kindField("Psiho26_I11", "strategy"));
        assertEquals("CANDIDATE_ONLY", kindField("Psiho26_I11", "policy"));
        assertEquals("WOS_VENUE", kindField("Psiho26_I13", "source"));
        assertEquals("false", kindField("Psiho26_I13", "excludeSelf"));
    }

    @Test
    void cappedIndicatorsCarryTheCapsOfTheStandard() {
        assertEquals(10, indicator("Psiho26_I27_4").get("maxPoints").asInt());
        assertEquals(2, indicator("Psiho26_I27_5").get("maxPoints").asInt());
        long capped = indicatorsByName.values().stream().filter(node -> node.hasNonNull("maxPoints")).count();
        assertEquals(2, capped);
    }

    @Test
    void publicationAndCitationFormulasOnlyUseVariablesTheEngineBinds() {
        for (String name : List.of("Psiho26_I1A", "Psiho26_I1B", "Psiho26_I2", "Psiho26_I3A", "Psiho26_I3B",
                "Psiho26_I4A", "Psiho26_I4B", "Psiho26_I5", "Psiho26_I6", "Psiho26_I11", "Psiho26_I13")) {
            JsonNode node = indicator(name);
            JsonNode kind = node.get("kind");
            Indicator indicator = new Indicator();
            indicator.setName(name);
            indicator.setFormula(node.get("formula").asText());
            String type = kind.get("_class").asText();
            indicator.setKind(switch (type.substring(type.indexOf('$') + 1)) {
                case "Publications" -> IndicatorKind.of(
                        new IndicatorKind.Publications(
                                ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.valueOf(kind.get("role").asText()),
                                ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy.valueOf(
                                        kind.get("strategy").asText())).toLegacy().outputTypeName(),
                        kind.get("strategy").asText());
                case "Citations" -> IndicatorKind.of("CITATIONS_EXCLUDE_SELF", kind.get("strategy").asText());
                case "HIndex" -> IndicatorKind.of("HINDEX_WOS", "HIRSCH");
                default -> throw new AssertionError(type);
            });
            assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(indicator), name);
        }
    }

    // ------------------------------------------------------------------ publication formulas

    private static double publication(String name, double s, String quarter, boolean feeJournal, int authors,
                                      String category, String docType) {
        return EVALUATOR.eval(indicator(name).get("formula").asText(), FormulaContext.builder()
                .put("S", s).put("Q", quarter).put("feeJournal", feeJournal).put("N", authors)
                .put("category", category).put("docType", docType).build());
    }

    @Test
    void principalAuthorArticlesSplitByPublicationFee() {
        // IF 2.0 >= p: 3 + 3 × IF = 9, counted at I1A for a non-fee journal and at I1B for a fee journal.
        assertEquals(9.0, publication("Psiho26_I1A", 2.0, "Q3", false, 4, "", "ar"), 1e-9);
        assertEquals(0.0, publication("Psiho26_I1B", 2.0, "Q3", false, 4, "", "ar"), 1e-9);
        assertEquals(0.0, publication("Psiho26_I1A", 2.0, "Q3", true, 4, "", "ar"), 1e-9);
        assertEquals(9.0, publication("Psiho26_I1B", 2.0, "Q3", true, 4, "", "ar"), 1e-9);
        // The Psychology exception: below p but above the category median (Q1/Q2) still counts.
        assertEquals(5.4, publication("Psiho26_I1A", 0.8, "Q2", false, 1, "", "ar"), 1e-9);
        assertEquals(0.0, publication("Psiho26_I1A", 0.8, "Q3", false, 1, "", "ar"), 1e-9);
        // The threshold itself qualifies ("mai mare sau egal cu p").
        assertEquals(6.0, publication("Psiho26_I1A", 1.0, "Q4", false, 1, "", "ar"), 1e-9);
    }

    @Test
    void coauthorArticlesDivideOnlyTheImpactFactorPart() {
        // I5 = 3 + (3 × IF) / n — not (3 + 3 × IF) / n.
        assertEquals(3 + 6.0 / 4, publication("Psiho26_I5", 2.0, "Q3", false, 4, "", "ar"), 1e-9);
        // I6 = (3 + IF) / n; the scorer hands 3 + IF over as S.
        assertEquals(3.5 / 5, publication("Psiho26_I6", 3.5, null, false, 5, "WOS", "ar"), 1e-9);
        assertEquals(3.5, publication("Psiho26_I2", 3.5, null, false, 5, "WOS", "ar"), 1e-9);
    }

    @Test
    void booksAndChaptersUseTheNewBasesAndNeverDivideByAuthors() {
        assertEquals(48.0, publication("Psiho26_I3A", 3.0, null, false, 6, "A1", "bk"), 1e-9);
        assertEquals(16.0, publication("Psiho26_I3A", 1.0, null, false, 6, "A2", "bk"), 1e-9);
        assertEquals(8.0, publication("Psiho26_I3B", 0.5, null, false, 6, "B", "bk"), 1e-9);
        assertEquals(12.0, publication("Psiho26_I4A", 3.0, null, false, 6, "A1", "ch"), 1e-9);
        assertEquals(4.0, publication("Psiho26_I4A", 1.0, null, false, 6, "A2", "ch"), 1e-9);
        assertEquals(2.0, publication("Psiho26_I4B", 0.5, null, false, 6, "B", "ch"), 1e-9);
        // Each item lands on exactly one of the four indicators.
        assertEquals(0.0, publication("Psiho26_I3A", 0.5, null, false, 1, "B", "bk"), 1e-9);
        assertEquals(0.0, publication("Psiho26_I3B", 1.0, null, false, 1, "A2", "bk"), 1e-9);
        assertEquals(0.0, publication("Psiho26_I3A", 1.0, null, false, 1, "A2", "ch"), 1e-9);
        assertEquals(0.0, publication("Psiho26_I4A", 1.0, null, false, 1, "A2", "bk"), 1e-9);
    }

    @Test
    void citationsAndHirschIndexFormulas() {
        assertEquals(0.5, EVALUATOR.eval(indicator("Psiho26_I11").get("formula").asText(),
                FormulaContext.builder().put("S", 1.0).build()), 1e-9);
        assertEquals(64.0, EVALUATOR.eval(indicator("Psiho26_I13").get("formula").asText(),
                FormulaContext.builder().put("S", 8.0).build()), 1e-9);
    }

    // ------------------------------------------------------------------ activity formulas

    /** Evaluates an activity indicator the way ActivityReportingService does: S = 1 plus the entered fields. */
    private static double activity(String name, Map<String, Object> entered) {
        JsonNode node = indicator(name);
        JsonNode definition = activitiesById.get(node.get("activity").get("$id").get("$oid").asText());
        assertNotNull(definition, name + " is bound to an activity missing from the seed");
        Map<String, Object> variables = new HashMap<>();
        for (JsonNode field : definition.get("fields")) {
            String fieldName = field.get("name").asText();
            if (entered.containsKey(fieldName)) {
                Object value = entered.get(fieldName);
                if (value instanceof String text && field.has("allowedValues") && !field.get("allowedValues").isNull()) {
                    List<String> allowed = new ArrayList<>();
                    field.get("allowedValues").forEach(option -> allowed.add(option.asText()));
                    assertTrue(allowed.contains(text), name + ": '" + text + "' is not an option of " + fieldName);
                }
                variables.put(fieldName, value);
            } else {
                variables.put(fieldName, null);
            }
        }
        for (String key : entered.keySet()) {
            assertTrue(variables.containsKey(key), name + ": the activity has no field " + key);
        }
        variables.put("S", 1.0);
        return EVALUATOR.eval(node.get("formula").asText(), FormulaContext.builder().putAll(variables).build());
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void preregistrationBonusFollowsTheJournalType() {
        assertEquals(1.0, activity("Psiho26_I1A_bonus", fields("Tip_revista", "Fără taxă de publicare")), 1e-9);
        assertEquals(0.0, activity("Psiho26_I1B_bonus", fields("Tip_revista", "Fără taxă de publicare")), 1e-9);
        assertEquals(1.0, activity("Psiho26_I1B_bonus", fields("Tip_revista", "Cu taxă de publicare")), 1e-9);
        assertEquals(0.0, activity("Psiho26_I1A_bonus", fields("Tip_revista", "Cu taxă de publicare")), 1e-9);
    }

    @Test
    void registeredMaterialsDivideFiveByTheAuthors() {
        assertEquals(2.5, activity("Psiho26_I7", fields("N_autori", 2.0)), 1e-9);
        assertEquals(5.0, activity("Psiho26_I7", fields()), 1e-9);
    }

    @Test
    void googleScholarCountsOnlyWhatWebOfScienceDoesNot() {
        // 0.05 × (GS − WoS)
        assertEquals(0.05 * 300, activity("Psiho26_I12", fields("Citari_GS", 420.0, "Citari_WoS", 120.0)), 1e-9);
        assertEquals(0.05 * 420, activity("Psiho26_I12", fields("Citari_GS", 420.0)), 1e-9);
        assertEquals(0.0, activity("Psiho26_I12", fields("Citari_GS", 50.0, "Citari_WoS", 80.0)), 1e-9);
        assertEquals(0.0, activity("Psiho26_I12", fields()), 1e-9);
        // (hGS × hGS) × 0.25
        assertEquals(25.0, activity("Psiho26_I14", fields("h_GS", 10.0)), 1e-9);
        assertEquals(0.0, activity("Psiho26_I14", fields()), 1e-9);
    }

    @Test
    void editorialRolesMultiplyRoleAndJournalWeight() {
        String strong = "WoS cu IF de cel puțin 1";
        String other = "WoS cu IF sub 1 sau altă BDI recunoscută";
        assertEquals(36.0, activity("Psiho26_I15", fields("Rol", "Redactor-șef", "Indexare", strong)), 1e-9);
        assertEquals(24.0, activity("Psiho26_I15", fields("Rol", "Editor asociat", "Indexare", strong)), 1e-9);
        assertEquals(12.0, activity("Psiho26_I15", fields("Rol", "Membru", "Indexare", strong)), 1e-9);
        assertEquals(12.0, activity("Psiho26_I15", fields("Rol", "Redactor-șef", "Indexare", other)), 1e-9);
        assertEquals(4.0, activity("Psiho26_I15", fields("Rol", "Membru", "Indexare", other)), 1e-9);
    }

    @Test
    void keynotesAndCoordinatedBooks() {
        assertEquals(6.0, activity("Psiho26_I16", fields("Nivel", "Internațională")), 1e-9);
        assertEquals(2.0, activity("Psiho26_I16", fields("Nivel", "Națională")), 1e-9);
        assertEquals(12.0, activity("Psiho26_I17", fields("Categorie_editura", "A1", "N_coordonatori", 2.0)), 1e-9);
        assertEquals(8.0, activity("Psiho26_I17", fields("Categorie_editura", "A2")), 1e-9);
        assertEquals(1.0, activity("Psiho26_I17", fields("Categorie_editura", "B", "N_coordonatori", 4.0)), 1e-9);
    }

    @Test
    void grantLeadershipFollowsTheFourRolesOfTheStandard() {
        assertEquals(81.0, activity("Psiho26_I24", fields("Rol", "Director (proiect internațional)")), 1e-9);
        assertEquals(27.0, activity("Psiho26_I24", fields("Rol", "Director (proiect național)")), 1e-9);
        assertEquals(27.0, activity("Psiho26_I24", fields("Rol", "Coordonator local (proiect internațional)")), 1e-9);
        assertEquals(8.991, activity("Psiho26_I24", fields("Rol", "Coordonator local (proiect național)")), 1e-9);
        assertEquals(0.0, activity("Psiho26_I24", fields("Rol", "Membru")), 1e-9);
        // A team member scores at I26.1 instead, so a grant never counts twice.
        assertEquals(3.0, activity("Psiho26_I26_1", fields("Rol", "Membru")), 1e-9);
        assertEquals(0.0, activity("Psiho26_I26_1", fields("Rol", "Director (proiect național)")), 1e-9);
    }

    @Test
    void institutionalGrantsFellowshipsAndChairs() {
        assertEquals(4.5, activity("Psiho26_I25", fields("Tip", "Dezvoltare instituțională", "Rol", "Director")), 1e-9);
        assertEquals(4.5, activity("Psiho26_I25",
                fields("Tip", "Cercetare aplicativă", "Rol", "Coordonator partener")), 1e-9);
        assertEquals(0.0, activity("Psiho26_I25", fields("Tip", "Cercetare aplicativă", "Rol", "Membru")), 1e-9);
        assertEquals(4.5, activity("Psiho26_I25", fields("Tip", "Fellowship")), 1e-9);
        assertEquals(9.0, activity("Psiho26_I25", fields("Tip", "Chair")), 1e-9);
        assertEquals(1.5, activity("Psiho26_I26_2", fields("Tip", "Dezvoltare instituțională", "Rol", "Membru")), 1e-9);
        assertEquals(0.0, activity("Psiho26_I26_2", fields("Tip", "Dezvoltare instituțională", "Rol", "Director")), 1e-9);
        assertEquals(0.0, activity("Psiho26_I26_2", fields("Tip", "Fellowship", "Rol", "Membru")), 1e-9);
    }

    @Test
    void coordinationActivitiesScoreFlatPoints() {
        assertEquals(2.0, activity("Psiho26_I27_1", fields()), 1e-9);
        assertEquals(2.0, activity("Psiho26_I27_2", fields()), 1e-9);
        assertEquals(2.0, activity("Psiho26_I27_2_digital", fields()), 1e-9);
        assertEquals(0.5, activity("Psiho26_I27_3", fields()), 1e-9);
        assertEquals(0.5, activity("Psiho26_I27_4", fields("Rol", "Conducător științific")), 1e-9);
        assertEquals(1.0, activity("Psiho26_I27_5", fields()), 1e-9);
        assertEquals(1.0, activity("Psiho26_I27_6", fields()), 1e-9);
    }

    @Test
    void everyOptionComparedInAFormulaIsAnOptionOfItsActivity() {
        Pattern literal = Pattern.compile("'([^']*)'");
        for (Map.Entry<String, JsonNode> entry : indicatorsByName.entrySet()) {
            JsonNode activityRef = entry.getValue().get("activity");
            if (activityRef == null || activityRef.isNull()) {
                continue;
            }
            Set<String> options = new HashSet<>();
            for (JsonNode field : activitiesById.get(activityRef.get("$id").get("$oid").asText()).get("fields")) {
                if (field.has("allowedValues") && !field.get("allowedValues").isNull()) {
                    field.get("allowedValues").forEach(option -> options.add(option.asText()));
                }
            }
            Matcher matcher = literal.matcher(entry.getValue().get("formula").asText());
            while (matcher.find()) {
                assertTrue(options.contains(matcher.group(1)),
                        entry.getKey() + " compares against '" + matcher.group(1) + "', which its activity does not offer");
            }
        }
    }

    // ------------------------------------------------------------------ descriptions

    @Test
    void everyIndicatorHasADescriptionAndNoDescriptionIsOrphaned() throws Exception {
        JsonNode descriptions = JSON.readTree(
                Files.readString(Path.of("src/main/resources/indicator-descriptions/psihologie-2026.json")));
        Set<String> described = new HashSet<>();
        descriptions.fieldNames().forEachRemaining(described::add);
        described.remove("_comment");
        assertEquals(indicatorsByName.keySet(), described);
        indicatorsByName.forEach((name, node) -> {
            assertEquals(descriptions.get(name).asText(), node.path("description").asText(null),
                    name + ": the seed and the committed description differ");
            assertFalse(node.get("description").asText().isBlank());
        });
        assertNull(descriptions.get("Psiho_I1"), "the 2016 descriptions live in psihologie.json");
    }
}
