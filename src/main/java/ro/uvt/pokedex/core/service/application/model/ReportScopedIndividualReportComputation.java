package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.Map;

public record ReportScopedIndividualReportComputation(
        Map<Indicator, Double> indicatorScores,
        Map<String, Double> indicatorScoresByIndicatorId,
        Map<Integer, Double> criterionScores,
        Map<String, IndicatorApplyResultDto> reportScopedIndicatorResultsByIndicatorId,
        /** S2: per-position indicator totals (indicatorId → position → total), only for indicators whose
         *  formula references {@code Poz} AND whose totals diverge from the canonical value. */
        Map<String, Map<String, Double>> indicatorScoresByPositionByIndicatorId,
        /** H138: the competition domain (1–13) a domain-selectable report was scored under; null otherwise. */
        Integer competitionDomainCode
) {
    /** The pre-H138 shape: no competition domain. */
    public ReportScopedIndividualReportComputation(
            Map<Indicator, Double> indicatorScores,
            Map<String, Double> indicatorScoresByIndicatorId,
            Map<Integer, Double> criterionScores,
            Map<String, IndicatorApplyResultDto> reportScopedIndicatorResultsByIndicatorId,
            Map<String, Map<String, Double>> indicatorScoresByPositionByIndicatorId) {
        this(indicatorScores, indicatorScoresByIndicatorId, criterionScores, reportScopedIndicatorResultsByIndicatorId,
                indicatorScoresByPositionByIndicatorId, null);
    }
}
