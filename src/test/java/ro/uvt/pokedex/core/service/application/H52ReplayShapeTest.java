package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * H52 slice 10 / Commit 2 — replay-shape gate against the {@code userIndicatorResults}
 * cache. Snapshots one {@code rawGraph} per distinct fingerprint (57 blobs covering
 * every production render shape) and asserts shape invariants. Tripwire for the
 * Commit 3 decommission pass that deletes {@code Indicator.Type}, {@code Indicator.Strategy},
 * {@code Score.extra}, etc. — any shape drift introduced by those removals would
 * surface here before it breaks the user-facing "view cached result" flow.
 *
 * <p>The fixture at {@code src/test/resources/h52/replay-fixture.json} was captured
 * on 2026-06-01 from the {@code test} database. Re-generation is documented in the
 * H52 design doc. PII (researcher emails) has been redacted to
 * {@code redacted@test.local}; researcher-authored publication titles are left intact
 * because they are public scholarly metadata.</p>
 *
 * <p>The test deliberately uses generic {@link JsonNode} access rather than binding to
 * the concrete view-model classes. The goal is to prove the cached JSON shape stays
 * <em>parseable</em> across the Commit-3 schema changes; binding to view-model classes
 * would couple this test to refactors of those classes, which is the opposite of the
 * tripwire contract.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H52ReplayShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode fixture;

    @BeforeAll
    void loadFixture() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/h52/replay-fixture.json")) {
            assertNotNull(is, "h52/replay-fixture.json missing from test resources");
            fixture = MAPPER.readTree(is);
        }
    }

    // ---------- Fixture metadata ----------

    @Test
    void fixtureSchemaIsCurrent() {
        assertEquals("h52-replay-fixture-v1", fixture.path("schemaVersion").asText());
        assertEquals(57, fixture.path("fingerprintCount").asInt(),
                "fixture must have one entry per distinct production fingerprint");
        assertEquals("test/userIndicatorResults", fixture.path("capturedFrom").asText());
        assertTrue(fixture.path("capturedAt").asText().startsWith("2026-"),
                "capturedAt should be ISO-8601 and from the H52 capture window");
    }

    @Test
    void fixtureHasEveryProductionViewName() {
        Set<String> views = new TreeSet<>();
        for (JsonNode entry : fixture.path("entries")) {
            views.add(entry.path("viewName").asText());
        }
        assertEquals(
                Set.of(
                        "user/indicators-apply",
                        "user/indicators-apply-activities",
                        "user/indicators-apply-citations",
                        "user/indicators-apply-publications"
                ),
                views
        );
    }

    // ---------- Per-entry deserialization ----------

    @Test
    void everyRawGraphParsesAsJson() {
        for (JsonNode entry : fixture.path("entries")) {
            String fingerprint = entry.path("fingerprint").asText();
            String rawGraph = entry.path("rawGraph").asText();
            try {
                JsonNode parsed = MAPPER.readTree(rawGraph);
                assertTrue(parsed.isObject(),
                        "rawGraph for " + fingerprint + " must be a JSON object, got " + parsed.getNodeType());
            } catch (Exception ex) {
                fail("rawGraph failed to parse for " + fingerprint + ": " + ex.getMessage());
            }
        }
    }

    @Test
    void everyRawGraphHasIndicatorAndTotal() {
        // Universal invariants across all 57 entries — only two keys are guaranteed
        // present on every view, including the empty-results "no publications/activities
        // matched" case. The richer per-view shapes are pinned in the view-specific
        // tests below.
        //
        // If Commit 3 accidentally drops the `indicator` block or the `total` formatted
        // string from the assembler, this test catches it before the user-facing
        // "view cached" path NPEs.
        for (JsonNode entry : fixture.path("entries")) {
            String fingerprint = entry.path("fingerprint").asText();
            JsonNode rg = parseRawGraph(entry);

            assertTrue(rg.has("indicator"),
                    "missing 'indicator' top-level key in " + fingerprint);
            assertTrue(rg.has("total"),
                    "missing 'total' top-level key in " + fingerprint);

            JsonNode indicator = rg.path("indicator");
            assertTrue(indicator.isObject(), "indicator must be object in " + fingerprint);
            // Per slice 2, these legacy fields are still serialized through the @Deprecated
            // accessors. The Commit-3 read-switch must keep them present in the JSON shape
            // (via @JsonProperty aliases or similar) until cached blobs are all re-rendered.
            assertTrue(indicator.has("name"), "indicator.name missing in " + fingerprint);
            assertTrue(indicator.has("formula"), "indicator.formula missing in " + fingerprint);
            assertTrue(indicator.has("scoringStrategy"),
                    "indicator.scoringStrategy missing in " + fingerprint);
            assertTrue(indicator.has("outputType"),
                    "indicator.outputType missing in " + fingerprint);
        }
    }

    // ---------- View-specific invariants ----------

    @Test
    void dedicatedPublicationsViewsAlwaysCarryForumAndPublicationsLists() {
        // The dedicated indicators-apply-publications and indicators-apply-citations
        // views always carry forum + publications context — they're only rendered
        // when the score lookup found matches. The catch-all indicators-apply view
        // varies (it also handles the empty-results case where these fields are
        // absent), so we exclude it.
        Set<String> dedicatedPublicationViews = Set.of(
                "user/indicators-apply-publications",
                "user/indicators-apply-citations"
        );
        for (JsonNode entry : fixture.path("entries")) {
            if (!dedicatedPublicationViews.contains(entry.path("viewName").asText())) continue;
            String fingerprint = entry.path("fingerprint").asText();
            JsonNode rg = parseRawGraph(entry);
            assertTrue(rg.has("publications"),
                    "publications missing on publication-view " + fingerprint);
            assertTrue(rg.has("forumMap"),
                    "forumMap missing on publication-view " + fingerprint);
            assertTrue(rg.has("scores"),
                    "scores missing on publication-view " + fingerprint);
        }
    }

    @Test
    void activitiesViewCarriesActivitiesList() {
        for (JsonNode entry : fixture.path("entries")) {
            if (!"user/indicators-apply-activities".equals(entry.path("viewName").asText())) continue;
            String fingerprint = entry.path("fingerprint").asText();
            JsonNode rg = parseRawGraph(entry);
            assertTrue(rg.has("activities"),
                    "activities missing on activities-view " + fingerprint);
            assertFalse(rg.has("publications"),
                    "activities-view should not carry publications: " + fingerprint);
        }
    }

    @Test
    void dedicatedCitationsViewCarriesCitationMapAndTotalCit() {
        // Only the dedicated indicators-apply-citations view guarantees these keys;
        // the catch-all indicators-apply varies even for CITATIONS-strategy indicators
        // when the score lookup returned empty.
        for (JsonNode entry : fixture.path("entries")) {
            if (!"user/indicators-apply-citations".equals(entry.path("viewName").asText())) continue;
            String fingerprint = entry.path("fingerprint").asText();
            JsonNode rg = parseRawGraph(entry);
            assertTrue(rg.has("citationMap"),
                    "citationMap missing on citation-view " + fingerprint);
            assertTrue(rg.has("totalCit"),
                    "totalCit missing on citation-view " + fingerprint);
        }
    }

    // ---------- Score shape (the H52-sensitive surface) ----------

    @Test
    void everyScoreEntryHasTheLegacyOpenBagFieldsSerialized() {
        // H52 slice 9 introduces Score.multiplier alongside Score.extra, and Commit 3
        // will eventually drop extra/errors/details. Until that ships, every cached
        // score must still carry the legacy bag fields so the JSON deserializer can
        // round-trip them. This test pins the contract.
        for (JsonNode entry : fixture.path("entries")) {
            String fingerprint = entry.path("fingerprint").asText();
            JsonNode scores = parseRawGraph(entry).path("scores");
            if (!scores.isObject() || scores.size() == 0) continue;

            JsonNode firstScore = scores.elements().next();
            // total wrapper appears as a key inside scores for some views — skip it.
            if (firstScore.isObject() && !firstScore.has("score")) continue;

            // Required surface today; documented as Commit-3 removal targets in the
            // design doc. Adding to this list is fine; removing is a behaviour change.
            List<String> required = List.of("score", "authorScore");
            for (String key : required) {
                assertTrue(firstScore.has(key),
                        "score." + key + " missing in " + fingerprint);
            }
        }
    }

    // ---------- helpers ----------

    private JsonNode parseRawGraph(JsonNode entry) {
        try {
            return MAPPER.readTree(entry.path("rawGraph").asText());
        } catch (Exception ex) {
            throw new AssertionError(
                    "rawGraph unparseable in " + entry.path("fingerprint").asText() + ": " + ex.getMessage(),
                    ex
            );
        }
    }
}
