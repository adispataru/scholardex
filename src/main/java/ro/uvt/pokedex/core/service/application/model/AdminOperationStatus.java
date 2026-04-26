package ro.uvt.pokedex.core.service.application.model;

import java.time.Instant;

public record AdminOperationStatus(
        Instant lastRunAt,
        String outcome,
        String summary
) {
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_PARTIAL = "PARTIAL";
    public static final String OUTCOME_NEVER_RUN = "NEVER_RUN";
    public static final String OUTCOME_IN_PROGRESS = "IN_PROGRESS";

    public static AdminOperationStatus neverRun() {
        return new AdminOperationStatus(null, OUTCOME_NEVER_RUN, null);
    }

    public boolean hasRun() {
        return lastRunAt != null;
    }
}
