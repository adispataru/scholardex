package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;

import java.time.Instant;

public record WosEnrichmentRunSummaryDto(
        String stepName,
        boolean executed,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        int processed,
        int computed,
        int preserved,
        int failed,
        int skipped,
        String note
) {
    public static WosEnrichmentRunSummaryDto fromStep(
            MigrationStepResult step,
            Instant startedAt,
            Instant completedAt
    ) {
        long durationMs = 0L;
        if (startedAt != null && completedAt != null) {
            durationMs = Math.max(0L, completedAt.toEpochMilli() - startedAt.toEpochMilli());
        }
        int processed = step == null ? 0 : step.processed();
        int computed = step == null ? 0 : step.updated();
        int failed = step == null ? 0 : step.errors();
        int preserved = Math.max(0, processed - computed - failed);
        int skipped = step == null ? 0 : step.skipped();
        return new WosEnrichmentRunSummaryDto(
                step == null ? "enrich-category-rankings" : step.stepName(),
                step != null && step.executed(),
                startedAt,
                completedAt,
                durationMs,
                processed,
                computed,
                preserved,
                failed,
                skipped,
                step == null ? "not-run" : step.note()
        );
    }

    public static WosEnrichmentRunSummaryDto notRun() {
        return new WosEnrichmentRunSummaryDto(
                "enrich-category-rankings",
                false,
                null,
                null,
                0L,
                0,
                0,
                0,
                0,
                0,
                "not-run"
        );
    }
}
