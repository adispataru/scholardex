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
                       metric_type, quarter, quartile_rank
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
        List<WosCategoryJournalViewModel> journals = new ArrayList<>();
        Integer latestYear = null;
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
            journals.add(new WosCategoryJournalViewModel(
                    entry.getKey(),
                    name,
                    issn,
                    eIssn,
                    snapshot.latestYear,
                    snapshot.metricQuarter(MetricType.AIS),
                    snapshot.metricQuarter(MetricType.RIS),
                    snapshot.metricQuarter(MetricType.IF)
            ));
        }

        journals.sort(Comparator.comparing(WosCategoryJournalViewModel::journalName, String.CASE_INSENSITIVE_ORDER));
        return Optional.of(new WosCategoryDetailViewModel(
                key,
                categoryName,
                edition.name(),
                journals.size(),
                latestYear,
                journals
        ));
    }

    public WosCategoryPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

        StringBuilder whereClause = new StringBuilder("""
                 WHERE edition_normalized IN ('SCIE', 'SSCI')
                   AND category_name_canonical IS NOT NULL
                   AND category_name_canonical != ''
                   AND journal_id IS NOT NULL
                   AND journal_id != ''
                """);

        if (normalizedQuery != null) {
            whereClause.append(" AND (category_name_canonical ILIKE :q OR edition_normalized::text ILIKE :q)");
            params.addValue("q", "%" + normalizedQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }

        String aggregateSql = """
                SELECT category_name_canonical || ' - ' || edition_normalized AS key,
                       category_name_canonical,
                       edition_normalized,
                       COUNT(DISTINCT journal_id) AS journal_count,
                       MAX(year)                  AS latest_year
                FROM reporting_read.wos_category_fact
                """ + whereClause + """
                GROUP BY category_name_canonical, edition_normalized
                ORDER BY """ + " " + normalizedSort + " " + normalizedDirection + " " + """
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
                        rs.getObject("latest_year", Integer.class)
                )
        );

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
        int totalPages = (int) Math.ceil(total / (double) size);
        int safePage = totalPages == 0 ? 0 : Math.min(page, totalPages - 1);

        return new WosCategoryPageResponse(items, safePage, size, total, totalPages);
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        return switch (normalized) {
            case "categoryName" -> "category_name_canonical";
            case "edition" -> "edition_normalized";
            case "journalCount" -> "journal_count";
            case "latestYear" -> "latest_year";
            default -> throw new IllegalArgumentException("Invalid sort parameter. Allowed: categoryName, edition, journalCount, latestYear.");
        };
    }

    private String normalizeDirection(String direction) {
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("asc") && !normalized.equals("desc")) {
            throw new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc.");
        }
        return normalized.toUpperCase(Locale.ROOT);
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
                snapshot.latestByMetric.put(fact.getMetricType(), new MetricObservation(fact.getYear(), normalizeQuarter(fact.getQuarter())));
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

    private record RankingRow(String journalId, String name, String issn, String eIssn) {}

    private static final class MetricSnapshot {
        private Integer latestYear;
        private final Map<MetricType, MetricObservation> latestByMetric = new EnumMap<>(MetricType.class);

        private String metricQuarter(MetricType metricType) {
            MetricObservation observation = latestByMetric.get(metricType);
            return observation == null ? "—" : observation.quarter();
        }
    }

    private record MetricObservation(Integer year, String quarter) {}
}
