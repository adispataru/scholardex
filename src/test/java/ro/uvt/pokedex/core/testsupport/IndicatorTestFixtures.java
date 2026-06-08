package ro.uvt.pokedex.core.testsupport;

import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.scoring.Selector;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;

public final class IndicatorTestFixtures {
    private IndicatorTestFixtures() {
    }

    public static void setOutputType(Indicator indicator, String outputType) {
        String strategy = indicator.getScoringStrategy();
        indicator.setKind(IndicatorKind.of(outputType, strategy != null ? strategy : defaultStrategyFor(outputType)));
    }

    public static void setScoringStrategy(Indicator indicator, String strategy) {
        if (strategy == null) {
            indicator.setKind(null);
            return;
        }
        String outputType = indicator.getOutputType();
        indicator.setKind(IndicatorKind.of(outputType != null ? outputType : defaultOutputFor(strategy), strategy));
    }

    public static void setYearRange(Indicator indicator, String yearRange) {
        indicator.setYearRangeSpec(YearRangeSpec.parse(yearRange));
    }

    public static void setScoreYearRange(Indicator indicator, String scoreYearRange) {
        indicator.setScoreYearRangeSpec(ScoreYearRangeSpec.parse(scoreYearRange));
    }

    public static void setSelector(Indicator indicator, String selector) {
        indicator.setSelectorSpec(Selector.of(selector));
    }

    private static String defaultStrategyFor(String outputType) {
        if ("GENERIC_ACTIVITIES".equals(outputType)) return ScoringStrategy.GENERIC_ACTIVITY.name();
        return ScoringStrategy.GENERIC_COUNT.name();
    }

    private static String defaultOutputFor(String strategy) {
        if (ScoringStrategy.GENERIC_ACTIVITY.name().equals(strategy)) return "GENERIC_ACTIVITIES";
        return "PUBLICATIONS";
    }
}
