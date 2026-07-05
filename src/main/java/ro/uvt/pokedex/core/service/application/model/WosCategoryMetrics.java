package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

/**
 * Aggregated JCR metrics for a WoS category detail page: headline latest-year average / top Article Influence
 * Score (AIS — current) and Impact Factor (IF — historical, our data ends 2019, hence {@code ifYear}), plus a
 * per-year trend series driving the detail chart. IF points are null after 2019, so the chart shows the AIS
 * line continuing while the IF line ends — making the "IF is historical" story visible.
 */
public record WosCategoryMetrics(
        Double avgAis,
        Double topAis,
        Double medianAis,
        Integer aisYear,
        Double avgIf,
        Double topIf,
        Double medianIf,
        Integer ifYear,
        QuartileSplit aisQuartiles,
        List<TrendPoint> trend
) {
    /** One year of the category's average AIS / IF (either may be null for a year). */
    public record TrendPoint(int year, Double avgAis, Double avgIf) {}

    /** Count of the category's journals in each latest-year AIS quartile. */
    public record QuartileSplit(int q1, int q2, int q3, int q4) {
        public int total() {
            return q1 + q2 + q3 + q4;
        }

        public int pct(int count) {
            int t = total();
            return t == 0 ? 0 : Math.round(count * 100f / t);
        }
    }

    public boolean hasAis() {
        return avgAis != null;
    }

    public boolean hasIf() {
        return avgIf != null;
    }

    public boolean hasQuartiles() {
        return aisQuartiles != null && aisQuartiles.total() > 0;
    }
}
