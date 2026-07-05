package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.WosCategoryListItemResponse;
import ro.uvt.pokedex.core.controller.dto.WosCategoryPageResponse;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.WosCategoryJournalViewModel;
import ro.uvt.pokedex.core.service.application.model.WosCategoryMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostgresWosCategoryReadPort {

    private static final Set<EditionNormalized> SUPPORTED_EDITIONS = Set.of(EditionNormalized.SCIE, EditionNormalized.SSCI);
    private static final int MAX_QUERY_LENGTH = 100;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public Optional<WosCategoryDetailViewModel> findCategoryPage(String categoryName, EditionNormalized edition) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", categoryName)
                .addValue("edition", edition.name());

        List<WosCategoryFact> facts = namedParameterJdbcTemplate.query(
                """
                SELECT journal_id, year, category_name_canonical, edition_normalized,
                       metric_type, quarter, quartile_rank, rank
                FROM reporting_read.wos_category_fact
                WHERE category_name_canonical = :name
                  AND edition_normalized = :edition::reporting_read.edition_normalized_enum
                """,
                params,
                (rs, rowNum) -> {
                    WosCategoryFact fact = new WosCategoryFact();
                    fact.setJournalId(rs.getString("journal_id"));
                    fact.setYear(rs.getObject("year", Integer.class));
                    fact.setCategoryNameCanonical(rs.getString("category_name_canonical"));
                    String ed = rs.getString("edition_normalized");
                    if (ed != null) {
                        fact.setEditionNormalized(EditionNormalized.valueOf(ed));
                    }
                    String mt = rs.getString("metric_type");
                    if (mt != null) {
                        fact.setMetricType(MetricType.valueOf(mt));
                    }
                    fact.setQuarter(rs.getString("quarter"));
                    fact.setRank(rs.getObject("rank", Integer.class));
                    return fact;
                }
        );

        if (facts.isEmpty()) {
            return Optional.empty();
        }

        Map<String, List<WosCategoryFact>> factsByJournalId = facts.stream()
                .filter(fact -> fact.getJournalId() != null && !fact.getJournalId().isBlank())
                .collect(Collectors.groupingBy(WosCategoryFact::getJournalId, LinkedHashMap::new, Collectors.toList()));

        List<String> journalIds = new ArrayList<>(factsByJournalId.keySet());
        Map<String, RankingRow> rankingRows = new LinkedHashMap<>();
        if (!journalIds.isEmpty()) {
            namedParameterJdbcTemplate.query(
                    "SELECT journal_id, name, issn, e_issn FROM reporting_read.wos_ranking_view WHERE journal_id IN (:ids)",
                    new MapSqlParameterSource("ids", journalIds),
                    (rs, rowNum) -> {
                        rankingRows.put(rs.getString("journal_id"), new RankingRow(
                                rs.getString("journal_id"),
                                rs.getString("name"),
                                rs.getString("issn"),
                                rs.getString("e_issn")
                        ));
                        return null;
                    }
            );
        }

        String key = categoryName + " - " + edition.name();
        Map<String, JournalMetric> journalMetrics = loadJournalMetrics(new ArrayList<>(factsByJournalId.keySet()));
        List<WosCategoryJournalViewModel> journals = new ArrayList<>();
        Integer latestYear = null;
        int[] aisQuartiles = new int[4]; // Q1..Q4 counts for the category quartile split
        for (Map.Entry<String, List<WosCategoryFact>> entry : factsByJournalId.entrySet()) {
            List<WosCategoryFact> journalFacts = entry.getValue();
            RankingRow row = rankingRows.get(entry.getKey());
            MetricSnapshot snapshot = buildMetricSnapshot(journalFacts);
            if (snapshot.latestYear != null && (latestYear == null || snapshot.latestYear > latestYear)) {
                latestYear = snapshot.latestYear;
            }
            String name = row != null && row.name() != null && !row.name().isBlank() ? row.name() : entry.getKey();
            String issn = row != null ? blankToDash(row.issn()) : "—";
            String eIssn = row != null ? blankToDash(row.eIssn()) : "—";
            String aisQuarter = snapshot.metricQuarter(MetricType.AIS);
            int qIdx = quartileIndex(aisQuarter);
            if (qIdx >= 0) {
                aisQuartiles[qIdx]++;
            }
            JournalMetric jm = journalMetrics.getOrDefault(entry.getKey(), JournalMetric.empty());
            journals.add(new WosCategoryJournalViewModel(
                    entry.getKey(),
                    name,
                    issn,
                    eIssn,
                    snapshot.latestYear,
                    aisQuarter,
                    snapshot.metricQuarter(MetricType.RIS),
                    snapshot.metricQuarter(MetricType.IF),
                    snapshot.metricRank(MetricType.AIS),
                    jm.latestAis(), jm.latestAisYear(),
                    jm.latestIf(), jm.latestIfYear(),
                    jm.avg5Ais(), jm.avg5If(),
                    jm.aisTrend()
            ));
        }

        journals.sort(Comparator.comparing(WosCategoryJournalViewModel::journalName, String.CASE_INSENSITIVE_ORDER));
        WosCategoryMetrics.QuartileSplit split =
                new WosCategoryMetrics.QuartileSplit(aisQuartiles[0], aisQuartiles[1], aisQuartiles[2], aisQuartiles[3]);
        return Optional.of(new WosCategoryDetailViewModel(
                key,
                categoryName,
                edition.name(),
                journals.size(),
                latestYear,
                journals,
                loadCategoryMetrics(categoryName, edition, split)
        ));
    }

    /** Per-journal AIS/IF: latest value+year, last-5-DB-years average, and the AIS trend (for row sparklines). */
    private Map<String, JournalMetric> loadJournalMetrics(List<String> journalIds) {
        if (journalIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, java.util.TreeMap<Integer, Double>>> raw = new java.util.HashMap<>();
        namedParameterJdbcTemplate.query(
                """
                SELECT journal_id, metric_type, year, value
                FROM reporting_read.wos_metric_fact
                WHERE journal_id IN (:ids)
                  AND metric_type IN ('AIS'::reporting_read.metric_type_enum, 'IF'::reporting_read.metric_type_enum)
                  AND value IS NOT NULL
                """,
                new MapSqlParameterSource("ids", journalIds),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String jid = rs.getString("journal_id");
                    String metric = rs.getString("metric_type");
                    Integer year = rs.getObject("year", Integer.class);
                    Double value = rs.getObject("value", Double.class);
                    if (jid == null || metric == null || year == null || value == null) {
                        return;
                    }
                    raw.computeIfAbsent(jid, k -> new java.util.HashMap<>())
                            .computeIfAbsent(metric, k -> new java.util.TreeMap<>())
                            .put(year, value);
                });
        Map<String, JournalMetric> out = new java.util.HashMap<>();
        for (Map.Entry<String, Map<String, java.util.TreeMap<Integer, Double>>> e : raw.entrySet()) {
            java.util.TreeMap<Integer, Double> ais = e.getValue().getOrDefault("AIS", new java.util.TreeMap<>());
            java.util.TreeMap<Integer, Double> impact = e.getValue().getOrDefault("IF", new java.util.TreeMap<>());
            out.put(e.getKey(), new JournalMetric(
                    latestValue(ais), latestKey(ais), latestValue(impact), latestKey(impact),
                    avgLastFive(ais), avgLastFive(impact), trendLastFive(ais)));
        }
        return out;
    }

    private static Double latestValue(java.util.TreeMap<Integer, Double> series) {
        return series.isEmpty() ? null : series.lastEntry().getValue();
    }

    private static Integer latestKey(java.util.TreeMap<Integer, Double> series) {
        return series.isEmpty() ? null : series.lastKey();
    }

    private static Double avgLastFive(java.util.TreeMap<Integer, Double> series) {
        if (series.isEmpty()) {
            return null;
        }
        java.util.OptionalDouble avg = series.descendingMap().values().stream().limit(5)
                .mapToDouble(Double::doubleValue).average();
        return avg.isPresent() ? avg.getAsDouble() : null;
    }

    private static List<WosCategoryJournalViewModel.MetricPoint> trendLastFive(java.util.TreeMap<Integer, Double> series) {
        List<Integer> years = new ArrayList<>(series.navigableKeySet());
        int from = Math.max(0, years.size() - 5);
        List<WosCategoryJournalViewModel.MetricPoint> points = new ArrayList<>();
        for (int i = from; i < years.size(); i++) {
            points.add(new WosCategoryJournalViewModel.MetricPoint(years.get(i), series.get(years.get(i))));
        }
        return points;
    }

    private static int quartileIndex(String quarter) {
        if (quarter == null) {
            return -1;
        }
        return switch (quarter.trim().toUpperCase(Locale.ROOT)) {
            case "Q1" -> 0;
            case "Q2" -> 1;
            case "Q3" -> 2;
            case "Q4" -> 3;
            default -> -1;
        };
    }

    private record JournalMetric(Double latestAis, Integer latestAisYear, Double latestIf, Integer latestIfYear,
                                 Double avg5Ais, Double avg5If, List<WosCategoryJournalViewModel.MetricPoint> aisTrend) {
        static JournalMetric empty() {
            return new JournalMetric(null, null, null, null, null, null, List.of());
        }
    }

    /** Headline latest-year AIS/IF (avg + top + median), the per-year avg-AIS/avg-IF trend for the detail chart,
     *  and the category's latest-year AIS quartile split (computed by the caller from its journals). */
    private WosCategoryMetrics loadCategoryMetrics(String categoryName, EditionNormalized edition,
                                                   WosCategoryMetrics.QuartileSplit aisQuartiles) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", categoryName)
                .addValue("edition", edition.name());

        Map<String, MetricHead> heads = new java.util.HashMap<>();
        namedParameterJdbcTemplate.query(
                """
                SELECT metric_type, avg_value, max_value, median_value, year FROM (
                    SELECT metric_type, avg_value, max_value, median_value, year,
                           row_number() OVER (PARTITION BY metric_type ORDER BY year DESC) rn
                    FROM reporting_read.wos_category_metric_agg
                    WHERE category_name_canonical = :name
                      AND edition_normalized = :edition::reporting_read.edition_normalized_enum
                      AND metric_type IN ('AIS'::reporting_read.metric_type_enum, 'IF'::reporting_read.metric_type_enum)
                ) t WHERE rn = 1
                """,
                params,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> heads.put(
                        rs.getString("metric_type"),
                        new MetricHead(rs.getObject("avg_value", Double.class),
                                rs.getObject("max_value", Double.class),
                                rs.getObject("median_value", Double.class),
                                rs.getObject("year", Integer.class))));

        List<WosCategoryMetrics.TrendPoint> trend = new ArrayList<>();
        namedParameterJdbcTemplate.query(
                """
                SELECT year,
                       MAX(avg_value) FILTER (WHERE metric_type = 'AIS'::reporting_read.metric_type_enum) AS avg_ais,
                       MAX(avg_value) FILTER (WHERE metric_type = 'IF'::reporting_read.metric_type_enum)  AS avg_if
                FROM reporting_read.wos_category_metric_agg
                WHERE category_name_canonical = :name
                  AND edition_normalized = :edition::reporting_read.edition_normalized_enum
                  AND metric_type IN ('AIS'::reporting_read.metric_type_enum, 'IF'::reporting_read.metric_type_enum)
                GROUP BY year ORDER BY year
                """,
                params,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> trend.add(
                        new WosCategoryMetrics.TrendPoint(rs.getInt("year"),
                                rs.getObject("avg_ais", Double.class), rs.getObject("avg_if", Double.class))));

        MetricHead ais = heads.get("AIS");
        MetricHead impact = heads.get("IF");
        return new WosCategoryMetrics(
                ais != null ? ais.avg() : null, ais != null ? ais.max() : null, ais != null ? ais.median() : null,
                ais != null ? ais.year() : null,
                impact != null ? impact.avg() : null, impact != null ? impact.max() : null, impact != null ? impact.median() : null,
                impact != null ? impact.year() : null,
                aisQuartiles, trend);
    }

    private record MetricHead(Double avg, Double max, Double median, Integer year) {}

    public WosCategoryPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);
        int safeSize = size <= 0 ? 25 : size;
        MapSqlParameterSource params = new MapSqlParameterSource();

        StringBuilder whereClause = buildSearchWhereClause();

        if (normalizedQuery != null) {
            whereClause.append(" AND (category_name_canonical ILIKE :q OR edition_normalized::text ILIKE :q)");
            params.addValue("q", "%" + escapeLikePattern(normalizedQuery) + "%");
        }

        String countSql = """
                SELECT COUNT(*) FROM (
                    SELECT 1
                    FROM reporting_read.wos_category_fact
                    """ + whereClause + """
                    GROUP BY category_name_canonical, edition_normalized
                ) sub
                """;
        Long totalItems = namedParameterJdbcTemplate.queryForObject(countSql, params, Long.class);
        long total = totalItems == null ? 0L : totalItems;
        int totalPages = (int) Math.ceil(total / (double) safeSize);
        int safePage = normalizePage(page, totalPages);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) safePage * safeSize);

        // The base category list (journal count + latest year) is joined LATERALLY to the latest-year
        // AIS and IF aggregate rows so the list can show + sort by avg/top of each metric. NULLS LAST keeps
        // categories that lack a given metric (e.g. AHCI with no IF) at the bottom of a metric sort.
        String aggregateSql = """
                SELECT c.key, c.category_name_canonical, c.edition_normalized, c.journal_count, c.latest_year,
                       ais.avg_value AS ais_avg, ais.max_value AS ais_top, ais.year AS ais_year,
                       if_agg.avg_value AS if_avg, if_agg.max_value AS if_top, if_agg.year AS if_year
                FROM (
                    SELECT category_name_canonical || ' - ' || edition_normalized AS key,
                           category_name_canonical,
                           edition_normalized,
                           COUNT(DISTINCT journal_id) AS journal_count,
                           MAX(year)                  AS latest_year
                    FROM reporting_read.wos_category_fact
                    """ + whereClause + """
                    GROUP BY category_name_canonical, edition_normalized
                ) c
                LEFT JOIN LATERAL (
                    SELECT avg_value, max_value, year FROM reporting_read.wos_category_metric_agg a
                    WHERE a.category_name_canonical = c.category_name_canonical
                      AND a.edition_normalized = c.edition_normalized
                      AND a.metric_type = 'AIS'::reporting_read.metric_type_enum
                    ORDER BY a.year DESC LIMIT 1
                ) ais ON true
                LEFT JOIN LATERAL (
                    SELECT avg_value, max_value, year FROM reporting_read.wos_category_metric_agg a
                    WHERE a.category_name_canonical = c.category_name_canonical
                      AND a.edition_normalized = c.edition_normalized
                      AND a.metric_type = 'IF'::reporting_read.metric_type_enum
                    ORDER BY a.year DESC LIMIT 1
                ) if_agg ON true
                ORDER BY """ + " " + normalizedSort + " " + normalizedDirection + " NULLS LAST " + """
                LIMIT :limit OFFSET :offset
                """;

        List<WosCategoryListItemResponse> items = namedParameterJdbcTemplate.query(
                aggregateSql,
                params,
                (rs, rowNum) -> new WosCategoryListItemResponse(
                        rs.getString("key"),
                        rs.getString("category_name_canonical"),
                        rs.getString("edition_normalized"),
                        rs.getLong("journal_count"),
                        rs.getObject("latest_year", Integer.class),
                        rs.getObject("ais_avg", Double.class),
                        rs.getObject("ais_top", Double.class),
                        rs.getObject("ais_year", Integer.class),
                        rs.getObject("if_avg", Double.class),
                        rs.getObject("if_top", Double.class),
                        rs.getObject("if_year", Integer.class),
                        List.of()
                )
        );
        return new WosCategoryPageResponse(attachAisTrend(items), safePage, safeSize, total, totalPages);
    }

    /** Attach each page item's last-5-years average-AIS series (for the row sparkline) in one extra query. */
    private List<WosCategoryListItemResponse> attachAisTrend(List<WosCategoryListItemResponse> items) {
        if (items.isEmpty()) {
            return items;
        }
        Integer latestAisYear = namedParameterJdbcTemplate.queryForObject(
                "SELECT MAX(year) FROM reporting_read.wos_category_metric_agg "
                        + "WHERE metric_type = 'AIS'::reporting_read.metric_type_enum",
                new MapSqlParameterSource(), Integer.class);
        if (latestAisYear == null) {
            return items;
        }
        List<String> names = items.stream().map(WosCategoryListItemResponse::categoryName).distinct().toList();
        Map<String, List<WosCategoryListItemResponse.MetricPoint>> trendByKey = new java.util.HashMap<>();
        namedParameterJdbcTemplate.query(
                """
                SELECT category_name_canonical, edition_normalized, year, avg_value
                FROM reporting_read.wos_category_metric_agg
                WHERE metric_type = 'AIS'::reporting_read.metric_type_enum
                  AND category_name_canonical IN (:names)
                  AND year >= :minYear
                ORDER BY category_name_canonical, edition_normalized, year
                """,
                new MapSqlParameterSource().addValue("names", names).addValue("minYear", latestAisYear - 4),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String key = rs.getString("category_name_canonical") + " - " + rs.getString("edition_normalized");
                    trendByKey.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new WosCategoryListItemResponse.MetricPoint(
                                    rs.getInt("year"), rs.getObject("avg_value", Double.class)));
                });
        return items.stream()
                .map(it -> new WosCategoryListItemResponse(
                        it.key(), it.categoryName(), it.edition(), it.journalCount(), it.latestYear(),
                        it.avgAis(), it.topAis(), it.aisYear(), it.avgIf(), it.topIf(), it.ifYear(),
                        trendByKey.getOrDefault(it.key(), List.of())))
                .toList();
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        return switch (normalized) {
            case "categoryName" -> "category_name_canonical";
            case "edition" -> "edition_normalized";
            case "journalCount" -> "journal_count";
            case "latestYear" -> "latest_year";
            case "avgAis" -> "ais_avg";
            case "topAis" -> "ais_top";
            case "avgIf" -> "if_avg";
            case "topIf" -> "if_top";
            default -> throw new IllegalArgumentException(
                    "Invalid sort parameter. Allowed: categoryName, edition, journalCount, latestYear, avgAis, topAis, avgIf, topIf.");
        };
    }

    private String normalizeDirection(String direction) {
        return QueryNormalizationSupport.normalizeDirectionUpper(direction);
    }

    private String normalizeQuery(String q) {
        if (q == null) {
            return null;
        }
        String normalized = q.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Invalid q parameter. Maximum length is " + MAX_QUERY_LENGTH + ".");
        }
        return normalized;
    }

    private MetricSnapshot buildMetricSnapshot(List<WosCategoryFact> facts) {
        MetricSnapshot snapshot = new MetricSnapshot();
        for (WosCategoryFact fact : facts) {
            if (fact.getYear() != null && (snapshot.latestYear == null || fact.getYear() > snapshot.latestYear)) {
                snapshot.latestYear = fact.getYear();
            }
            if (fact.getMetricType() == null || fact.getYear() == null) {
                continue;
            }
            MetricObservation current = snapshot.latestByMetric.get(fact.getMetricType());
            if (current == null || fact.getYear() > current.year()) {
                snapshot.latestByMetric.put(fact.getMetricType(),
                        new MetricObservation(fact.getYear(), normalizeQuarter(fact.getQuarter()), fact.getRank()));
            }
        }
        return snapshot;
    }

    private String normalizeQuarter(String rawQuarter) {
        if (rawQuarter == null || rawQuarter.isBlank()) {
            return "—";
        }
        String normalized = rawQuarter.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("Q") ? normalized : "Q" + normalized;
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private StringBuilder buildSearchWhereClause() {
        return new StringBuilder("""
                 WHERE edition_normalized IN ('SCIE', 'SSCI')
                   AND category_name_canonical IS NOT NULL
                   AND category_name_canonical != ''
                   AND journal_id IS NOT NULL
                   AND journal_id != ''
                """);
    }

    private String escapeLikePattern(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private int normalizePage(int page, int totalPages) {
        return totalPages == 0 ? 0 : Math.min(page, totalPages - 1);
    }

    private record RankingRow(String journalId, String name, String issn, String eIssn) {}

    private static final class MetricSnapshot {
        private Integer latestYear;
        private final Map<MetricType, MetricObservation> latestByMetric = new EnumMap<>(MetricType.class);

        private String metricQuarter(MetricType metricType) {
            MetricObservation observation = latestByMetric.get(metricType);
            return observation == null ? "—" : observation.quarter();
        }

        private Integer metricRank(MetricType metricType) {
            MetricObservation observation = latestByMetric.get(metricType);
            return observation == null ? null : observation.rank();
        }
    }

    private record MetricObservation(Integer year, String quarter, Integer rank) {}
}
