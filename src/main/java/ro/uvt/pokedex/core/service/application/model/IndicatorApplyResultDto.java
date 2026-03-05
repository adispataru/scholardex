package ro.uvt.pokedex.core.service.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IndicatorApplyResultDto(
        String resultId,
        String indicatorId,
        String viewName,
        Map<String, Object> rawGraph,
        Summary summary,
        Source source,
        Instant createdAt,
        Instant updatedAt,
        int refreshVersion
) {

    public record Summary(
            Double totalScore,
            Integer totalCount,
            List<String> quarterLabels,
            List<Integer> quarterValues
    ) {
    }

    public enum Source {
        PERSISTED,
        COMPUTED
    }
}
