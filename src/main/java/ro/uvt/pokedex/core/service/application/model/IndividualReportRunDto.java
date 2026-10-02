package ro.uvt.pokedex.core.service.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IndividualReportRunDto(
        String runId,
        String reportDefinitionId,
        List<IndicatorApplyResultDto> indicatorResults,
        Map<String, Double> indicatorScoresByIndicatorId,
        /** S2: per-position indicator totals (indicatorId → position → total); empty when no formula diverges. */
        Map<String, Map<String, Double>> indicatorScoresByPositionByIndicatorId,
        Map<Integer, Double> criteriaScores,
        Instant createdAt,
        Source source,
        String triggeredByEmail,
        /** H138: the competition domain (1–13) the run was scored under, for a domain-selectable report. */
        Integer competitionDomainCode
) {
    /** The pre-H138 shape: no competition domain. */
    public IndividualReportRunDto(String runId, String reportDefinitionId, List<IndicatorApplyResultDto> indicatorResults,
                                  Map<String, Double> indicatorScoresByIndicatorId,
                                  Map<String, Map<String, Double>> indicatorScoresByPositionByIndicatorId,
                                  Map<Integer, Double> criteriaScores, Instant createdAt, Source source, String triggeredByEmail) {
        this(runId, reportDefinitionId, indicatorResults, indicatorScoresByIndicatorId, indicatorScoresByPositionByIndicatorId,
                criteriaScores, createdAt, source, triggeredByEmail, null);
    }

    public enum Source {
        PERSISTED,
        BUILT,
        /** H77: a run produced by the admin provisional scoring pass (DECLARED authorship, read-only). */
        ADMIN_PROVISIONAL
    }
}
