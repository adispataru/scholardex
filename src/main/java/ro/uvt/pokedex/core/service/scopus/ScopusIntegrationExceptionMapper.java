package ro.uvt.pokedex.core.service.scopus;

import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

final class ScopusIntegrationExceptionMapper {

    IntegrationException mapRuntimeException(Throwable exception) {
        if (exception instanceof IntegrationException ie) {
            return ie;
        }
        return new IntegrationException(
                IntegrationErrorCode.PERSISTENCE_ERROR,
                false,
                exception.getMessage() == null ? "Unexpected failure" : exception.getMessage(),
                exception
        );
    }

    IntegrationException mapIntegrationException(String operation, Throwable exception) {
        if (exception instanceof IntegrationException ie) {
            return ie;
        }
        WebClientResponseException responseException = findCause(exception, WebClientResponseException.class);
        if (responseException != null) {
            int status = responseException.getStatusCode().value();
            if (status >= 500) {
                return new IntegrationException(
                        IntegrationErrorCode.EXTERNAL_5XX,
                        true,
                        operation + " failed with HTTP " + status,
                        exception
                );
            }
            return new IntegrationException(
                    IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                    false,
                    operation + " failed with HTTP " + status,
                    exception
            );
        }
        if (findCause(exception, WebClientRequestException.class) != null) {
            return new IntegrationException(
                    IntegrationErrorCode.EXTERNAL_TIMEOUT,
                    true,
                    operation + " failed to reach external service",
                    exception
            );
        }
        if (findCause(exception, DataBufferLimitException.class) != null) {
            return new IntegrationException(
                    IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                    false,
                    operation + " failed because response payload exceeded configured buffer size",
                    exception
            );
        }
        if (findCause(exception, DecodingException.class) != null) {
            return new IntegrationException(
                    IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                    false,
                    operation + " failed because response payload could not be decoded",
                    exception
            );
        }
        Throwable rootCause = rootCause(exception);
        String details = rootCause.getMessage();
        String suffix = (details == null || details.isBlank()) ? "" : ": " + details;
        return new IntegrationException(
                IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                false,
                operation + " failed with unexpected integration error (" + rootCause.getClass().getSimpleName() + ")" + suffix,
                exception
        );
    }

    private Throwable rootCause(Throwable exception) {
        Throwable current = Exceptions.unwrap(exception);
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 20) {
            current = current.getCause();
        }
        return current;
    }

    private <T extends Throwable> T findCause(Throwable exception, Class<T> type) {
        Throwable current = Exceptions.unwrap(exception);
        int guard = 0;
        while (current != null && guard++ < 20) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
