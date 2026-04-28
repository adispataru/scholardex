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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
}
