package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

/**
 * One metric's view of a WoS category (the AIS/IF switch on the category page shows one block at a time).
 * Everything inside is anchored to the block's {@code referenceYear} — the latest year this category has
 * facts for this metric (IF currently ends 2019 dataset-wide; when newer JCR data lands the reference moves
 * by itself). The cohort is exactly the reference-year membership, so quartiles and ranks come from a single
 * year and never mix eras; journals that left earlier appear under {@code formerMembers}, labelled with the
 * year they were last ranked. {@code stale} marks a block whose reference year is behind the dataset-wide
 * latest year for the metric (a retired category name keeps its data but reads as historical).
 */
public record WosCategoryMetricBlock(
        String metricType,
        String metricLabel,
        Integer referenceYear,
        Integer globalLatestYear,
        int windowFrom,
        int windowTo,
        boolean stale,
        List<Row> cohort,
        List<FormerMember> formerMembers,
        WosCategoryMetrics.QuartileSplit quartileSplit
) {
    /** One reference-year cohort member: quartile/rank/value from the reference year, avg + trend windowed. */
    public record Row(
            String journalId,
            String journalName,
            String issn,
            String quarter,
            Integer rank,
            Double value,
            Double windowAvg,
            List<MetricPoint> trend
    ) {
        /** Compact "year:value,…" encoding of the windowed trend for the row sparkline data attribute. */
        public String trendCsv() {
            if (trend == null || trend.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (MetricPoint p : trend) {
                if (p.value() == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(p.year()).append(':').append(p.value());
            }
            return sb.toString();
        }
    }

    /** A journal with historical facts in this category that is absent from the reference-year cohort. */
    public record FormerMember(
            String journalId,
            String journalName,
            Integer lastYear,
            String quarter,
            Integer rank,
            Integer cohortSizeAtLastYear
    ) {
    }

    /** One (year, value) point of a windowed metric series. */
    public record MetricPoint(int year, Double value) {
    }

    public boolean hasCohort() {
        return cohort != null && !cohort.isEmpty();
    }
}
