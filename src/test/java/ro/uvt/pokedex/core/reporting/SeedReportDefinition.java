package ro.uvt.pokedex.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.service.application.ScholardexProjectReadPort;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.PsihologiePublisherService;
import ro.uvt.pokedex.core.service.reporting.InternationalPublisherListService;
import ro.uvt.pokedex.core.service.reporting.InternationalPublisherSupport;
import ro.uvt.pokedex.core.service.reporting.PublisherCategoryService;
import ro.uvt.pokedex.core.service.reporting.PublisherCategorySupport;
import ro.uvt.pokedex.core.service.reporting.PublisherRules;
import ro.uvt.pokedex.core.service.reporting.WosMasterBookListService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * H143 — the international houses the tests use, standing in for the WoS Master Book List (a Mongo-backed list):
     * the category of a declared book comes from the real committed lists otherwise.
     */
    static final Set<String> MASTER_BOOK_LIST = Set.of("routledge", "springer", "palgrave macmillan", "sage");

    static {
        WosMasterBookListService masterBookList = mock(WosMasterBookListService.class);
        when(masterBookList.isRecognized(any())).thenAnswer(call -> {
            String name = call.getArgument(0);
            return name != null && MASTER_BOOK_LIST.contains(name.trim().toLowerCase(java.util.Locale.ROOT));
        });
        PublisherCategorySupport.register(new PublisherCategoryService(
                new PsihologiePublisherService(mock(ro.uvt.pokedex.core.repository.reporting.PsihologiePublisherRepository.class)),
                masterBookList));
        // the committed international lists; SENSE, a database collection, stays empty here
        InternationalPublisherSupport.register(new InternationalPublisherListService(
                mock(ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository.class)));
    }

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
        // H142: a standard without criterion codes ("Tabelul 1 — DID …") is found by a prefix only it has
        List<JsonNode> named = new ArrayList<>();
        for (JsonNode criterion : report.get("criteria")) {
            if (criterion.get("name").asText().startsWith(prefix)) {
                named.add(criterion);
            }
        }
        if (named.size() == 1) {
            return named.getFirst();
        }
        throw new AssertionError(named.isEmpty() ? "no criterion " + prefix : "more than one criterion " + prefix);
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
        // the 2026 rule flags, as committed (H143: they also pick the publisher lists of a declared book)
        indicator.setPsihologie2026(node.path("psihologie2026").asBoolean(false) ? Boolean.TRUE : null);
        indicator.setStiinteEducatiei2026(node.path("stiinteEducatiei2026").asBoolean(false) ? Boolean.TRUE : null);
        indicator.setSociologie2026(node.path("sociologie2026").asBoolean(false) ? Boolean.TRUE : null);
        indicator.setMuzica2026(node.path("muzica2026").asBoolean(false) ? Boolean.TRUE : null);
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

    /** A publication formula evaluated with exactly the variables given (for standards with their own surface). */
    double evalFormula(String shortName, Map<String, Object> variables) {
        FormulaContext.Builder builder = FormulaContext.builder();
        variables.forEach(builder::put);
        return EVALUATOR.eval(indicator(shortName).get("formula").asText(), builder.build());
    }

    double onScore(String shortName, double s) {
        return EVALUATOR.eval(indicator(shortName).get("formula").asText(),
                FormulaContext.builder().put("S", s).build());
    }

    /** Points for ONE declared activity, computed by the real activity scoring service. */
    double activity(String shortName, Map<String, String> entered) {
        return activity(shortName, entered, null);
    }

    /**
     * Points for ONE declared activity that names an event (H142: the artistic performance, whose visibility and
     * result are derived from the event and the fields).
     */
    double activity(String shortName, Map<String, String> entered, String eventName) {
        return activity(shortName, entered, eventName, null);
    }

    /**
     * Points for ONE declared book whose request for a publisher category a head decided (H143): the record asks for
     * {@code Incadrare_solicitata} and the decision is {@code status}.
     */
    double activityWithDecision(String shortName, Map<String, String> entered, PublisherClaim.Status status) {
        PublisherClaim claim = new PublisherClaim();
        claim.setStatus(status);
        claim.setRequested(entered.get(PublisherRules.FIELD_CLAIM));
        return activity(shortName, entered, (String) null, claim, DEFAULT_DATE);
    }

    /** Points for ONE declared activity dated {@code date} (the other helpers date it {@value #DEFAULT_DATE}). */
    double activityOn(String date, String shortName, Map<String, String> entered) {
        return activity(shortName, entered, (String) null, null, date);
    }

    private static final String DEFAULT_DATE = "2024-01-01";

    private double activity(String shortName, Map<String, String> entered, String eventName, PublisherClaim claim) {
        return activity(shortName, entered, eventName, claim, DEFAULT_DATE);
    }

    /**
     * H144 — points for ONE declared activity that names entities of the registries (a conference, an organisation, an
     * award, an artistic event, a university, a journal by ISSN); the formula reads what the registries say of them.
     */
    double activityNaming(String shortName, Map<String, String> entered, Map<Activity.ReferenceField, String> named) {
        return activity(shortName, entered, named, null, DEFAULT_DATE);
    }

    private double activity(String shortName, Map<String, String> entered, String eventName, PublisherClaim claim,
                            String date) {
        return activity(shortName, entered, eventName == null ? Map.of() : Map.of(Activity.ReferenceField.EVENT_NAME, eventName),
                claim, date);
    }

    private double activity(String shortName, Map<String, String> entered, Map<Activity.ReferenceField, String> named,
                            PublisherClaim claim, String date) {
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
        List<Activity.ReferenceField> references = new ArrayList<>();
        definition.path("referenceFields").forEach(ref -> references.add(Activity.ReferenceField.valueOf(ref.asText())));
        activity.setReferenceFields(references);
        for (String key : entered.keySet()) {
            assertTrue(known.contains(key), prefix + shortName + ": the activity has no field " + key);
        }

        ActivityInstance instance = new ActivityInstance();
        instance.setId("declared-1");
        instance.setDate(date);
        instance.setActivity(activity);
        instance.setFields(new HashMap<>(entered));
        instance.setPublisherClaim(claim);
        instance.setReferenceFields(new HashMap<>(named));
        for (Activity.ReferenceField field : named.keySet()) {
            assertTrue(references.contains(field), prefix + shortName + ": the activity names no " + field);
        }

        Map<String, Score> scores =
                activityReportingService.calculateActivityScores(List.of(instance), asIndicator(shortName));
        return scores.get("total").getAuthorScore();
    }

    // ------------------------------------------------------------------ H144: the registries and the app's lists

    private static final List<ro.uvt.pokedex.core.model.registry.RegistryEntry> RANKED = new ArrayList<>();
    private static final Map<String, ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport.JournalFacts> JOURNALS = new HashMap<>();
    private static final Map<String, Integer> URAP = new HashMap<>();
    private static final Map<String, Integer> WORLD = new HashMap<>();
    private static final Map<String, String> COUNTRIES = new HashMap<>();

    /** An entry the experts ranked; every name not ranked this way waits for them. */
    static void rank(ro.uvt.pokedex.core.model.registry.RegistryKind kind, String name, String level, String category,
                     String country) {
        ro.uvt.pokedex.core.model.registry.RegistryEntry entry = ro.uvt.pokedex.core.model.registry.RegistryEntry.of(kind);
        entry.setName(name);
        entry.setStatus(ro.uvt.pokedex.core.model.registry.RegistryStatus.CONFIRMED);
        entry.setLevel(level);
        entry.setCategory(category);
        entry.setCountry(country);
        RANKED.add(entry);
        ro.uvt.pokedex.core.service.reporting.RegistrySupport.register(RANKED);
    }

    /** What the app's lists know of a journal (by its canonical ISSN). */
    static void journal(String issn, boolean fee, boolean wos, boolean wosCore, boolean scopus, int databases, Double impactFactor) {
        JOURNALS.put(issn, new ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport.JournalFacts(
                fee, wos, wosCore, scopus, databases, impactFactor));
        registerLookups();
    }

    /** A university of the rankings: its URAP position (null when URAP does not rank it) and country. */
    static void university(String name, Integer urapRank, String country) {
        university(name, urapRank, null, country);
    }

    /** A university with its URAP position and its best QS, THE or Shanghai position (null when unranked). */
    static void university(String name, Integer urapRank, Integer worldRank, String country) {
        if (urapRank != null) URAP.put(name, urapRank);
        if (worldRank != null) WORLD.put(name, worldRank);
        COUNTRIES.put(name, country);
        registerLookups();
    }

    static void resetRegistries() {
        RANKED.clear();
        JOURNALS.clear();
        URAP.clear();
        WORLD.clear();
        COUNTRIES.clear();
        ro.uvt.pokedex.core.service.reporting.RegistrySupport.reset();
        ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport.reset();
    }

    private static void registerLookups() {
        ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport.register(
                new ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport.Lookups() {
                    @Override
                    public java.util.Optional<Integer> urapRank(String university, int year) {
                        return java.util.Optional.ofNullable(URAP.get(university));
                    }

                    @Override
                    public java.util.Optional<Integer> worldRank(String university, int year) {
                        return java.util.Optional.ofNullable(WORLD.get(university));
                    }

                    @Override
                    public java.util.Optional<String> universityCountry(String university) {
                        return java.util.Optional.ofNullable(COUNTRIES.get(university));
                    }

                    @Override
                    public java.util.Optional<ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport.JournalFacts> journal(
                            String issn, int year) {
                        return java.util.Optional.ofNullable(JOURNALS.get(issn));
                    }
                });
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
            // values the engine derives itself (H142: the result of an artistic performance; H143: the category of
            // a declared book's publisher)
            options.addAll(Set.of(ro.uvt.pokedex.core.service.reporting.ArtisticPerformanceSupport.PARTICIPATION,
                    ro.uvt.pokedex.core.service.reporting.ArtisticPerformanceSupport.NOMINATION,
                    ro.uvt.pokedex.core.service.reporting.ArtisticPerformanceSupport.PRIZE));
            for (PublisherRules rules : PublisherRules.values()) {
                options.addAll(rules.categories());
            }
            // H144: the levels of the registries experts rank, which the engine derives from the named entity
            for (ro.uvt.pokedex.core.model.registry.RegistryKind kind : ro.uvt.pokedex.core.model.registry.RegistryKind.values()) {
                options.addAll(kind.levels());
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
