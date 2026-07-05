package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.WosCategoryMetricBlock;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresWosCategoryReadPortTest {

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private PostgresWosCategoryReadPort readPort;

    @BeforeEach
    void setUp() {
        readPort = new PostgresWosCategoryReadPort(namedParameterJdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithQueryCastsEditionEnumToTextForIlike() {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(namedParameterJdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);

        var result = readPort.search(0, 25, "categoryName", "asc", "scie");

        assertEquals(0, result.totalItems());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        verify(namedParameterJdbcTemplate).queryForObject(sqlCaptor.capture(), any(MapSqlParameterSource.class), eq(Long.class));

        List<String> sqls = sqlCaptor.getAllValues();
        String aggregateSql = sqls.get(0);
        String countSql = sqls.get(1);

        assertTrue(aggregateSql.contains("edition_normalized::text ILIKE :q"));
        assertTrue(countSql.contains("edition_normalized::text ILIKE :q"));
        assertTrue(!aggregateSql.contains("OR edition_normalized ILIKE :q"));
        assertTrue(!countSql.contains("OR edition_normalized ILIKE :q"));
    }

    /** Stubs the three RowCallbackHandler-based queries (global latest years, ranking names, metric values). */
    private void stubCallbackQueries(Object[][] globalLatest, Object[][] rankingRows, Object[][] metricValues) {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            RowCallbackHandler handler = inv.getArgument(2);
            if (sql.contains("MAX(year) AS max_year")) {
                for (Object[] row : globalLatest) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("metric_type")).thenReturn((String) row[0]);
                    when(rs.getObject("max_year", Integer.class)).thenReturn((Integer) row[1]);
                    handler.processRow(rs);
                }
            } else if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                for (Object[] row : rankingRows) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("journal_id")).thenReturn((String) row[0]);
                    when(rs.getString("name")).thenReturn((String) row[1]);
                    lenient().when(rs.getString("issn")).thenReturn((String) row[2]);
                    lenient().when(rs.getString("e_issn")).thenReturn((String) row[3]);
                    handler.processRow(rs);
                }
            } else if (sql.contains("FROM reporting_read.wos_metric_fact")) {
                String metric = String.valueOf(
                        inv.getArgument(1, org.springframework.jdbc.core.namedparam.SqlParameterSource.class).getValue("metric"));
                for (Object[] row : metricValues) {
                    if (!metric.equals(row[0])) {
                        continue;
                    }
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("journal_id")).thenReturn((String) row[1]);
                    when(rs.getObject("year", Integer.class)).thenReturn((Integer) row[2]);
                    when(rs.getObject("value", Double.class)).thenReturn((Double) row[3]);
                    handler.processRow(rs);
                }
            }
            return null;
        }).when(namedParameterJdbcTemplate).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    private ResultSet categoryFactRow(String journalId, Integer year, String metric, String quarter, Integer rank) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("journal_id")).thenReturn(journalId);
        when(rs.getObject("year", Integer.class)).thenReturn(year);
        when(rs.getString("metric_type")).thenReturn(metric);
        when(rs.getString("quarter")).thenReturn(quarter);
        lenient().when(rs.getObject("rank", Integer.class)).thenReturn(rank);
        return rs;
    }

    @Test
    @SuppressWarnings("unchecked")
    void findCategoryPageBuildsPerMetricCohortBlocks() throws Exception {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(2);
                    return List.of(
                            mapper.mapRow(categoryFactRow("j1", 2024, "AIS", "1", 1), 0),
                            mapper.mapRow(categoryFactRow("j1", 2023, "AIS", "Q2", 2), 1),
                            mapper.mapRow(categoryFactRow("j2", 2008, "AIS", "q2", 20), 2),
                            mapper.mapRow(categoryFactRow("j1", 2019, "IF", "Q1", 2), 3)
                    );
                });
        stubCallbackQueries(
                new Object[][]{{"AIS", 2024}, {"IF", 2019}},
                new Object[][]{{"j1", "Journal One", "1111-1111", "2222-2222"}, {"j2", "Journal Two", null, null}},
                new Object[][]{{"AIS", "j1", 2023, 1.0}, {"AIS", "j1", 2024, 1.2}, {"IF", "j1", 2019, 2.5}}
        );

        WosCategoryDetailViewModel page = readPort.findCategoryPage("AI", EditionNormalized.SCIE).orElseThrow();

        assertEquals("AI - SCIE", page.key());
        assertEquals(2024, page.latestYear());
        assertEquals(1, page.journalCount());
        assertTrue(!page.archival());
        assertEquals(2, page.blocks().size());

        WosCategoryMetricBlock ais = page.blocks().get(0);
        assertEquals("AIS", ais.metricType());
        assertEquals(2024, ais.referenceYear());
        assertEquals(2020, ais.windowFrom());
        assertTrue(!ais.stale());
        assertEquals(1, ais.cohort().size());
        WosCategoryMetricBlock.Row j1 = ais.cohort().get(0);
        assertEquals("Journal One", j1.journalName());
        assertEquals("Q1", j1.quarter());          // "1" normalized to Q1
        assertEquals(1, j1.rank());
        assertEquals(1.2, j1.value());
        assertEquals(1.1, j1.windowAvg(), 1e-9);   // avg of the 2023+2024 window values
        assertEquals(2, j1.trend().size());
        assertEquals(1, ais.quartileSplit().q1());
        // j2 left the category in 2008 — a former member, never a cohort row
        assertEquals(1, ais.formerMembers().size());
        WosCategoryMetricBlock.FormerMember former = ais.formerMembers().get(0);
        assertEquals("Journal Two", former.journalName());
        assertEquals(2008, former.lastYear());
        assertEquals("Q2", former.quarter());
        assertEquals(20, former.rank());
        assertEquals(1, former.cohortSizeAtLastYear());

        WosCategoryMetricBlock impact = page.blocks().get(1);
        assertEquals("IF", impact.metricType());
        assertEquals(2019, impact.referenceYear());
        assertTrue(!impact.stale());               // 2019 IS the dataset-wide latest IF year
        assertEquals(2.5, impact.cohort().get(0).value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findCategoryPageMarksRetiredCategoriesArchival() throws Exception {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(2);
                    return List.of(
                            mapper.mapRow(categoryFactRow("j1", 2000, "AIS", "Q1", 1), 0),
                            mapper.mapRow(categoryFactRow("j1", 2000, "IF", "Q1", 1), 1)
                    );
                });
        stubCallbackQueries(
                new Object[][]{{"AIS", 2024}, {"IF", 2019}},
                new Object[][]{{"j1", "Journal One", "1111-1111", null}},
                new Object[][]{}
        );

        WosCategoryDetailViewModel page = readPort.findCategoryPage("SOFTWARE, GRAPHICS, PROGRAMMING", EditionNormalized.SCIE).orElseThrow();

        assertTrue(page.archival());
        assertEquals(2000, page.latestYear());
        assertTrue(page.blocks().stream().allMatch(WosCategoryMetricBlock::stale));
        assertEquals(1996, page.blocks().get(0).windowFrom());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findCategoryPageReturnsEmptyWhenNoFacts() {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        assertTrue(readPort.findCategoryPage("AI", EditionNormalized.SCIE).isEmpty());
    }

    @Test
    void privateHelperContracts() throws Exception {
        assertEquals("edition_normalized", invoke("normalizeSort", new Class[]{String.class}, "edition"));
        assertEquals("journal_count", invoke("normalizeSort", new Class[]{String.class}, "journalCount"));
        assertEquals("ASC", invoke("normalizeDirection", new Class[]{String.class}, "asc"));
        assertEquals("DESC", invoke("normalizeDirection", new Class[]{String.class}, "desc"));
        assertEquals(null, invoke("normalizeQuery", new Class[]{String.class}, (Object) null));
        assertEquals(null, invoke("normalizeQuery", new Class[]{String.class}, "   "));
        assertEquals("abc", invoke("normalizeQuery", new Class[]{String.class}, "  abc  "));
        assertEquals("Q1", invoke("normalizeQuarter", new Class[]{String.class}, "1"));
        assertEquals("—", invoke("normalizeQuarter", new Class[]{String.class}, " "));
        assertEquals("—", invoke("blankToDash", new Class[]{String.class}, " "));
        assertEquals("x\\\\y\\%z\\_k", invoke("escapeLikePattern", new Class[]{String.class}, "x\\y%z_k"));
        assertEquals(0, invoke("normalizePage", new Class[]{int.class, int.class}, 7, 0));
        assertEquals(2, invoke("normalizePage", new Class[]{int.class, int.class}, 9, 3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findCategoryPageOrdersCohortByRank() throws Exception {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(2);
                    return List.of(
                            mapper.mapRow(categoryFactRow("jZ", 2024, "AIS", "Q2", 2), 0),
                            mapper.mapRow(categoryFactRow("jA", 2024, "AIS", "Q1", 1), 1)
                    );
                });
        stubCallbackQueries(
                new Object[][]{{"AIS", 2024}},
                new Object[][]{{"jA", "Alpha", "1", "2"}, {"jZ", "Zulu", "3", "4"}},
                new Object[][]{}
        );

        WosCategoryDetailViewModel page = readPort.findCategoryPage("AI", EditionNormalized.SCIE).orElseThrow();
        assertEquals("Alpha", page.blocks().get(0).cohort().get(0).journalName());
        assertEquals("Zulu", page.blocks().get(0).cohort().get(1).journalName());
    }

    private Object invoke(String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = PostgresWosCategoryReadPort.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(readPort, args);
    }
}
