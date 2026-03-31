package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.Map;

public record ReportScopedIndividualReportComputation(
        Map<Indicator, Double> indicatorScores,
        Map<String, Double> indicatorScoresByIndicatorId,
        Map<Integer, Double> criterionScores,
        Map<String, IndicatorApplyResultDto> reportScopedIndicatorResultsByIndicatorId
) {
}
