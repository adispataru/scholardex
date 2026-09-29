package ro.uvt.pokedex.core.service.integration;

public enum IntegrationErrorCode {
    VALIDATION_ERROR,
    EXTERNAL_TIMEOUT,
    EXTERNAL_5XX,
    EXTERNAL_RATE_LIMITED,
    EXTERNAL_BAD_PAYLOAD,
    PERSISTENCE_ERROR
}
