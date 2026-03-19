package ro.uvt.pokedex.core.service.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.service.CacheService;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresReportingLookupFacade extends AbstractReportingLookupFacade {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public PostgresReportingLookupFacade(
            CacheService cacheService,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ReportingLookupMemoization reportingLookupMemoization) {
        super(cacheService, reportingLookupMemoization);
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    protected String memoBackendKey() {
        return "postgres";
    }

    @Override
    protected List<WoSRanking> loadRankingsByIssn(String normalizedIssn) {

        long totalStartedAtNanos = System.nanoTime();
        long rankingLookupStartedAtNanos = System.nanoTime();
        List<WosRankingView> views = namedParameterJdbcTemplate.query(
                """
                        (
                            SELECT journal_id, name, issn, e_issn, alternative_issns, alternative_names
                            FROM reporting_read.wos_ranking_view
                            WHERE issn_norm = :issn
                        )
                        UNION
                        (
                            SELECT journal_id, name, issn, e_issn, alternative_issns, alternative_names
                            FROM reporting_read.wos_ranking_view
                            WHERE e_issn_norm = :issn
                        )
                        UNION
                        (
                            SELECT journal_id, name, issn, e_issn, alternative_issns, alternative_names
                            FROM reporting_read.wos_ranking_view
                            WHERE alternative_issns_norm @> ARRAY[:issn]::text[]
                        )
                        """,
                new MapSqlParameterSource("issn", normalizedIssn),
                this::mapRankingView
        );
        long rankingLookupMs = nanosToMillis(System.nanoTime() - rankingLookupStartedAtNanos);

        if (views.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "WoS ISSN lookup timings [issn={}]: rankingLookupMs={}, metricLoadMs=0, categoryLoadMs=0, assemblyMs=0, totalMs={}",
                        normalizedIssn,
                        rankingLookupMs,
                        nanosToMillis(System.nanoTime() - totalStartedAtNanos)
                );
            }
            return List.of();
        }

        List<String> journalIds = views.stream().map(WosRankingView::getId).toList();

        long metricLoadStartedAtNanos = System.nanoTime();
        List<WosMetricFact> metricFacts = namedParameterJdbcTemplate.query(
                """
                        SELECT journal_id, year, metric_type, value
                        FROM reporting_read.wos_metric_fact
                        WHERE journal_id IN (:journalIds)
                        """,
                new MapSqlParameterSource("journalIds", journalIds),
                (rs, rowNum) -> {
                    WosMetricFact fact = new WosMetricFact();
                    fact.setJournalId(rs.getString("journal_id"));
                    fact.setYear(rs.getObject("year", Integer.class));
                    String metricType = rs.getString("metric_type");
                    if (metricType != null) {
                        fact.setMetricType(MetricType.valueOf(metricType));
                    }
                    fact.setValue(rs.getObject("value", Double.class));
                    return fact;
                }
        );
        long metricLoadMs = nanosToMillis(System.nanoTime() - metricLoadStartedAtNanos);

        long categoryLoadStartedAtNanos = System.nanoTime();
        List<WosCategoryFact> categoryFacts = namedParameterJdbcTemplate.query(
                """
                        SELECT journal_id, year, category_name_canonical, edition_normalized,
                               metric_type, quarter, quartile_rank, "rank" AS rank_value
                        FROM reporting_read.wos_category_fact
                        WHERE journal_id IN (:journalIds)
                          AND edition_normalized IN ('SCIE', 'SSCI')
                        """,
                new MapSqlParameterSource().addValue("journalIds", journalIds),
                (rs, rowNum) -> {
                    WosCategoryFact fact = new WosCategoryFact();
                    fact.setJournalId(rs.getString("journal_id"));
                    fact.setYear(rs.getObject("year", Integer.class));
                    fact.setCategoryNameCanonical(rs.getString("category_name_canonical"));
                    String edition = rs.getString("edition_normalized");
                    if (edition != null) {
                        fact.setEditionNormalized(EditionNormalized.valueOf(edition));
                    }
                    String metricType = rs.getString("metric_type");
                    if (metricType != null) {
                        fact.setMetricType(MetricType.valueOf(metricType));
                    }
                    fact.setQuarter(rs.getString("quarter"));
                    fact.setQuartileRank(rs.getObject("quartile_rank", Integer.class));
                    fact.setRank(rs.getObject("rank_value", Integer.class));
                    return fact;
                }
        );
        long categoryLoadMs = nanosToMillis(System.nanoTime() - categoryLoadStartedAtNanos);

        long assemblyStartedAtNanos = System.nanoTime();
        Map<String, List<WosMetricFact>> scoresByJournal = new HashMap<>();
        for (WosMetricFact metricFact : metricFacts) {
            scoresByJournal.computeIfAbsent(metricFact.getJournalId(), ignored -> new ArrayList<>()).add(metricFact);
        }

        Map<String, List<WosCategoryFact>> categoriesByJournal = new HashMap<>();
        for (WosCategoryFact categoryFact : categoryFacts) {
            categoriesByJournal.computeIfAbsent(categoryFact.getJournalId(), ignored -> new ArrayList<>()).add(categoryFact);
        }

        List<WoSRanking> rankings = new ArrayList<>();
        for (WosRankingView view : views) {
            List<WosMetricFact> journalScores = scoresByJournal.getOrDefault(view.getId(), List.of());
            List<WosCategoryFact> journalCategories = categoriesByJournal.getOrDefault(view.getId(), List.of());
            if (journalScores.isEmpty() && journalCategories.isEmpty()) {
                continue;
            }
            rankings.add(toLegacyRanking(view, journalScores, journalCategories));
        }
        long assemblyMs = nanosToMillis(System.nanoTime() - assemblyStartedAtNanos);
        if (log.isDebugEnabled()) {
            log.debug(
                    "WoS ISSN lookup timings [issn={}, journals={}]: rankingLookupMs={}, metricLoadMs={}, categoryLoadMs={}, assemblyMs={}, totalMs={}",
                    normalizedIssn,
                    journalIds.size(),
                    rankingLookupMs,
                    metricLoadMs,
                    categoryLoadMs,
                    assemblyMs,
                    nanosToMillis(System.nanoTime() - totalStartedAtNanos)
            );
        }
        return rankings;
    }

    @Override
    protected Integer loadTopRankings(ParsedCategory parsedCategory, Integer year) {
        Set<EditionNormalized> editions = parsedCategory.editionNormalized() == null
                ? OPERATIONAL_EDITIONS
                : Set.of(parsedCategory.editionNormalized());

        List<String> editionNames = editions.stream().map(Enum::name).sorted().toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("year", year)
                .addValue("category", parsedCategory.categoryNameCanonical());
        String editionPredicate;
        if (editionNames.size() == 1) {
            params.addValue("edition0", editionNames.getFirst());
            editionPredicate = "edition_normalized = CAST(:edition0 AS reporting_read.edition_normalized_enum)";
        } else {
            params.addValue("edition0", editionNames.getFirst());
            params.addValue("edition1", editionNames.get(1));
            editionPredicate = """
                    edition_normalized IN (
                        CAST(:edition0 AS reporting_read.edition_normalized_enum),
                        CAST(:edition1 AS reporting_read.edition_normalized_enum)
                    )
                    """;
        }

        String sql = """
                SELECT COALESCE(SUM(top_journal_count), 0)
                FROM reporting_read.mv_wos_top_rankings_q1_ais
                WHERE year = :year
                  AND category_name_canonical = :category
                  AND %s
                """.formatted(editionPredicate);

        Integer count = namedParameterJdbcTemplate.queryForObject(sql, params, Integer.class);
        return count == null ? 0 : count;
    }

    private WosRankingView mapRankingView(ResultSet rs, int ignored) throws SQLException {
        WosRankingView view = new WosRankingView();
        view.setId(rs.getString("journal_id"));
        view.setName(rs.getString("name"));
        view.setIssn(rs.getString("issn"));
        view.setEIssn(rs.getString("e_issn"));
        view.setAlternativeIssns(toStringList(rs.getArray("alternative_issns")));
        view.setAlternativeNames(toStringList(rs.getArray("alternative_names")));
        return view;
    }

    private List<String> toStringList(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object value = sqlArray.getArray();
        if (value instanceof String[] items) {
            return List.of(items);
        }
        return List.of();
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
