package ro.uvt.pokedex.core.service.integration;

public class IntegrationException extends RuntimeException {
    private final IntegrationErrorCode errorCode;
    private final boolean retryable;

    public IntegrationException(IntegrationErrorCode errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public IntegrationException(IntegrationErrorCode errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public IntegrationErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
