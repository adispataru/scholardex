package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresReportingLookupFacade implements ReportingLookupPort {

    private static final Set<EditionNormalized> OPERATIONAL_EDITIONS = EnumSet.of(
            EditionNormalized.SCIE,
            EditionNormalized.SSCI
    );

    private final CacheService cacheService;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ReportingLookupMemoization reportingLookupMemoization;

    @Override
    public Forum getForum(String forumId) {
        return cacheService.getCachedForums(forumId);
    }

    @Override
    public List<WoSRanking> getRankingsByIssn(String issn) {
        long totalStartedAtNanos = System.nanoTime();
        String normalizedIssn = normalizeIssn(issn);
        if (normalizedIssn == null) {
            return List.of();
        }
        return reportingLookupMemoization.getOrCompute(
                "postgres",
                "rankingsByIssn",
                normalizedIssn,
                () -> loadRankingsByIssn(normalizedIssn, totalStartedAtNanos)
        );
    }

    private List<WoSRanking> loadRankingsByIssn(String normalizedIssn, long totalStartedAtNanos) {

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
    public List<CoreConferenceRanking> getConferenceRankings(String acronym) {
        return cacheService.getCachedConfRankings(acronym);
    }

    @Override
    public int getTopRankings(String categoryIndex, Integer year) {
        if (year == null || categoryIndex == null || categoryIndex.isBlank()) {
            return 0;
        }
        ParsedCategory parsedCategory = parseCategoryIndex(categoryIndex);
        if (parsedCategory.categoryNameCanonical().isBlank()) {
            return 0;
        }
        String editionMemoKey = parsedCategory.editionNormalized() == null
                ? "OPERATIONAL"
                : parsedCategory.editionNormalized().name();
        String memoKey = year + "|" + parsedCategory.categoryNameCanonical() + "|" + editionMemoKey;
        return reportingLookupMemoization.getOrCompute(
                "postgres",
                "topRankings",
                memoKey,
                () -> loadTopRankings(parsedCategory, year)
        );
    }

    private Integer loadTopRankings(ParsedCategory parsedCategory, Integer year) {
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
            params.addValue("edition0", editionNames.get(0));
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

    @Override
    public Set<String> getUniversityAuthorIds() {
        return cacheService.getUniversityAuthorIds();
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

    private WoSRanking toLegacyRanking(
            WosRankingView view,
            List<WosMetricFact> scoreFacts,
            List<WosCategoryFact> categoryFacts
    ) {
        WoSRanking ranking = new WoSRanking();
        ranking.setId(view.getId());
        ranking.setName(view.getName());
        ranking.setIssn(view.getIssn());
        ranking.setEIssn(view.getEIssn());
        ranking.setAlternativeIssns(view.getAlternativeIssns() == null ? List.of() : view.getAlternativeIssns());
        ranking.setAlternativeNames(view.getAlternativeNames() == null ? List.of() : view.getAlternativeNames());

        WoSRanking.Score score = new WoSRanking.Score();
        for (WosMetricFact metricFact : scoreFacts) {
            if (metricFact.getYear() == null || metricFact.getValue() == null || metricFact.getMetricType() == null) {
                continue;
            }
            switch (metricFact.getMetricType()) {
                case AIS -> score.getAis().merge(metricFact.getYear(), metricFact.getValue(), Double::max);
                case RIS -> score.getRis().merge(metricFact.getYear(), metricFact.getValue(), Double::max);
                case IF -> score.getIF().merge(metricFact.getYear(), metricFact.getValue(), Double::max);
            }
        }
        ranking.setScore(score);

        Map<String, WoSRanking.Rank> categoryIndex = new LinkedHashMap<>();
        for (WosCategoryFact categoryFact : categoryFacts) {
            if (categoryFact.getCategoryNameCanonical() == null || categoryFact.getCategoryNameCanonical().isBlank()) {
                continue;
            }
            if (categoryFact.getYear() == null || categoryFact.getMetricType() == null) {
                continue;
            }
            String key = categoryFact.getCategoryNameCanonical() + " - " + categoryFact.getEditionNormalized();
            WoSRanking.Rank rank = categoryIndex.computeIfAbsent(key, ignored -> new WoSRanking.Rank());
            WoSRanking.Quarter quarter = parseQuarter(categoryFact.getQuarter());
            switch (categoryFact.getMetricType()) {
                case AIS -> {
                    if (quarter != null) {
                        rank.getQAis().put(categoryFact.getYear(), quarter);
                    }
                    if (categoryFact.getQuartileRank() != null) {
                        rank.getQuartileRankAis().put(categoryFact.getYear(), categoryFact.getQuartileRank());
                    }
                    if (categoryFact.getRank() != null) {
                        rank.getRankAis().put(categoryFact.getYear(), categoryFact.getRank());
                    }
                }
                case RIS -> {
                    if (quarter != null) {
                        rank.getQRis().put(categoryFact.getYear(), quarter);
                    }
                    if (categoryFact.getQuartileRank() != null) {
                        rank.getQuartileRankRis().put(categoryFact.getYear(), categoryFact.getQuartileRank());
                    }
                    if (categoryFact.getRank() != null) {
                        rank.getRankRis().put(categoryFact.getYear(), categoryFact.getRank());
                    }
                }
                case IF -> {
                    if (quarter != null) {
                        rank.getQIF().put(categoryFact.getYear(), quarter);
                    }
                    if (categoryFact.getQuartileRank() != null) {
                        rank.getQuartileRankIF().put(categoryFact.getYear(), categoryFact.getQuartileRank());
                    }
                    if (categoryFact.getRank() != null) {
                        rank.getRankIF().put(categoryFact.getYear(), categoryFact.getRank());
                    }
                }
            }
        }
        ranking.setWebOfScienceCategoryIndex(categoryIndex);
        return ranking;
    }

    private String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "");
        return normalized.isBlank() ? null : normalized;
    }

    private WoSRanking.Quarter parseQuarter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WoSRanking.Quarter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return WoSRanking.Quarter.NOT_FOUND;
        }
    }

    private ParsedCategory parseCategoryIndex(String categoryIndex) {
        String normalized = categoryIndex == null ? "" : categoryIndex.trim();
        if (normalized.isBlank()) {
            return new ParsedCategory("", null);
        }
        int delimiter = normalized.lastIndexOf('-');
        if (delimiter < 0 || delimiter == normalized.length() - 1) {
            return new ParsedCategory(normalized, null);
        }
        String categoryName = normalized.substring(0, delimiter).trim();
        String editionToken = normalized.substring(delimiter + 1).trim().toUpperCase(Locale.ROOT);
        EditionNormalized edition = null;
        if ("SCIE".equals(editionToken)) {
            edition = EditionNormalized.SCIE;
        } else if ("SSCI".equals(editionToken)) {
            edition = EditionNormalized.SSCI;
        }
        if (categoryName.isBlank()) {
            return new ParsedCategory("", edition);
        }
        return new ParsedCategory(categoryName, edition);
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private record ParsedCategory(String categoryNameCanonical, EditionNormalized editionNormalized) {
    }
}
