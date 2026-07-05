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
import ro.uvt.pokedex.core.service.application.model.WosCategoryMetricBlock;
import ro.uvt.pokedex.core.service.application.model.WosCategoryMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
                SELECT journal_id, year, metric_type, quarter, rank
                FROM reporting_read.wos_category_fact
                WHERE category_name_canonical = :name
                  AND edition_normalized = :edition::reporting_read.edition_normalized_enum
                  AND journal_id IS NOT NULL AND journal_id != ''
                  AND year IS NOT NULL
                """,
                params,
                (rs, rowNum) -> {
                    WosCategoryFact fact = new WosCategoryFact();
                    fact.setJournalId(rs.getString("journal_id"));
                    fact.setYear(rs.getObject("year", Integer.class));
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

        Map<MetricType, Integer> globalLatestYearByMetric = loadGlobalLatestYears();
        Map<String, RankingRow> rankingRows = loadRankingRows(facts.stream()
                .map(WosCategoryFact::getJournalId).collect(Collectors.toCollection(LinkedHashSet::new)));

        List<WosCategoryMetricBlock> blocks = new ArrayList<>();
        for (MetricType metric : List.of(MetricType.AIS, MetricType.IF)) {
            WosCategoryMetricBlock block = buildMetricBlock(metric, facts, rankingRows, globalLatestYearByMetric.get(metric));
            if (block != null) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return Optional.empty();
        }

        boolean archival = blocks.stream().allMatch(WosCategoryMetricBlock::stale);
        Integer latestYear = blocks.stream().map(WosCategoryMetricBlock::referenceYear)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(null);
        WosCategoryMetrics.QuartileSplit aisSplit = blocks.stream()
                .filter(b -> MetricType.AIS.name().equals(b.metricType()))
                .map(WosCategoryMetricBlock::quartileSplit)
                .findFirst().orElse(new WosCategoryMetrics.QuartileSplit(0, 0, 0, 0));

        return Optional.of(new WosCategoryDetailViewModel(
                categoryName + " - " + edition.name(),
                categoryName,
                edition.name(),
                blocks.get(0).cohort().size(),
                latestYear,
                archival,
                blocks,
                loadCategoryMetrics(categoryName, edition, aisSplit)
        ));
    }

    /**
     * One metric's cohort view: everything is anchored to the category's reference year for this metric
     * (its latest year with facts), so quartiles/ranks come from a single cohort and never mix eras. The
     * trend/average window is the 5 years ending at the reference year; journals with older facts only
     * become "former members", labelled with the year they were last ranked.
     */
    private WosCategoryMetricBlock buildMetricBlock(
            MetricType metric,
            List<WosCategoryFact> allFacts,
            Map<String, RankingRow> rankingRows,
            Integer globalLatestYear
    ) {
        List<WosCategoryFact> metricFacts = allFacts.stream()
                .filter(f -> f.getMetricType() == metric)
                .toList();
        if (metricFacts.isEmpty()) {
            return null;
        }
        int referenceYear = metricFacts.stream().mapToInt(WosCategoryFact::getYear).max().orElseThrow();
        int windowFrom = referenceYear - 4;

        Map<String, WosCategoryFact> cohortByJournal = new LinkedHashMap<>();
        Map<String, WosCategoryFact> lastFactByJournal = new LinkedHashMap<>();
        Map<Integer, Set<String>> journalsByYear = new java.util.HashMap<>();
        for (WosCategoryFact fact : metricFacts) {
            journalsByYear.computeIfAbsent(fact.getYear(), ignored -> new java.util.HashSet<>()).add(fact.getJournalId());
            if (fact.getYear() == referenceYear) {
                cohortByJournal.putIfAbsent(fact.getJournalId(), fact);
            }
            WosCategoryFact last = lastFactByJournal.get(fact.getJournalId());
            if (last == null || fact.getYear() > last.getYear()) {
                lastFactByJournal.put(fact.getJournalId(), fact);
            }
        }

        Map<String, java.util.TreeMap<Integer, Double>> valueSeries =
                loadMetricValues(metric, cohortByJournal.keySet(), windowFrom, referenceYear);

        int[] quartiles = new int[4];
        List<WosCategoryMetricBlock.Row> cohort = new ArrayList<>();
        for (Map.Entry<String, WosCategoryFact> entry : cohortByJournal.entrySet()) {
            WosCategoryFact fact = entry.getValue();
            RankingRow row = rankingRows.get(entry.getKey());
            String quarter = normalizeQuarter(fact.getQuarter());
            int qIdx = quartileIndex(quarter);
            if (qIdx >= 0) {
                quartiles[qIdx]++;
            }
            java.util.TreeMap<Integer, Double> series =
                    valueSeries.getOrDefault(entry.getKey(), new java.util.TreeMap<>());
            List<WosCategoryMetricBlock.MetricPoint> trend = new ArrayList<>();
            double sum = 0;
            int count = 0;
            for (Map.Entry<Integer, Double> point : series.entrySet()) {
                trend.add(new WosCategoryMetricBlock.MetricPoint(point.getKey(), point.getValue()));
                sum += point.getValue();
                count++;
            }
            cohort.add(new WosCategoryMetricBlock.Row(
                    entry.getKey(),
                    row != null && row.name() != null && !row.name().isBlank() ? row.name() : entry.getKey(),
                    row != null ? blankToDash(row.issn()) : "—",
                    quarter,
                    fact.getRank(),
                    series.get(referenceYear),
                    count == 0 ? null : sum / count,
                    trend
            ));
        }
        cohort.sort(Comparator
                .comparing(WosCategoryMetricBlock.Row::rank, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WosCategoryMetricBlock.Row::journalName, String.CASE_INSENSITIVE_ORDER));

        List<WosCategoryMetricBlock.FormerMember> former = new ArrayList<>();
        for (Map.Entry<String, WosCategoryFact> entry : lastFactByJournal.entrySet()) {
            if (cohortByJournal.containsKey(entry.getKey())) {
                continue;
            }
            WosCategoryFact fact = entry.getValue();
            RankingRow row = rankingRows.get(entry.getKey());
            former.add(new WosCategoryMetricBlock.FormerMember(
                    entry.getKey(),
                    row != null && row.name() != null && !row.name().isBlank() ? row.name() : entry.getKey(),
                    fact.getYear(),
                    normalizeQuarter(fact.getQuarter()),
                    fact.getRank(),
                    journalsByYear.getOrDefault(fact.getYear(), Set.of()).size()
            ));
        }
        former.sort(Comparator
                .comparing(WosCategoryMetricBlock.FormerMember::lastYear, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WosCategoryMetricBlock.FormerMember::journalName, String.CASE_INSENSITIVE_ORDER));

        return new WosCategoryMetricBlock(
                metric.name(),
                metric == MetricType.AIS ? "Article Influence Score" : "Journal Impact Factor",
                referenceYear,
                globalLatestYear,
                windowFrom,
                referenceYear,
                globalLatestYear != null && referenceYear < globalLatestYear,
                cohort,
                former,
                new WosCategoryMetrics.QuartileSplit(quartiles[0], quartiles[1], quartiles[2], quartiles[3])
        );
    }

    /** Dataset-wide latest fact year per metric — the yardstick for marking a category's block as stale. */
    private Map<MetricType, Integer> loadGlobalLatestYears() {
        Map<MetricType, Integer> out = new EnumMap<>(MetricType.class);
        namedParameterJdbcTemplate.query(
                "SELECT metric_type, MAX(year) AS max_year FROM reporting_read.wos_category_fact GROUP BY metric_type",
                new MapSqlParameterSource(),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String mt = rs.getString("metric_type");
                    Integer year = rs.getObject("max_year", Integer.class);
                    if (mt != null && year != null) {
                        out.put(MetricType.valueOf(mt), year);
                    }
                });
        return out;
    }

    private Map<String, RankingRow> loadRankingRows(Set<String> journalIds) {
        Map<String, RankingRow> rankingRows = new LinkedHashMap<>();
        if (journalIds.isEmpty()) {
            return rankingRows;
        }
        namedParameterJdbcTemplate.query(
                "SELECT journal_id, name, issn, e_issn FROM reporting_read.wos_ranking_view WHERE journal_id IN (:ids)",
                new MapSqlParameterSource("ids", journalIds),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> rankingRows.put(rs.getString("journal_id"), new RankingRow(
                        rs.getString("journal_id"),
                        rs.getString("name"),
                        rs.getString("issn"),
                        rs.getString("e_issn")
                )));
        return rankingRows;
    }

    /** Per-journal metric values inside the block window (year → value), for the value/avg/sparkline columns. */
    private Map<String, java.util.TreeMap<Integer, Double>> loadMetricValues(
            MetricType metric, Set<String> journalIds, int fromYear, int toYear) {
        Map<String, java.util.TreeMap<Integer, Double>> out = new java.util.HashMap<>();
        if (journalIds.isEmpty()) {
            return out;
        }
        namedParameterJdbcTemplate.query(
                """
                SELECT journal_id, year, value
                FROM reporting_read.wos_metric_fact
                WHERE journal_id IN (:ids)
                  AND metric_type = :metric::reporting_read.metric_type_enum
                  AND year BETWEEN :from AND :to
                  AND value IS NOT NULL
                """,
                new MapSqlParameterSource("ids", journalIds)
                        .addValue("metric", metric.name())
                        .addValue("from", fromYear)
                        .addValue("to", toYear),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String jid = rs.getString("journal_id");
                    Integer year = rs.getObject("year", Integer.class);
                    Double value = rs.getObject("value", Double.class);
                    if (jid != null && year != null && value != null) {
                        out.computeIfAbsent(jid, k -> new java.util.TreeMap<>()).put(year, value);
                    }
                });
        return out;
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
                       MAX(avg_value)    FILTER (WHERE metric_type = 'AIS'::reporting_read.metric_type_enum) AS avg_ais,
                       MAX(avg_value)    FILTER (WHERE metric_type = 'IF'::reporting_read.metric_type_enum)  AS avg_if,
                       MAX(median_value) FILTER (WHERE metric_type = 'AIS'::reporting_read.metric_type_enum) AS median_ais,
                       MAX(median_value) FILTER (WHERE metric_type = 'IF'::reporting_read.metric_type_enum)  AS median_if
                FROM reporting_read.wos_category_metric_agg
                WHERE category_name_canonical = :name
                  AND edition_normalized = :edition::reporting_read.edition_normalized_enum
                  AND metric_type IN ('AIS'::reporting_read.metric_type_enum, 'IF'::reporting_read.metric_type_enum)
                GROUP BY year ORDER BY year
                """,
                params,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> trend.add(
                        new WosCategoryMetrics.TrendPoint(rs.getInt("year"),
                                rs.getObject("avg_ais", Double.class), rs.getObject("avg_if", Double.class),
                                rs.getObject("median_ais", Double.class), rs.getObject("median_if", Double.class))));

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

}
