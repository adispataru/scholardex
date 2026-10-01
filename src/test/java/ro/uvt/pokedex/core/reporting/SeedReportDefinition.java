package ro.uvt.pokedex.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.service.application.ScholardexProjectReadPort;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScoringFactoryService;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaContext;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;

import java.io.IOException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A report definition as committed in {@code seed/precious-config}, read for the tests that pin it to its
 * standard. Activity formulas are evaluated by the real {@link ActivityReportingService}, so what a test
 * sees includes the variables the service derives (the budget in euro, the number of editions) and not
 * just the fields a researcher types.
 */
final class SeedReportDefinition {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SEED = Path.of("seed", "precious-config");
    private static final FormulaEvaluator EVALUATOR = new FormulaEvaluator();

    private final JsonNode report;
    private final String prefix;
    private final Map<String, JsonNode> indicatorsByName = new LinkedHashMap<>();
    private final Map<String, JsonNode> activitiesById = new HashMap<>();
    private final List<String> reportIndicatorNames = new ArrayList<>();
    private final ActivityReportingService activityReportingService = new ActivityReportingService(
            mock(ScoringFactoryService.class), EVALUATOR, mock(ScholardexProjectReadPort.class));

    SeedReportDefinition(String title, String indicatorPrefix) {
        this.prefix = indicatorPrefix;
        try {
            Map<String, JsonNode> indicatorsById = new HashMap<>();
            for (JsonNode indicator : JSON.readTree(Files.readString(SEED.resolve("indicators.json")))) {
                indicatorsById.put(indicator.get("_id").get("$oid").asText(), indicator);
                if (indicator.get("name").asText().startsWith(indicatorPrefix)) {
                    indicatorsByName.put(indicator.get("name").asText(), indicator);
                }
            }
            for (JsonNode activity : JSON.readTree(Files.readString(SEED.resolve("activities.json")))) {
                activitiesById.put(activity.get("_id").get("$oid").asText(), activity);
            }
            JsonNode found = null;
            for (JsonNode candidate : JSON.readTree(Files.readString(SEED.resolve("individualReports.json")))) {
                if (title.equals(candidate.get("title").asText())) {
                    found = candidate;
                }
            }
            assertNotNull(found, title + " is missing from the seed");
            report = found;
            for (JsonNode ref : report.get("indicators")) {
                reportIndicatorNames.add(
                        indicatorsById.get(ref.get("$id").get("$oid").asText()).get("name").asText());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The committed report with this exact title, or null when the seed has none (for superseded reports). */
    static JsonNode find(String title) {
        try {
            for (JsonNode candidate : JSON.readTree(Files.readString(SEED.resolve("individualReports.json")))) {
                if (title.equals(candidate.get("title").asText())) {
                    return candidate;
                }
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ report structure

    JsonNode report() {
        return report;
    }

    Set<String> indicatorNames() {
        return indicatorsByName.keySet();
    }

    List<String> reportIndicatorNames() {
        return reportIndicatorNames;
    }

    private JsonNode criterion(String prefix) {
        for (JsonNode criterion : report.get("criteria")) {
            if (criterion.get("name").asText().startsWith(prefix + ":")) {
                return criterion;
            }
        }
        throw new AssertionError("no criterion " + prefix);
    }

    Double threshold(String criterionPrefix, String position) {
        for (JsonNode threshold : criterion(criterionPrefix).get("thresholds")) {
            if (position.equals(threshold.get("position").asText())) {
                return threshold.get("value").asDouble();
            }
        }
        return null;
    }

    void assertThresholds(String criterion, Double conf, Double prof, Double habil) {
        assertEquals(conf, threshold(criterion, "CONF_UNIV"), criterion + " conferențiar");
        assertEquals(prof, threshold(criterion, "PROF_UNIV"), criterion + " profesor");
        assertEquals(habil, threshold(criterion, "HABIL"), criterion + " abilitare");
    }

    Set<String> members(String criterionPrefix) {
        Set<String> names = new HashSet<>();
        for (JsonNode index : criterion(criterionPrefix).get("indicatorIndices")) {
            names.add(reportIndicatorNames.get(index.asInt()));
        }
        return names;
    }

    /** Short member names, without the indicator prefix. */
    Set<String> shortMembers(String criterionPrefix) {
        Set<String> names = new HashSet<>();
        members(criterionPrefix).forEach(name -> names.add(name.substring(prefix.length())));
        return names;
    }

    /** Short names of the indicators a share criterion is a percentage OF; empty for a sum criterion. */
    Set<String> shareOf(String criterionPrefix) {
        Set<String> names = new HashSet<>();
        JsonNode whole = criterion(criterionPrefix).get("shareOfIndicatorIndices");
        if (whole != null) {
            whole.forEach(index -> names.add(reportIndicatorNames.get(index.asInt()).substring(prefix.length())));
        }
        return names;
    }

    /** Weight of a member inside a criterion; 1 when the criterion carries none for it. */
    double weight(String criterionPrefix, String shortName) {
        JsonNode weights = criterion(criterionPrefix).get("weights");
        int index = reportIndicatorNames.indexOf(prefix + shortName);
        assertTrue(index >= 0, prefix + shortName + " is not in the report");
        return weights == null || !weights.has(String.valueOf(index))
                ? 1.0 : weights.get(String.valueOf(index)).asDouble();
    }

    /** The WoS category keys of the domain an indicator is bound to. */
    Set<String> domainCategories(String shortName) {
        String domainId = indicator(shortName).get("domain").get("$id").asText();
        try {
            for (JsonNode domain : JSON.readTree(Files.readString(SEED.resolve("domains.json")))) {
                if (domainId.equals(domain.get("_id").asText())) {
                    Set<String> keys = new HashSet<>();
                    domain.get("wosCategories").forEach(key -> keys.add(key.asText()));
                    return keys;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError("domain " + domainId + " is missing from the seed");
    }

    List<Integer> criteriaOf(int perspective) {
        List<Integer> indices = new ArrayList<>();
        report.get("perspectives").get(perspective).get("composition").get("all")
                .forEach(leaf -> indices.add(leaf.get("criterion").asInt()));
        return indices;
    }

    // ------------------------------------------------------------------ indicators

    JsonNode indicator(String shortName) {
        JsonNode indicator = indicatorsByName.get(prefix + shortName);
        assertNotNull(indicator, prefix + shortName + " is missing from the seed");
        return indicator;
    }

    String kindField(String shortName, String field) {
        JsonNode value = indicator(shortName).get("kind").get(field);
        return value == null ? null : value.asText();
    }

    Set<String> flagged(String flag) {
        Set<String> names = new HashSet<>();
        indicatorsByName.forEach((name, node) -> {
            if (node.path(flag).asBoolean(false)) {
                names.add(name.substring(prefix.length()));
            }
        });
        return names;
    }

    /** The indicator as the engine sees it: kind, formula, flags, cap. Domain and activity are left out. */
    Indicator asIndicator(String shortName) {
        JsonNode node = indicator(shortName);
        JsonNode kind = node.get("kind");
        Indicator indicator = new Indicator();
        indicator.setId(node.get("_id").get("$oid").asText());
        indicator.setName(node.get("name").asText());
        indicator.setFormula(node.get("formula").asText());
        String type = kind.get("_class").asText();
        indicator.setKind(switch (type.substring(type.indexOf('$') + 1)) {
            case "Publications" -> new IndicatorKind.Publications(
                    AuthorRole.valueOf(kind.get("role").asText()),
                    ScoringStrategy.valueOf(kind.get("strategy").asText()));
            case "Citations" -> IndicatorKind.of("CITATIONS_EXCLUDE_SELF", kind.get("strategy").asText());
            case "HIndex" -> IndicatorKind.of("HINDEX_WOS", "HIRSCH");
            case "GenericActivity" -> new IndicatorKind.GenericActivity();
            default -> throw new AssertionError(type);
        });
        if (node.hasNonNull("maxPoints")) {
            indicator.setMaxPoints(node.get("maxPoints").asInt());
        }
        return indicator;
    }

    // ------------------------------------------------------------------ formulas

    /** A publication or citation formula with the variables the scoring engine binds. */
    double publication(String shortName, double s, String quartile, boolean feeJournal, int authors,
                       String category, String docType) {
        return EVALUATOR.eval(indicator(shortName).get("formula").asText(), FormulaContext.builder()
                .put("S", s).put("Q", quartile).put("feeJournal", feeJournal).put("N", authors)
                .put("category", category).put("docType", docType).build());
    }

    /** A publication formula of a standard that multiplies by the coefficient m ({@code Coef_m}). */
    double publication(String shortName, double s, int authors, String category, String docType,
                       double coefficient) {
        return EVALUATOR.eval(indicator(shortName).get("formula").asText(), FormulaContext.builder()
                .put("S", s).put("N", authors).put("category", category).put("docType", docType)
                .put("Coef_m", coefficient).build());
    }

    double onScore(String shortName, double s) {
        return EVALUATOR.eval(indicator(shortName).get("formula").asText(),
                FormulaContext.builder().put("S", s).build());
    }

    /** Points for ONE declared activity, computed by the real activity scoring service. */
    double activity(String shortName, Map<String, String> entered) {
        JsonNode node = indicator(shortName);
        JsonNode definition = activitiesById.get(node.get("activity").get("$id").get("$oid").asText());
        assertNotNull(definition, prefix + shortName + " is bound to an activity missing from the seed");

        Activity activity = new Activity();
        activity.setId(definition.get("_id").get("$oid").asText());
        activity.setName(definition.get("name").asText());
        List<Activity.Field> fields = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (JsonNode field : definition.get("fields")) {
            Activity.Field f = new Activity.Field();
            f.setName(field.get("name").asText());
            f.setNumber(field.path("number").asBoolean(false));
            if (field.has("allowedValues") && !field.get("allowedValues").isNull()) {
                List<String> allowed = new ArrayList<>();
                field.get("allowedValues").forEach(option -> allowed.add(option.asText()));
                f.setAllowedValues(allowed);
                String value = entered.get(f.getName());
                assertTrue(value == null || allowed.contains(value),
                        prefix + shortName + ": '" + value + "' is not an option of " + f.getName());
            }
            fields.add(f);
            known.add(f.getName());
        }
        activity.setFields(fields);
        for (String key : entered.keySet()) {
            assertTrue(known.contains(key), prefix + shortName + ": the activity has no field " + key);
        }

        ActivityInstance instance = new ActivityInstance();
        instance.setId("declared-1");
        instance.setDate("2024-01-01");
        instance.setActivity(activity);
        instance.setFields(new HashMap<>(entered));
        instance.setReferenceFields(new HashMap<>());

        Map<String, Score> scores =
                activityReportingService.calculateActivityScores(List.of(instance), asIndicator(shortName));
        return scores.get("total").getAuthorScore();
    }

    static Map<String, String> fields(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** Every option a formula compares against must be one the bound activity offers. */
    void assertComparedOptionsExist() {
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
                assertTrue(options.contains(matcher.group(1)), entry.getKey() + " compares against '"
                        + matcher.group(1) + "', which its activity does not offer");
            }
        }
    }

    /** The committed descriptions file describes exactly these indicators, with the text the seed carries. */
    void assertDescribedBy(String descriptionsFile) throws IOException {
        JsonNode descriptions = JSON.readTree(
                Files.readString(Path.of("src/main/resources/indicator-descriptions", descriptionsFile)));
        Set<String> described = new HashSet<>();
        descriptions.fieldNames().forEachRemaining(described::add);
        described.remove("_comment");
        assertEquals(indicatorsByName.keySet(), described);
        indicatorsByName.forEach((name, node) -> assertEquals(descriptions.get(name).asText(),
                node.path("description").asText(null), name + ": the seed and the committed description differ"));
    }

    String activityName(String shortName) {
        JsonNode node = indicator(shortName);
        return activitiesById.get(node.get("activity").get("$id").get("$oid").asText()).get("name").asText();
    }
}
