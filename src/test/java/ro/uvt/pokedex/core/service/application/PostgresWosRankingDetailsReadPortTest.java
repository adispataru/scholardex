package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresWosRankingDetailsReadPortTest {

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private PostgresWosRankingDetailsReadPort readPort;

    @BeforeEach
    void setUp() {
        readPort = new PostgresWosRankingDetailsReadPort(namedParameterJdbcTemplate);
    }

    @Test
    void findByJournalIdReturnsEmptyForBlankInput() {
        assertTrue(readPort.findByJournalId(" ").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByJournalIdBuildsLegacyRankingWithScoresAndCategories() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("journal_id")).thenReturn("j1");
                        when(rs.getString("name")).thenReturn("Journal 1");
                        when(rs.getString("issn")).thenReturn("1111-1111");
                        when(rs.getString("e_issn")).thenReturn("2222-2222");
                        java.sql.Array alternativeIssns = mock(java.sql.Array.class);
                        when(alternativeIssns.getArray()).thenReturn(new String[]{"3333-3333"});
                        java.sql.Array alternativeNames = mock(java.sql.Array.class);
                        when(alternativeNames.getArray()).thenReturn(new String[]{"J One"});
                        when(rs.getArray("alternative_issns")).thenReturn(alternativeIssns);
                        when(rs.getArray("alternative_names")).thenReturn(alternativeNames);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("FROM reporting_read.wos_metric_fact")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet ais2024 = mock(ResultSet.class);
                        when(ais2024.getString("journal_id")).thenReturn("j1");
                        when(ais2024.getObject("year", Integer.class)).thenReturn(2024);
                        when(ais2024.getString("metric_type")).thenReturn("AIS");
                        when(ais2024.getObject("value", Double.class)).thenReturn(1.2d);

                        ResultSet ais2024Lower = mock(ResultSet.class);
                        when(ais2024Lower.getString("journal_id")).thenReturn("j1");
                        when(ais2024Lower.getObject("year", Integer.class)).thenReturn(2024);
                        when(ais2024Lower.getString("metric_type")).thenReturn("AIS");
                        when(ais2024Lower.getObject("value", Double.class)).thenReturn(1.1d);

                        ResultSet ris2023 = mock(ResultSet.class);
                        when(ris2023.getString("journal_id")).thenReturn("j1");
                        when(ris2023.getObject("year", Integer.class)).thenReturn(2023);
                        when(ris2023.getString("metric_type")).thenReturn("RIS");
                        when(ris2023.getObject("value", Double.class)).thenReturn(2.3d);

                        return List.of(mapper.mapRow(ais2024, 0), mapper.mapRow(ais2024Lower, 1), mapper.mapRow(ris2023, 2));
                    }
                    if (sql.contains("FROM reporting_read.wos_category_fact")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet ais = mock(ResultSet.class);
                        when(ais.getString("journal_id")).thenReturn("j1");
                        when(ais.getObject("year", Integer.class)).thenReturn(2024);
                        when(ais.getString("category_name_canonical")).thenReturn("CS");
                        when(ais.getString("edition_normalized")).thenReturn("SCIE");
                        when(ais.getString("metric_type")).thenReturn("AIS");
                        when(ais.getString("quarter")).thenReturn("q1");
                        when(ais.getObject("quartile_rank", Integer.class)).thenReturn(2);
                        when(ais.getObject("rank_value", Integer.class)).thenReturn(10);

                        ResultSet risInvalidQuarter = mock(ResultSet.class);
                        when(risInvalidQuarter.getString("journal_id")).thenReturn("j1");
                        when(risInvalidQuarter.getObject("year", Integer.class)).thenReturn(2024);
                        when(risInvalidQuarter.getString("category_name_canonical")).thenReturn("CS");
                        when(risInvalidQuarter.getString("edition_normalized")).thenReturn("SCIE");
                        when(risInvalidQuarter.getString("metric_type")).thenReturn("RIS");
                        when(risInvalidQuarter.getString("quarter")).thenReturn("unknown");
                        when(risInvalidQuarter.getObject("quartile_rank", Integer.class)).thenReturn(3);
                        when(risInvalidQuarter.getObject("rank_value", Integer.class)).thenReturn(12);

                        return List.of(mapper.mapRow(ais, 0), mapper.mapRow(risInvalidQuarter, 1));
                    }
                    return List.of();
                });

        WoSRanking ranking = readPort.findByJournalId("j1").orElseThrow();
        assertEquals("j1", ranking.getId());
        assertEquals("Journal 1", ranking.getName());
        assertEquals(1.2d, ranking.getScore().getAis().get(2024));
        assertEquals(2.3d, ranking.getScore().getRis().get(2023));
        assertTrue(ranking.getWebOfScienceCategoryIndex().containsKey("CS - SCIE"));
        WoSRanking.Rank rank = ranking.getWebOfScienceCategoryIndex().get("CS - SCIE");
        assertEquals(WoSRanking.Quarter.Q1, rank.getQAis().get(2024));
        assertEquals(WoSRanking.Quarter.NOT_FOUND, rank.getQRis().get(2024));
        assertEquals(10, rank.getRankAis().get(2024));
        assertEquals(12, rank.getRankRis().get(2024));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByJournalIdReturnsEmptyWhenNoFactsExist() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("journal_id")).thenReturn("j1");
                        when(rs.getString("name")).thenReturn("Journal 1");
                        when(rs.getString("issn")).thenReturn("1111-1111");
                        when(rs.getString("e_issn")).thenReturn("2222-2222");
                        when(rs.getArray("alternative_issns")).thenReturn(null);
                        when(rs.getArray("alternative_names")).thenReturn(null);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });

        assertTrue(readPort.findByJournalId("j1").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByJournalIdReturnsEmptyWhenNoRankingViewFound() {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertTrue(readPort.findByJournalId("missing").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByJournalIdSkipsInvalidMetricAndCategoryFacts() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("journal_id")).thenReturn("j1");
                        when(rs.getString("name")).thenReturn("Journal 1");
                        when(rs.getString("issn")).thenReturn("1111-1111");
                        when(rs.getString("e_issn")).thenReturn("2222-2222");
                        when(rs.getArray("alternative_issns")).thenReturn(null);
                        when(rs.getArray("alternative_names")).thenReturn(null);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("FROM reporting_read.wos_metric_fact")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet invalid = mock(ResultSet.class);
                        when(invalid.getString("journal_id")).thenReturn("j1");
                        when(invalid.getObject("year", Integer.class)).thenReturn(null);
                        when(invalid.getString("metric_type")).thenReturn(MetricType.AIS.name());
                        when(invalid.getObject("value", Double.class)).thenReturn(1.0d);
                        return List.of(mapper.mapRow(invalid, 0));
                    }
                    if (sql.contains("FROM reporting_read.wos_category_fact")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet invalid = mock(ResultSet.class);
                        when(invalid.getString("journal_id")).thenReturn("j1");
                        when(invalid.getObject("year", Integer.class)).thenReturn(null);
                        when(invalid.getString("category_name_canonical")).thenReturn(" ");
                        when(invalid.getString("edition_normalized")).thenReturn("SCIE");
                        when(invalid.getString("metric_type")).thenReturn("AIS");
                        when(invalid.getString("quarter")).thenReturn(" ");
                        when(invalid.getObject("quartile_rank", Integer.class)).thenReturn(null);
                        when(invalid.getObject("rank_value", Integer.class)).thenReturn(null);
                        return List.of(mapper.mapRow(invalid, 0));
                    }
                    return List.of();
                });

        WoSRanking ranking = readPort.findByJournalId("j1").orElseThrow();
        assertTrue(ranking.getScore().getAis().isEmpty());
        assertTrue(ranking.getWebOfScienceCategoryIndex().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByJournalIdCoversIfMetricAndIfCategoryRankBranches() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("journal_id")).thenReturn("j1");
                        when(rs.getString("name")).thenReturn("Journal 1");
                        when(rs.getString("issn")).thenReturn("1111-1111");
                        when(rs.getString("e_issn")).thenReturn("2222-2222");
                        when(rs.getArray("alternative_issns")).thenReturn(null);
                        when(rs.getArray("alternative_names")).thenReturn(null);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("FROM reporting_read.wos_metric_fact")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet metric = mock(ResultSet.class);
                        when(metric.getString("journal_id")).thenReturn("j1");
                        when(metric.getObject("year", Integer.class)).thenReturn(2025);
                        when(metric.getString("metric_type")).thenReturn("IF");
                        when(metric.getObject("value", Double.class)).thenReturn(4.2d);
                        return List.of(mapper.mapRow(metric, 0));
                    }
                    if (sql.contains("FROM reporting_read.wos_category_fact")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet category = mock(ResultSet.class);
                        when(category.getString("journal_id")).thenReturn("j1");
                        when(category.getObject("year", Integer.class)).thenReturn(2025);
                        when(category.getString("category_name_canonical")).thenReturn("Physics");
                        when(category.getString("edition_normalized")).thenReturn("SCIE");
                        when(category.getString("metric_type")).thenReturn("IF");
                        when(category.getString("quarter")).thenReturn("Q3");
                        when(category.getObject("quartile_rank", Integer.class)).thenReturn(8);
                        when(category.getObject("rank_value", Integer.class)).thenReturn(13);
                        return List.of(mapper.mapRow(category, 0));
                    }
                    return List.of();
                });

        WoSRanking ranking = readPort.findByJournalId("j1").orElseThrow();
        assertEquals(4.2d, ranking.getScore().getIF().get(2025));
        WoSRanking.Rank rank = ranking.getWebOfScienceCategoryIndex().get("Physics - SCIE");
        assertEquals(WoSRanking.Quarter.Q3, rank.getQIF().get(2025));
        assertEquals(8, rank.getQuartileRankIF().get(2025));
        assertEquals(13, rank.getRankIF().get(2025));
    }

}
