package ro.uvt.pokedex.core.service.application;

import lombok.extern.slf4j.Slf4j;
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
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
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
@Slf4j
public class PostgresReportingLookupFacade implements ReportingLookupPort {

    private static final Set<EditionNormalized> OPERATIONAL_EDITIONS = EnumSet.of(
            EditionNormalized.SCIE,
            EditionNormalized.SSCI
    );

    private final CacheService cacheService;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ReportingLookupMemoization reportingLookupMemoization;
    private final WosForumResolutionService wosForumResolutionService;
    /** Lazily-built name/ISSN → journalId index reused across forum resolutions (same staleness class
     *  as the memoized ranking lookups). */
    private volatile WosForumResolutionService.ResolutionIndex resolutionIndex;

    public PostgresReportingLookupFacade(
            CacheService cacheService,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ReportingLookupMemoization reportingLookupMemoization,
            WosForumResolutionService wosForumResolutionService) {
        this.cacheService = cacheService;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.reportingLookupMemoization = reportingLookupMemoization;
        this.wosForumResolutionService = wosForumResolutionService;
    }

    @Override
    public boolean isForumInScopus(String forumId) {
        return isForumInDatabase(forumId, "SCOPUS");
    }

    @Override
    public boolean isForumInEsci(String forumId) {
        return isForumInDatabase(forumId, "ESCI");
    }

    @Override
    public boolean isForumInAhci(String forumId) {
        return isForumInDatabase(forumId, "AHCI");
    }

    private boolean isForumInDatabase(String forumId, String database) {
        if (forumId == null || forumId.isBlank()) {
            return false;
        }
        Boolean exists = namedParameterJdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reporting_read.scholardex_forum_membership_view "
                        + "WHERE forum_id = :forumId AND database = :database)",
                new MapSqlParameterSource("forumId", forumId).addValue("database", database),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<WoSRanking> getRankingsByForum(ScholardexForumView forum) {
        if (forum == null) {
            return List.of();
        }
        // H66 B3: read the forum-keyed ranking views by the canonical forum id (the wosForumIds FK join built
        // by the B2 projection). This is the stored-FK replacement for the fuzzy ISSN/name resolution below.
        String forumId = forum.getId();
        if (forumId != null && !forumId.isBlank()) {
            List<WoSRanking> byForum = reportingLookupMemoization.getOrCompute(
                    "postgres",
                    "rankingsByForumId",
                    forumId,
                    () -> loadRankingsByForumId(forum)
            );
            if (!byForum.isEmpty()) {
                return byForum;
            }
        }
        // Fallback (logged, should trend to zero once every WoS-indexed forum is projected): the legacy fuzzy
        // ISSN/name resolution, for forums not present in the forum-keyed views yet.
        List<WoSRanking> rankings = getRankingsByIssn(forum.getIssn());
        if (rankings.isEmpty()) {
            rankings = getRankingsByIssn(forum.getEIssn());
        }
        if (!rankings.isEmpty()) {
            log.debug("getRankingsByForum: forum-keyed views empty, fell back to ISSN resolution forumId={} issn={}",
                    forumId, forum.getIssn());
            return rankings;
        }
        String journalId = wosForumResolutionService.resolveJournalId(forum, resolutionIndex());
        if (journalId == null || journalId.isBlank()) {
            return List.of();
        }
        log.debug("getRankingsByForum: forum-keyed views empty, fell back to name resolution forumId={} journalId={}",
                forumId, journalId);
        return reportingLookupMemoization.getOrCompute(
                "postgres",
                "rankingsByJournalId",
                journalId,
                () -> loadRankingsByJournalId(journalId)
        );
    }

    private WosForumResolutionService.ResolutionIndex resolutionIndex() {
        WosForumResolutionService.ResolutionIndex cached = resolutionIndex;
        if (cached == null) {
            synchronized (this) {
                cached = resolutionIndex;
                if (cached == null) {
                    cached = wosForumResolutionService.buildResolutionIndex();
                    resolutionIndex = cached;
                }
            }
        }
        return cached;
    }

    @Override
    public ScholardexForumView getForum(String forumId) {
        if (forumId == null || forumId.isBlank()) {
            return null;
        }
        return cacheService.getCachedForums(forumId);
    }

    @Override
    public List<WoSRanking> getRankingsByIssn(String issn) {
        String normalizedIssn = QueryNormalizationSupport.normalizeIssn(issn);
        if (normalizedIssn == null) {
            return List.of();
        }
        return reportingLookupMemoization.getOrCompute(
                "postgres",
                "rankingsByIssn",
                normalizedIssn,
                () -> loadRankingsByIssn(normalizedIssn)
        );
    }

    @Override
    public List<CoreConferenceRanking> getConferenceRankings(String acronym) {
        return cacheService.getCachedConfRankings(acronym);
    }

    @Override
    public List<CoreConferenceRanking> getConferenceRankingsByNormalizedTitle(String normalizedTitle) {
        return cacheService.getCachedConfRankingsByNormalizedTitle(normalizedTitle);
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

    @Override
    public Set<String> getUniversityAuthorIds() {
        return cacheService.getUniversityAuthorIds();
    }

    private List<WoSRanking> loadRankingsByIssn(String normalizedIssn) {
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
        return assembleRankings(views);
    }

    private List<WoSRanking> loadRankingsByJournalId(String journalId) {
        List<WosRankingView> views = namedParameterJdbcTemplate.query(
                """
                        SELECT journal_id, name, issn, e_issn, alternative_issns, alternative_names
                        FROM reporting_read.wos_ranking_view
                        WHERE journal_id = :journalId
                        """,
                new MapSqlParameterSource("journalId", journalId),
                this::mapRankingView
        );
        return assembleRankings(views);
    }

    /**
     * H66 B3: assemble a single forum-level {@link WoSRanking} from the forum-keyed projection views
     * (scholardex_forum_metric_view / scholardex_forum_category_view), keyed by the canonical forum id —
     * the stored-FK replacement for the fuzzy ISSN/name → journalId resolution. Returns empty when the forum
     * has no projected rankings (caller then falls back to the legacy resolution).
     */
    private List<WoSRanking> loadRankingsByForumId(ScholardexForumView forum) {
        String forumId = forum.getId();
        List<WosMetricFact> metricFacts = namedParameterJdbcTemplate.query(
                """
                        SELECT year, metric_type, value
                        FROM reporting_read.scholardex_forum_metric_view
                        WHERE forum_id = :forumId
                        """,
                new MapSqlParameterSource("forumId", forumId),
                (rs, rowNum) -> {
                    WosMetricFact fact = new WosMetricFact();
                    fact.setJournalId(forumId);
                    fact.setYear(rs.getObject("year", Integer.class));
                    String metricType = rs.getString("metric_type");
                    if (metricType != null) {
                        fact.setMetricType(MetricType.valueOf(metricType));
                    }
                    fact.setValue(rs.getObject("value", Double.class));
                    return fact;
                }
        );

        List<WosCategoryFact> categoryFacts = namedParameterJdbcTemplate.query(
                """
                        SELECT year, category, edition, metric_type, quarter, quartile_rank, "rank" AS rank_value
                        FROM reporting_read.scholardex_forum_category_view
                        WHERE forum_id = :forumId
                          AND edition IN ('SCIE', 'SSCI')
                        """,
                new MapSqlParameterSource("forumId", forumId),
                (rs, rowNum) -> {
                    WosCategoryFact fact = new WosCategoryFact();
                    fact.setJournalId(forumId);
                    fact.setYear(rs.getObject("year", Integer.class));
                    fact.setCategoryNameCanonical(rs.getString("category"));
                    String edition = rs.getString("edition");
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

        if (metricFacts.isEmpty() && categoryFacts.isEmpty()) {
            return List.of();
        }

        // Adapt the forum to the view shape toLegacyRanking expects (id/name/issn/eIssn carry the identity).
        WosRankingView view = new WosRankingView();
        view.setId(forumId);
        view.setName(forum.getPublicationName());
        view.setIssn(forum.getIssn());
        view.setEIssn(forum.getEIssn());
        view.setAlternativeIssns(List.of());
        view.setAlternativeNames(List.of());
        return List.of(toLegacyRanking(view, metricFacts, categoryFacts));
    }

    private List<WoSRanking> assembleRankings(List<WosRankingView> views) {
        if (views.isEmpty()) {
            return List.of();
        }

        List<String> journalIds = views.stream().map(WosRankingView::getId).toList();

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
        return rankings;
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
        EditionNormalized edition = parseEdition(normalized.substring(delimiter + 1));
        if (categoryName.isBlank()) {
            return new ParsedCategory("", edition);
        }
        return new ParsedCategory(categoryName, edition);
    }

    private EditionNormalized parseEdition(String token) {
        String editionToken = token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
        return switch (editionToken) {
            case "SCIE" -> EditionNormalized.SCIE;
            case "SSCI" -> EditionNormalized.SSCI;
            default -> null;
        };
    }

    private record ParsedCategory(String categoryNameCanonical, EditionNormalized editionNormalized) {
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
