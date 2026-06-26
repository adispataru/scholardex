package ro.uvt.pokedex.core.service.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IndividualReportRunDto(
        String runId,
        String reportDefinitionId,
        List<IndicatorApplyResultDto> indicatorResults,
        Map<String, Double> indicatorScoresByIndicatorId,
        Map<Integer, Double> criteriaScores,
        Instant createdAt,
        Source source,
        String triggeredByEmail
) {

    public enum Source {
        PERSISTED,
        BUILT,
        /** H77: a run produced by the admin provisional scoring pass (DECLARED authorship, read-only). */
        ADMIN_PROVISIONAL
    }
}
