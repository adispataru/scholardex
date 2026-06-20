package ro.uvt.pokedex.core.service.application;

/**
 * Thrown when a publication-authorship decision is attempted before the researcher has confirmed their
 * affiliation scope (the H70 onboarding gate). It is a recoverable, user-facing condition — the workspace
 * catches it and routes the researcher into the onboarding wizard rather than surfacing a 500.
 */
public class AffiliationConfirmationRequiredException extends RuntimeException {
    public AffiliationConfirmationRequiredException(String message) {
        super(message);
    }
}
