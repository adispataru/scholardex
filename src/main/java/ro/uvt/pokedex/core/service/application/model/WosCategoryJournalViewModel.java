package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

/**
 * A journal within a WoS category. Beyond identity + latest-year quartiles it carries the numeric JCR
 * metrics: latest Article Influence Score / Impact Factor (with their years — IF is historical, ≤2019),
 * each metric's last-5-DB-years average, the journal's rank within the category, and a per-journal AIS
 * trend for a row sparkline.
 */
public record WosCategoryJournalViewModel(
        String journalId,
        String journalName,
        String issn,
        String eIssn,
        Integer latestYear,
        String latestAisQuarter,
        String latestRisQuarter,
        String latestIfQuarter,
        Integer rank,
        Double latestAis,
        Integer latestAisYear,
        Double latestIf,
        Integer latestIfYear,
        Double avg5Ais,
        Double avg5If,
        List<MetricPoint> aisTrend
) {
    /** One (year, value) point of a journal's metric series. */
    public record MetricPoint(int year, Double value) {}

    /** Compact "year:value,year:value,…" encoding of the AIS trend for a data attribute (row sparkline JS). */
    public String aisTrendCsv() {
        if (aisTrend == null || aisTrend.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MetricPoint p : aisTrend) {
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

    /** Identity + quartiles only (no numeric metrics) — used by tests and non-metric callers. */
    public WosCategoryJournalViewModel(String journalId, String journalName, String issn, String eIssn,
                                       Integer latestYear, String latestAisQuarter, String latestRisQuarter,
                                       String latestIfQuarter) {
        this(journalId, journalName, issn, eIssn, latestYear, latestAisQuarter, latestRisQuarter, latestIfQuarter,
                null, null, null, null, null, null, null, List.of());
    }
}
