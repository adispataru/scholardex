package ro.uvt.pokedex.core.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiAccessDeniedHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handleWritesForbiddenJsonEnvelopeForRequestPath() throws Exception {
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals(MockHttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(MockHttpServletResponse.SC_FORBIDDEN, body.get("status").asInt());
        assertEquals("forbidden", body.get("error").asText());
        assertEquals("/api/admin/users", body.get("path").asText());
        assertTrue(body.hasNonNull("timestamp"));
        assertDoesNotThrow(() -> Instant.parse(body.get("timestamp").asText()));
    }
}
