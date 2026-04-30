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

import java.sql.ResultSet;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @SuppressWarnings("unchecked")
    void findCategoryPageBuildsSnapshotAndNormalizesQuarters() throws Exception {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM reporting_read.wos_category_fact")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet rs1 = mock(ResultSet.class);
                        when(rs1.getString("journal_id")).thenReturn("j1");
                        when(rs1.getObject("year", Integer.class)).thenReturn(2024);
                        when(rs1.getString("category_name_canonical")).thenReturn("AI");
                        when(rs1.getString("edition_normalized")).thenReturn("SCIE");
                        when(rs1.getString("metric_type")).thenReturn("AIS");
                        when(rs1.getString("quarter")).thenReturn("1");

                        ResultSet rs2 = mock(ResultSet.class);
                        when(rs2.getString("journal_id")).thenReturn("j1");
                        when(rs2.getObject("year", Integer.class)).thenReturn(2023);
                        when(rs2.getString("category_name_canonical")).thenReturn("AI");
                        when(rs2.getString("edition_normalized")).thenReturn("SCIE");
                        when(rs2.getString("metric_type")).thenReturn("RIS");
                        when(rs2.getString("quarter")).thenReturn("q2");

                        ResultSet rs3 = mock(ResultSet.class);
                        when(rs3.getString("journal_id")).thenReturn("j2");
                        when(rs3.getObject("year", Integer.class)).thenReturn(2022);
                        when(rs3.getString("category_name_canonical")).thenReturn("AI");
                        when(rs3.getString("edition_normalized")).thenReturn("SCIE");
                        when(rs3.getString("metric_type")).thenReturn("IF");
                        when(rs3.getString("quarter")).thenReturn(null);

                        return List.of(mapper.mapRow(rs1, 0), mapper.mapRow(rs2, 1), mapper.mapRow(rs3, 2));
                    }
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet rs1 = mock(ResultSet.class);
                        when(rs1.getString("journal_id")).thenReturn("j1");
                        when(rs1.getString("name")).thenReturn("Journal One");
                        when(rs1.getString("issn")).thenReturn("1111-1111");
                        when(rs1.getString("e_issn")).thenReturn("2222-2222");

                        ResultSet rs2 = mock(ResultSet.class);
                        when(rs2.getString("journal_id")).thenReturn("j2");
                        when(rs2.getString("name")).thenReturn("");
                        when(rs2.getString("issn")).thenReturn(null);
                        when(rs2.getString("e_issn")).thenReturn("  ");
                        mapper.mapRow(rs1, 0);
                        mapper.mapRow(rs2, 1);
                        return List.of();
                    }
                    return List.of();
                });

        Optional<WosCategoryDetailViewModel> pageOpt = readPort.findCategoryPage("AI", EditionNormalized.SCIE);
        assertTrue(pageOpt.isPresent());
        WosCategoryDetailViewModel page = pageOpt.get();
        assertEquals("AI - SCIE", page.key());
        assertEquals(2, page.journals().size());
        assertEquals(2024, page.latestYear());
        var byId = page.journals().stream().collect(java.util.stream.Collectors.toMap(j -> j.journalId(), j -> j));
        assertEquals("Journal One", byId.get("j1").journalName());
        assertEquals("Q1", byId.get("j1").latestAisQuarter());
        assertEquals("Q2", byId.get("j1").latestRisQuarter());
        assertEquals("—", byId.get("j2").latestIfQuarter());
    }

    @Test
    void searchRejectsInvalidSortDirectionAndOverlongQuery() {
        assertThrows(IllegalArgumentException.class, () -> readPort.search(0, 25, "bad", "asc", null));
        assertThrows(IllegalArgumentException.class, () -> readPort.search(0, 25, "categoryName", "up", null));
        assertThrows(IllegalArgumentException.class,
                () -> readPort.search(0, 25, "categoryName", "asc", "x".repeat(101)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchBuildsItemsAndSafePageFromCount() throws Exception {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(2);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("key")).thenReturn("AI - SCIE");
                    when(rs.getString("category_name_canonical")).thenReturn("AI");
                    when(rs.getString("edition_normalized")).thenReturn("SCIE");
                    when(rs.getLong("journal_count")).thenReturn(7L);
                    when(rs.getObject("latest_year", Integer.class)).thenReturn(2024);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(namedParameterJdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(3L);

        var page = readPort.search(9, 2, "latestYear", "desc", "  ");
        assertEquals(1, page.items().size());
        assertEquals(2, page.totalPages());
        assertEquals(1, page.page());
        assertEquals(3L, page.totalItems());

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(namedParameterJdbcTemplate).query(any(String.class), paramsCaptor.capture(), any(RowMapper.class));
        MapSqlParameterSource usedParams = paramsCaptor.getValue();
        assertEquals(2, usedParams.getValue("limit"));
        assertEquals(2L, usedParams.getValue("offset"));
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
    void findCategoryPageSortsJournalsByName() throws Exception {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM reporting_read.wos_category_fact")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet a = mock(ResultSet.class);
                        when(a.getString("journal_id")).thenReturn("jA");
                        when(a.getObject("year", Integer.class)).thenReturn(2024);
                        when(a.getString("category_name_canonical")).thenReturn("AI");
                        when(a.getString("edition_normalized")).thenReturn("SCIE");
                        when(a.getString("metric_type")).thenReturn("AIS");
                        when(a.getString("quarter")).thenReturn("Q1");
                        ResultSet z = mock(ResultSet.class);
                        when(z.getString("journal_id")).thenReturn("jZ");
                        when(z.getObject("year", Integer.class)).thenReturn(2024);
                        when(z.getString("category_name_canonical")).thenReturn("AI");
                        when(z.getString("edition_normalized")).thenReturn("SCIE");
                        when(z.getString("metric_type")).thenReturn("AIS");
                        when(z.getString("quarter")).thenReturn("Q1");
                        return List.of(mapper.mapRow(z, 0), mapper.mapRow(a, 1));
                    }
                    if (sql.contains("FROM reporting_read.wos_ranking_view")) {
                        RowMapper<Object> mapper = inv.getArgument(2);
                        ResultSet a = mock(ResultSet.class);
                        when(a.getString("journal_id")).thenReturn("jA");
                        when(a.getString("name")).thenReturn("Alpha");
                        when(a.getString("issn")).thenReturn("1");
                        when(a.getString("e_issn")).thenReturn("2");
                        ResultSet z = mock(ResultSet.class);
                        when(z.getString("journal_id")).thenReturn("jZ");
                        when(z.getString("name")).thenReturn("Zulu");
                        when(z.getString("issn")).thenReturn("3");
                        when(z.getString("e_issn")).thenReturn("4");
                        mapper.mapRow(z, 0);
                        mapper.mapRow(a, 1);
                        return List.of();
                    }
                    return List.of();
                });

        WosCategoryDetailViewModel page = readPort.findCategoryPage("AI", EditionNormalized.SCIE).orElseThrow();
        assertEquals("Alpha", page.journals().get(0).journalName());
        assertEquals("Zulu", page.journals().get(1).journalName());
    }

    private Object invoke(String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = PostgresWosCategoryReadPort.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(readPort, args);
    }
}
