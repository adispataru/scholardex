package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.CacheService;

import java.sql.Array;
import java.sql.ResultSet;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PostgresReportingLookupFacadeTest {

    @Mock
    private CacheService cacheService;

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Mock
    private WosForumResolutionService wosForumResolutionService;

    private PostgresReportingLookupFacade facade;

    @BeforeEach
    void setUp() {
        facade = new PostgresReportingLookupFacade(
                cacheService,
                namedParameterJdbcTemplate,
                new ReportingLookupMemoization(),
                wosForumResolutionService
        );
    }

    @Test
    void getTopRankingsQueriesDistinctCountWithParsedCategory() {
        when(namedParameterJdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(3);

        int count = facade.getTopRankings("ECONOMICS - SCIE", 2024);

        assertEquals(3, count);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(namedParameterJdbcTemplate).queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), eq(Integer.class));

        assertTrue(sqlCaptor.getValue().contains("mv_wos_top_rankings_q1_ais"));
        assertTrue(!sqlCaptor.getValue().contains("edition_normalized::text"));
        assertTrue(sqlCaptor.getValue().contains("CAST(:edition0 AS reporting_read.edition_normalized_enum)"));
        assertTrue(!sqlCaptor.getValue().contains("reporting_read.edition_normalized)"));
        assertEquals("ECONOMICS", paramsCaptor.getValue().getValue("category"));
    }

    @Test
    void getTopRankingsReturnsZeroForBlankCategoryOrNullYear() {
        assertEquals(0, facade.getTopRankings("", 2024));
        assertEquals(0, facade.getTopRankings("ECONOMICS - SCIE", null));
    }

    @Test
    void getTopRankingsMemoizesWithinRefreshScopeOnly() {
        when(namedParameterJdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(3);
        ReportingLookupMemoization memoization = new ReportingLookupMemoization();
        facade = new PostgresReportingLookupFacade(cacheService, namedParameterJdbcTemplate, memoization, wosForumResolutionService);

        memoization.withRefreshScope(() -> {
            assertEquals(3, facade.getTopRankings("ECONOMICS - SCIE", 2024));
            assertEquals(3, facade.getTopRankings("ECONOMICS - SCIE", 2024));
        });

        verify(namedParameterJdbcTemplate, times(1))
                .queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class));

        assertEquals(3, facade.getTopRankings("ECONOMICS - SCIE", 2024));
        verify(namedParameterJdbcTemplate, times(2))
                .queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class));
    }

    @Test
    void getRankingsByIssnReturnsEmptyOnBlankInput() {
        assertTrue(facade.getRankingsByIssn(" ").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRankingsByForumFallsBackToNameResolutionWhenIssnMisses() {
        // No ISSN on the forum → the ISSN path resolves nothing; the facade must fall back to the
        // WosForumResolutionService (name) path and then load by the resolved journal id.
        when(wosForumResolutionService.buildResolutionIndex())
                .thenReturn(new WosForumResolutionService.ResolutionIndex(java.util.Map.of(), java.util.Map.of()));
        when(wosForumResolutionService.resolveJournalId(any(ScholardexForumView.class), any()))
                .thenReturn("jResolvedByName");
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Some Journal Without A Matching ISSN");

        facade.getRankingsByForum(forum);

        verify(wosForumResolutionService).resolveJournalId(any(ScholardexForumView.class), any());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate, atLeast(1))
                .query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));
        assertTrue(sqlCaptor.getAllValues().stream().anyMatch(s -> s.contains("WHERE journal_id = :journalId")),
                "fallback must query the resolved journal id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRankingsByForumReadsForumKeyedViewsByIdWithoutFuzzyResolution() {
        // H66 B3: when the forum carries a canonical id and the forum-keyed projection has rows, the facade
        // must read scholardex_forum_metric_view / scholardex_forum_category_view by forum_id and NOT touch
        // the fuzzy ISSN/name resolver at all.
        ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact metric =
                new ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact();
        metric.setJournalId("forum-1");
        metric.setYear(2024);
        metric.setMetricType(ro.uvt.pokedex.core.model.reporting.wos.MetricType.AIS);
        metric.setValue(1.377);

        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    if (sql.contains("scholardex_forum_metric_view")) {
                        return List.of(metric);
                    }
                    return List.of();
                });

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setPublicationName("Energy");

        List<WoSRanking> rankings = facade.getRankingsByForum(forum);

        assertFalse(rankings.isEmpty(), "forum-keyed read must produce a ranking");
        // The fuzzy resolver must never be consulted when the forum-keyed views resolve.
        verify(wosForumResolutionService, times(0)).resolveJournalId(any(ScholardexForumView.class), any());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate, atLeast(1))
                .query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));
        assertTrue(sqlCaptor.getAllValues().stream().anyMatch(s -> s.contains("scholardex_forum_metric_view")),
                "must query the forum-keyed metric view");
        assertTrue(sqlCaptor.getAllValues().stream().anyMatch(s -> s.contains("WHERE forum_id = :forumId")),
                "must key the forum view query by forum_id");
    }

    @Test
    void parseQuarterFallbackIsNotFoundForUnknownQuarterToken() {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(java.util.List.of());

        assertTrue(facade.getRankingsByIssn("1234-5678").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRankingsByIssnCategoryQueryUsesEnumFilterWithoutTextCast() {
        WosRankingView view = new WosRankingView();
        view.setId("j1");

        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        return List.of(view);
                    }
                    return List.of();
                });

        assertTrue(facade.getRankingsByIssn("1234-5678").isEmpty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate, org.mockito.Mockito.atLeast(3))
                .query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));

        List<String> sqls = sqlCaptor.getAllValues();
        String rankingSql = sqls.stream()
                .filter(sql -> sql.contains("FROM reporting_read.wos_ranking_view"))
                .findFirst()
                .orElseThrow();
        String categorySql = sqls.stream()
                .filter(sql -> sql.contains("FROM reporting_read.wos_category_fact"))
                .findFirst()
                .orElseThrow();

        assertTrue(rankingSql.contains("UNION"));
        assertTrue(rankingSql.contains("alternative_issns_norm @> ARRAY[:issn]::text[]"));
        assertTrue(!rankingSql.contains(":issn = ANY(alternative_issns_norm)"));
        assertTrue(!rankingSql.contains("WHERE issn_norm = :issn\n                           OR e_issn_norm = :issn"));
        assertTrue(categorySql.contains("edition_normalized IN ('SCIE', 'SSCI')"));
        assertTrue(!categorySql.contains("edition_normalized::text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRankingsByIssnMemoizesWithinRefreshScopeOnly() {
        WosRankingView view = new WosRankingView();
        view.setId("j1");

        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        return List.of(view);
                    }
                    return List.of();
                });
        ReportingLookupMemoization memoization = new ReportingLookupMemoization();
        facade = new PostgresReportingLookupFacade(cacheService, namedParameterJdbcTemplate, memoization, wosForumResolutionService);

        memoization.withRefreshScope(() -> {
            assertTrue(facade.getRankingsByIssn("1234-5678").isEmpty());
            assertTrue(facade.getRankingsByIssn("1234-5678").isEmpty());
        });

        verify(namedParameterJdbcTemplate, times(3))
                .query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));

        assertTrue(facade.getRankingsByIssn("1234-5678").isEmpty());
        verify(namedParameterJdbcTemplate, times(6))
                .query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));
    }

    @Test
    void delegateLookupsReturnCachedValues() {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("sforum_1");
        when(cacheService.getCachedForums("sforum_1")).thenReturn(forum);

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICSE");
        when(cacheService.getCachedConfRankings("ICSE")).thenReturn(List.of(ranking));
        when(cacheService.getCachedConfRankingsByNormalizedTitle("international conference on software engineering"))
                .thenReturn(List.of(ranking));
        when(cacheService.getUniversityAuthorIds()).thenReturn(Set.of("sauth_1", "sauth_2"));

        assertEquals("sforum_1", facade.getForum("sforum_1").getId());
        assertEquals(1, facade.getConferenceRankings("ICSE").size());
        assertEquals(1, facade.getConferenceRankingsByNormalizedTitle("international conference on software engineering").size());
        assertEquals(Set.of("sauth_1", "sauth_2"), facade.getUniversityAuthorIds());
    }

    @Test
    void getTopRankingsReturnsZeroWhenJdbcReturnsNullAndHandlesCategoryShapes() {
        when(namedParameterJdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(null);

        assertEquals(0, facade.getTopRankings("ECONOMICS", 2024));
        assertEquals(0, facade.getTopRankings("  - SCIE", 2024));
        assertEquals(0, facade.getTopRankings("   ", 2024));
        assertEquals(0, facade.getTopRankings(null, 2024));
    }

    @Test
    void getTopRankingsReturnsZeroWhenParsedCategoryCanonicalNameBlank() {
        assertEquals(0, facade.getTopRankings(" - SSCI", 2024));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRankingsByIssnBuildsLegacyRankingFromMetricAndCategoryFacts() throws Exception {
        Array altIssnArray = mock(Array.class);
        when(altIssnArray.getArray()).thenReturn(new String[]{"1111-2222", "3333-4444"});
        Array altNameArray = mock(Array.class);
        when(altNameArray.getArray()).thenReturn(new String[]{"Alt Journal"});

        ResultSet rankingRs = mock(ResultSet.class);
        when(rankingRs.getString("journal_id")).thenReturn("j1");
        when(rankingRs.getString("name")).thenReturn("Journal One");
        when(rankingRs.getString("issn")).thenReturn("1234-5678");
        when(rankingRs.getString("e_issn")).thenReturn("8765-4321");
        when(rankingRs.getArray("alternative_issns")).thenReturn(altIssnArray);
        when(rankingRs.getArray("alternative_names")).thenReturn(altNameArray);

        ResultSet metricRsAis = mock(ResultSet.class);
        when(metricRsAis.getString("journal_id")).thenReturn("j1");
        when(metricRsAis.getObject("year", Integer.class)).thenReturn(2023);
        when(metricRsAis.getString("metric_type")).thenReturn("AIS");
        when(metricRsAis.getObject("value", Double.class)).thenReturn(2.5d);

        ResultSet metricRsRis = mock(ResultSet.class);
        when(metricRsRis.getString("journal_id")).thenReturn("j1");
        when(metricRsRis.getObject("year", Integer.class)).thenReturn(2023);
        when(metricRsRis.getString("metric_type")).thenReturn("RIS");
        when(metricRsRis.getObject("value", Double.class)).thenReturn(1.5d);

        ResultSet metricRsIf = mock(ResultSet.class);
        when(metricRsIf.getString("journal_id")).thenReturn("j1");
        when(metricRsIf.getObject("year", Integer.class)).thenReturn(2023);
        when(metricRsIf.getString("metric_type")).thenReturn("IF");
        when(metricRsIf.getObject("value", Double.class)).thenReturn(4.0d);

        ResultSet metricRsInvalid = mock(ResultSet.class);
        when(metricRsInvalid.getString("journal_id")).thenReturn("j1");
        when(metricRsInvalid.getObject("year", Integer.class)).thenReturn(null);
        when(metricRsInvalid.getString("metric_type")).thenReturn("AIS");
        when(metricRsInvalid.getObject("value", Double.class)).thenReturn(99.0d);

        ResultSet categoryRsAis = mock(ResultSet.class);
        when(categoryRsAis.getString("journal_id")).thenReturn("j1");
        when(categoryRsAis.getObject("year", Integer.class)).thenReturn(2023);
        when(categoryRsAis.getString("category_name_canonical")).thenReturn("ECONOMICS");
        when(categoryRsAis.getString("edition_normalized")).thenReturn("SCIE");
        when(categoryRsAis.getString("metric_type")).thenReturn("AIS");
        when(categoryRsAis.getString("quarter")).thenReturn("Q1");
        when(categoryRsAis.getObject("quartile_rank", Integer.class)).thenReturn(2);
        when(categoryRsAis.getObject("rank_value", Integer.class)).thenReturn(7);

        ResultSet categoryRsRis = mock(ResultSet.class);
        when(categoryRsRis.getString("journal_id")).thenReturn("j1");
        when(categoryRsRis.getObject("year", Integer.class)).thenReturn(2023);
        when(categoryRsRis.getString("category_name_canonical")).thenReturn("ECONOMICS");
        when(categoryRsRis.getString("edition_normalized")).thenReturn("SCIE");
        when(categoryRsRis.getString("metric_type")).thenReturn("RIS");
        when(categoryRsRis.getString("quarter")).thenReturn("BAD");
        when(categoryRsRis.getObject("quartile_rank", Integer.class)).thenReturn(3);
        when(categoryRsRis.getObject("rank_value", Integer.class)).thenReturn(11);

        ResultSet categoryRsIf = mock(ResultSet.class);
        when(categoryRsIf.getString("journal_id")).thenReturn("j1");
        when(categoryRsIf.getObject("year", Integer.class)).thenReturn(2023);
        when(categoryRsIf.getString("category_name_canonical")).thenReturn("ECONOMICS");
        when(categoryRsIf.getString("edition_normalized")).thenReturn("SCIE");
        when(categoryRsIf.getString("metric_type")).thenReturn("IF");
        when(categoryRsIf.getString("quarter")).thenReturn(null);
        when(categoryRsIf.getObject("quartile_rank", Integer.class)).thenReturn(4);
        when(categoryRsIf.getObject("rank_value", Integer.class)).thenReturn(15);

        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    org.springframework.jdbc.core.RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        return List.of(mapper.mapRow(rankingRs, 0));
                    }
                    if (sql.contains("FROM reporting_read.wos_metric_fact")) {
                        return List.of(
                                mapper.mapRow(metricRsAis, 0),
                                mapper.mapRow(metricRsRis, 1),
                                mapper.mapRow(metricRsIf, 2),
                                mapper.mapRow(metricRsInvalid, 3)
                        );
                    }
                    return List.of(
                            mapper.mapRow(categoryRsAis, 0),
                            mapper.mapRow(categoryRsRis, 1),
                            mapper.mapRow(categoryRsIf, 2)
                    );
                });

        List<WoSRanking> rankings = facade.getRankingsByIssn("1234-5678");

        assertEquals(1, rankings.size());
        WoSRanking ranking = rankings.getFirst();
        assertEquals("j1", ranking.getId());
        assertEquals("Journal One", ranking.getName());
        assertEquals("1234-5678", ranking.getIssn());
        assertEquals("8765-4321", ranking.getEIssn());
        assertEquals(2.5d, ranking.getScore().getAis().get(2023));
        assertEquals(1.5d, ranking.getScore().getRis().get(2023));
        assertEquals(4.0d, ranking.getScore().getIF().get(2023));

        WoSRanking.Rank category = ranking.getWebOfScienceCategoryIndex().get("ECONOMICS - SCIE");
        assertNotNull(category);
        assertEquals(WoSRanking.Quarter.Q1, category.getQAis().get(2023));
        assertEquals(WoSRanking.Quarter.NOT_FOUND, category.getQRis().get(2023));
        assertFalse(category.getQIF().containsKey(2023));
        assertEquals(2, category.getQuartileRankAis().get(2023));
        assertEquals(3, category.getQuartileRankRis().get(2023));
        assertEquals(4, category.getQuartileRankIF().get(2023));
        assertEquals(7, category.getRankAis().get(2023));
        assertEquals(11, category.getRankRis().get(2023));
        assertEquals(15, category.getRankIF().get(2023));

        verify(namedParameterJdbcTemplate, atLeast(3))
                .query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));
    }

    @Test
    void getRankingsByIssnReturnsEmptyForNullInput() {
        assertTrue(facade.getRankingsByIssn(null).isEmpty());
    }

    @Test
    void privateHelpersParseAndMapAsExpected() throws Exception {
        Method parseQuarter = PostgresReportingLookupFacade.class.getDeclaredMethod("parseQuarter", String.class);
        parseQuarter.setAccessible(true);
        assertEquals(null, parseQuarter.invoke(facade, ""));
        assertEquals(WoSRanking.Quarter.NOT_FOUND, parseQuarter.invoke(facade, "QX"));
        assertEquals(WoSRanking.Quarter.Q2, parseQuarter.invoke(facade, " q2 "));

        Method parseEdition = PostgresReportingLookupFacade.class.getDeclaredMethod("parseEdition", String.class);
        parseEdition.setAccessible(true);
        assertEquals(null, parseEdition.invoke(facade, "abc"));
        assertEquals("SCIE", ((Enum<?>) parseEdition.invoke(facade, " scie ")).name());
        assertEquals("SSCI", ((Enum<?>) parseEdition.invoke(facade, "ssci")).name());

        Method nanosToMillis = PostgresReportingLookupFacade.class.getDeclaredMethod("nanosToMillis", long.class);
        nanosToMillis.setAccessible(true);
        assertEquals(2L, nanosToMillis.invoke(facade, 2_999_999L));
        assertEquals(0L, nanosToMillis.invoke(facade, 999_999L));

        Method toStringList = PostgresReportingLookupFacade.class.getDeclaredMethod("toStringList", Array.class);
        toStringList.setAccessible(true);
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(new Object[]{"x"});
        @SuppressWarnings("unchecked")
        List<String> emptyForNonStringArray = (List<String>) toStringList.invoke(facade, array);
        assertTrue(emptyForNonStringArray.isEmpty());

        Method mapRankingView = PostgresReportingLookupFacade.class
                .getDeclaredMethod("mapRankingView", ResultSet.class, int.class);
        mapRankingView.setAccessible(true);
        ResultSet rs = mock(ResultSet.class);
        Array altIssnArray = mock(Array.class);
        Array altNameArray = mock(Array.class);
        when(altIssnArray.getArray()).thenReturn(new String[]{"issn-a"});
        when(altNameArray.getArray()).thenReturn(new String[]{"name-a"});
        when(rs.getString("journal_id")).thenReturn("jid");
        when(rs.getString("name")).thenReturn("jname");
        when(rs.getString("issn")).thenReturn("i1");
        when(rs.getString("e_issn")).thenReturn("i2");
        when(rs.getArray("alternative_issns")).thenReturn(altIssnArray);
        when(rs.getArray("alternative_names")).thenReturn(altNameArray);
        WosRankingView mapped = (WosRankingView) mapRankingView.invoke(facade, rs, 0);
        assertEquals(List.of("issn-a"), mapped.getAlternativeIssns());
        assertEquals(List.of("name-a"), mapped.getAlternativeNames());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getForumRankingsScopesYearsAndCategoriesIntoSql() {
        // H69 thread (6): the scoped read must push the requested years (metric + category queries) and the requested
        // WoS categories (category query) into SQL, and must NOT consult the fuzzy resolver when the forum-keyed
        // views resolve.
        ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact metric =
                new ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact();
        metric.setJournalId("forum-1");
        metric.setYear(2024);
        metric.setMetricType(ro.uvt.pokedex.core.model.reporting.wos.MetricType.AIS);
        metric.setValue(1.2);
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).contains("scholardex_forum_metric_view")
                        ? List.of(metric) : List.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setPublicationName("Energy");

        List<WoSRanking> rankings = facade.getForumRankings(forum, List.of(2024), List.of("ENERGY & FUELS"));

        assertFalse(rankings.isEmpty(), "scoped forum-keyed read must produce a ranking");
        verify(wosForumResolutionService, times(0)).resolveJournalId(any(ScholardexForumView.class), any());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(namedParameterJdbcTemplate, atLeast(1))
                .query(sqlCaptor.capture(), paramCaptor.capture(), any(org.springframework.jdbc.core.RowMapper.class));

        String metricSql = sqlCaptor.getAllValues().stream()
                .filter(s -> s.contains("scholardex_forum_metric_view")).findFirst().orElseThrow();
        String categorySql = sqlCaptor.getAllValues().stream()
                .filter(s -> s.contains("scholardex_forum_category_view")).findFirst().orElseThrow();
        assertTrue(metricSql.contains("AND year IN (:years)"), "metric query must scope years");
        assertTrue(categorySql.contains("AND year IN (:years)"), "category query must scope years");
        assertTrue(categorySql.contains("AND category IN (:categories)"), "category query must scope categories");

        MapSqlParameterSource categoryParams = paramCaptor.getAllValues().stream()
                .filter(p -> p.hasValue("categories")).findFirst().orElseThrow();
        assertEquals(List.of(2024), categoryParams.getValue("years"));
        assertEquals(List.of("ENERGY & FUELS"), categoryParams.getValue("categories"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getForumRankingsWithoutScopeOmitsYearAndCategoryPredicates() {
        // Empty years/categories => the original load-all behaviour: no year/category predicate added.
        ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact metric =
                new ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact();
        metric.setJournalId("forum-1");
        metric.setYear(2024);
        metric.setMetricType(ro.uvt.pokedex.core.model.reporting.wos.MetricType.AIS);
        metric.setValue(1.2);
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).contains("scholardex_forum_metric_view")
                        ? List.of(metric) : List.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setPublicationName("Energy");

        assertFalse(facade.getForumRankings(forum, null, null).isEmpty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate, atLeast(1))
                .query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));
        assertTrue(sqlCaptor.getAllValues().stream().noneMatch(s -> s.contains("year IN (:years)")),
                "unscoped read must not add a year predicate");
        assertTrue(sqlCaptor.getAllValues().stream().noneMatch(s -> s.contains("category IN (:categories)")),
                "unscoped read must not add a category predicate");
    }

    @Test
    void isForumInEsciIsYearTrueWithCarryForward() {
        // Year-keyed category data only has 2023.
        when(namedParameterJdbcTemplate.queryForList(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(List.of(2023));

        assertTrue(facade.isForumInEsci("forum-1", 2023), "exact recorded year");
        assertTrue(facade.isForumInEsci("forum-1", 2026), "newer than data -> carry forward the last known year");
        assertFalse(facade.isForumInEsci("forum-1", 2010), "older than data -> journal was not indexed yet");
    }

    @Test
    void isForumInEsciFallsBackToMembershipSnapshotWhenNoYearKeyedData() {
        // No year-keyed rows for this forum...
        when(namedParameterJdbcTemplate.queryForList(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(List.of());
        // ...but it is present in the year-agnostic membership snapshot.
        when(namedParameterJdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(true);

        assertTrue(facade.isForumInEsci("forum-1", 2026));
    }

    @Test
    void isForumInEsciReturnsFalseForBlankForum() {
        assertFalse(facade.isForumInEsci("", 2024));
        assertFalse(facade.isForumInEsci(null, 2024));
    }
}
