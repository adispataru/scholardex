package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

/**
 * A WoS category row on the signed-in categories list, scoped to ONE metric (the AIS/IF switch): {@code year}
 * is the category's reference year for that metric (its latest aggregate year — moves when newer JCR data
 * lands), {@code journalCount} is the reference-year cohort size, {@code avg}/{@code top} the aggregates of
 * that year, and {@code trend} the recent average series for the row sparkline. {@code stale} marks
 * categories whose reference year is behind the dataset-wide latest for the metric (retired taxonomy names).
 */
public record WosCategoryListItemResponse(
        String key,
        String categoryName,
        String edition,
        String metric,
        Integer year,
        boolean stale,
        Long journalCount,
        Double avg,
        Double top,
        List<MetricPoint> trend
) {
    /** One (year, value) point of an aggregated-metric time series. */
    public record MetricPoint(int year, Double avg) {}

    /** Identity-only convenience constructor (no aggregated metrics) — used by tests and non-metric callers. */
    public WosCategoryListItemResponse(String key, String categoryName, String edition, long journalCount, Integer year) {
        this(key, categoryName, edition, "AIS", year, false, journalCount, null, null, List.of());
    }
}
