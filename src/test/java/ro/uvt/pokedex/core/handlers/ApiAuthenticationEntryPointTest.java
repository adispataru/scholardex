package ro.uvt.pokedex.core.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiAuthenticationEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void commenceWritesUnauthorizedJsonEnvelopeForRequestPath() throws Exception {
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rankings/wos");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad credentials"));

        assertEquals(MockHttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(MockHttpServletResponse.SC_UNAUTHORIZED, body.get("status").asInt());
        assertEquals("unauthorized", body.get("error").asText());
        assertEquals("/api/rankings/wos", body.get("path").asText());
        assertTrue(body.hasNonNull("timestamp"));
        assertDoesNotThrow(() -> Instant.parse(body.get("timestamp").asText()));
    }
}
