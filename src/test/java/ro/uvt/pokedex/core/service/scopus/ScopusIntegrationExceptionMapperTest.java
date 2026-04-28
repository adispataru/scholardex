package ro.uvt.pokedex.core.service.scopus;

import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class ScopusIntegrationExceptionMapperTest {

    private final ScopusIntegrationExceptionMapper mapper = new ScopusIntegrationExceptionMapper();

    @Test
    void mapRuntimeExceptionReturnsExistingIntegrationException() {
        IntegrationException existing = new IntegrationException(
                IntegrationErrorCode.EXTERNAL_TIMEOUT,
                true,
                "already mapped"
        );

        IntegrationException mapped = mapper.mapRuntimeException(existing);

        assertSame(existing, mapped);
    }

    @Test
    void mapRuntimeExceptionWrapsUnexpectedFailuresAsPersistenceError() {
        RuntimeException exception = new RuntimeException();

        IntegrationException mapped = mapper.mapRuntimeException(exception);

        assertEquals(IntegrationErrorCode.PERSISTENCE_ERROR, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals("Unexpected failure", mapped.getMessage());
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionReturnsExistingIntegrationException() {
        IntegrationException existing = new IntegrationException(
                IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                false,
                "already mapped"
        );

        IntegrationException mapped = mapper.mapIntegrationException("authorWorks", existing);

        assertSame(existing, mapped);
    }

    @Test
    void mapIntegrationExceptionMapsHttp5xxAsRetryableExternal5xx() {
        WebClientResponseException exception = WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                HttpHeaders.EMPTY,
                new byte[0],
                null
        );

        IntegrationException mapped = mapper.mapIntegrationException("citationsByEid", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_5XX, mapped.getErrorCode());
        assertTrue(mapped.isRetryable());
        assertEquals("citationsByEid failed with HTTP 500", mapped.getMessage());
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionMapsHttp4xxAsNonRetryableBadPayload() {
        WebClientResponseException nested = WebClientResponseException.create(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"detail\":\"invalid_author_id\"}".getBytes(),
                null
        );
        RuntimeException exception = new RuntimeException("wrapper", nested);

        IntegrationException mapped = mapper.mapIntegrationException("authorWorks", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals("authorWorks failed with HTTP 400", mapped.getMessage());
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionMapsRequestFailuresAsRetryableTimeout() {
        WebClientRequestException nested = new WebClientRequestException(
                new IllegalStateException("connection refused"),
                HttpMethod.POST,
                URI.create("http://localhost/v1/author-works"),
                HttpHeaders.EMPTY
        );
        RuntimeException exception = new RuntimeException("wrapper", nested);

        IntegrationException mapped = mapper.mapIntegrationException("authorWorks", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_TIMEOUT, mapped.getErrorCode());
        assertTrue(mapped.isRetryable());
        assertEquals("authorWorks failed to reach external service", mapped.getMessage());
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionMapsOversizedPayloadsAsBadPayload() {
        RuntimeException exception = new RuntimeException("wrapper", new DataBufferLimitException("too large"));

        IntegrationException mapped = mapper.mapIntegrationException("citationsByEid", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals(
                "citationsByEid failed because response payload exceeded configured buffer size",
                mapped.getMessage()
        );
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionMapsDecodingErrorsAsBadPayload() {
        RuntimeException exception = new RuntimeException("wrapper", new DecodingException("Cannot decode response"));

        IntegrationException mapped = mapper.mapIntegrationException("authorWorks", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals("authorWorks failed because response payload could not be decoded", mapped.getMessage());
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionUsesRootCauseDetailsForUnexpectedFailures() {
        RuntimeException exception = new RuntimeException("outer", new IllegalStateException("deep detail"));

        IntegrationException mapped = mapper.mapIntegrationException("authorWorks", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals(
                "authorWorks failed with unexpected integration error (IllegalStateException): deep detail",
                mapped.getMessage()
        );
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionOmitsBlankRootCauseDetails() {
        RuntimeException exception = new RuntimeException("outer", new IllegalArgumentException(" "));

        IntegrationException mapped = mapper.mapIntegrationException("citationsByEid", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals(
                "citationsByEid failed with unexpected integration error (IllegalArgumentException)",
                mapped.getMessage()
        );
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionStopsHttpCauseSearchAtGuardLimit() {
        WebClientResponseException tooDeep = WebClientResponseException.create(
                HttpStatus.BAD_GATEWAY.value(),
                "Bad Gateway",
                HttpHeaders.EMPTY,
                new byte[0],
                null
        );
        RuntimeException exception = nestedCause(tooDeep, 21);

        IntegrationException mapped = mapper.mapIntegrationException("authorWorks", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals(
                "authorWorks failed with unexpected integration error (RuntimeException): wrapper-1",
                mapped.getMessage()
        );
        assertSame(exception, mapped.getCause());
    }

    @Test
    void mapIntegrationExceptionStopsRootCauseSearchAtGuardLimit() {
        RuntimeException tooDeep = new RuntimeException("too deep");
        RuntimeException exception = nestedCause(tooDeep, 21);

        IntegrationException mapped = mapper.mapIntegrationException("citationsByEid", exception);

        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals(
                "citationsByEid failed with unexpected integration error (RuntimeException): wrapper-1",
                mapped.getMessage()
        );
        assertSame(exception, mapped.getCause());
    }

    private RuntimeException nestedCause(Throwable innermost, int wrappers) {
        Throwable current = innermost;
        for (int i = 1; i <= wrappers; i++) {
            current = new RuntimeException("wrapper-" + i, current);
        }
        return (RuntimeException) current;
    }
}
