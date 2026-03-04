package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopusDataServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScopusDataService service = new ScopusDataService();

    @Test
    void createPublicationFromJsonThrowsOnMissingRequiredEid() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", "Test");

        assertThrows(IntegrationException.class, () -> service.createPublicationFromJson(node));
    }

    @Test
    void createPublicationFromJsonParsesRequiredFields() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eid", "2-s2.0-123");
        node.put("title", "Test title");
        node.put("author_count", 1);
        node.put("citedby_count", 0);
        node.put("openaccess", 0);

        assertEquals("2-s2.0-123", service.createPublicationFromJson(node).getEid());
    }
}
