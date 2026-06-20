package ro.uvt.pokedex.core.service.application.onboarding;

/**
 * The ordered steps of the H70 researcher onboarding wizard. The wizard opens at the first step whose data
 * prerequisite is not yet satisfied ("resume from last-known info"), so the order here is the flow order.
 */
public enum OnboardingStep {
    /** Scopus / WoS author ids (usually already populated by the staff import). */
    IDENTITY_IDS,
    /** ORCID iD — enables the OpenAlex author sync. */
    ORCID,
    /** Confirm/deny the affiliations observed from the resolved author records. */
    AFFILIATIONS,
    /** Confirm which canonical Scholardex author record(s) are the researcher's. */
    AUTHOR_MATCH,
    /** Terminal step: review the recommended bulk publication auto-claim. */
    PUBLICATION_CLAIM
}
