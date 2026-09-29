package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopusLicensedFieldsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aScopusPayloadLosesTheFieldsThatAreNotKept() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eid", "2-s2.0-1");
        payload.put("title", "Paper");
        payload.put("author_names", "A;B");
        payload.put("citedby_count", 3);
        payload.put("pii", "S1");
        payload.put("description", "An abstract");
        payload.put("authkeywords", "k1 | k2");
        payload.put("fund_acr", "PNRR");
        payload.put("fund_no", "123");
        payload.put("fund_sponsor", "UEFISCDI");
        payload.put("openaccess", 1);
        payload.put("freetoread", "all");
        payload.put("freetoreadLabel", "Open");
        payload.put("correspondingAuthors", "A");
        payload.put("matched_reference", "A, B: Paper. Journal (2020)");

        JsonNode stripped = mapper.valueToTree(
                ScopusLicensedFields.strip("SCOPUS_PYTHON_AUTHOR_WORKS", payload, mapper));

        assertEquals(List.of("eid", "title", "author_names", "citedby_count", "pii"),
                List.copyOf(mapper.convertValue(stripped, LinkedHashMap.class).keySet()));
        // the caller's object is not modified
        assertTrue(payload.containsKey("description"));
    }

    @Test
    void theEdgePayloadOfAReferenceTitleHitLosesTheReferenceText() {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("citedEid", "2-s2.0-1");
        edge.put("citingEid", "2-s2.0-2");
        edge.put("matchedReference", "A, B: Paper. Journal (2020)");

        JsonNode stripped = mapper.valueToTree(ScopusLicensedFields.strip("SCOPUS_PYTHON_REFTITLE_EDGE", edge, mapper));

        assertFalse(stripped.has("matchedReference"));
        assertEquals("2-s2.0-2", stripped.path("citingEid").asText());
    }

    @Test
    void payloadsOfOtherSourcesAndCleanPayloadsPassThroughUntouched() {
        Map<String, Object> wizard = Map.of("title", "Typed by the researcher", "description", "their own text");
        assertSame(wizard, ScopusLicensedFields.strip("USER_DEFINED_WIZARD", wizard, mapper));

        Map<String, Object> clean = Map.of("eid", "2-s2.0-1", "title", "Paper");
        assertSame(clean, ScopusLicensedFields.strip("SCOPUS_JSON_BOOTSTRAP", clean, mapper));
    }

    @Test
    void abstractAndKeywordsOfAPublicationViewAreNeverSerialised() throws Exception {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId("spub_1");
        view.setTitle("Paper");
        view.setDescription("An abstract still stored from an older import");
        view.setAuthKeywords(List.of("k1"));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(view));

        assertEquals("Paper", json.path("title").asText());
        assertFalse(json.has("description"));
        assertFalse(json.has("authKeywords"));
    }
}
