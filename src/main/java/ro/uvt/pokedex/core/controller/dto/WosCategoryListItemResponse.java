package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

/**
 * A WoS category row on the signed-in categories list. Beyond identity + journal count it carries the
 * aggregated JCR metrics: latest-year average / top Article Influence Score (AIS, current through 2024) and
 * average / top Impact Factor (IF, historical — our JCR data ends 2019, so {@code ifYear} is exposed to
 * label it). {@code aisTrend} is the last few years of average AIS for the per-row sparkline.
 */
public record WosCategoryListItemResponse(
        String key,
        String categoryName,
        String edition,
        long journalCount,
        Integer latestYear,
        Double avgAis,
        Double topAis,
        Integer aisYear,
        Double avgIf,
        Double topIf,
        Integer ifYear,
        List<MetricPoint> aisTrend
) {
    /** One (year, value) point of an aggregated-metric time series. */
    public record MetricPoint(int year, Double avg) {}

    /** Identity-only convenience constructor (no aggregated metrics) — used by tests and non-metric callers. */
    public WosCategoryListItemResponse(String key, String categoryName, String edition, long journalCount, Integer latestYear) {
        this(key, categoryName, edition, journalCount, latestYear, null, null, null, null, null, null, List.of());
    }
}
