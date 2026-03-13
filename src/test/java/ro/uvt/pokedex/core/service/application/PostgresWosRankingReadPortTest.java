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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresWosRankingReadPortTest {

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private PostgresWosRankingReadPort service;

    @BeforeEach
    void setUp() {
        service = new PostgresWosRankingReadPort(namedParameterJdbcTemplate);
        lenient().when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        lenient().when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
    }

    @Test
    void searchUsesPrefixFiltersAndDeterministicTieBreakOrdering() {
        service.search(0, 25, "name", "asc", "ab cd");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(namedParameterJdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

        String sql = sqlCaptor.getValue();
        MapSqlParameterSource params = paramsCaptor.getValue();

        assertTrue(sql.contains("name_norm LIKE :namePrefix ESCAPE '\\'"));
        assertTrue(sql.contains("issn_norm LIKE :issnPrefix"));
        assertTrue(sql.contains("e_issn_norm LIKE :issnPrefix"));
        assertTrue(sql.contains("ESCAPE '\\'"));
        assertTrue(sql.contains("unnest(alternative_issns_norm) alt"));
        assertTrue(sql.contains("alt LIKE :issnPrefix"));
        assertTrue(sql.contains("ORDER BY name COLLATE \"C\" ASC, journal_id COLLATE \"C\" ASC"));
        assertEquals("ab cd%", params.getValue("namePrefix"));
        assertEquals("ABCD%", params.getValue("issnPrefix"));
    }

    @Test
    void searchEscapesLikePatternAndUsesEscapeClause() {
        service.search(0, 25, "issn", "desc", "a%b_c\\d");

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(namedParameterJdbcTemplate).query(anyString(), paramsCaptor.capture(), any(RowMapper.class));

        MapSqlParameterSource params = paramsCaptor.getValue();
        assertEquals("a\\%b\\_c\\\\d%", params.getValue("namePrefix"));
        assertEquals("A\\%B\\_C\\\\D%", params.getValue("issnPrefix"));
    }

    @Test
    void searchWithoutQueryDoesNotAddPrefixFilter() {
        service.search(0, 25, "name", "asc", "   ");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertFalse(sqlCaptor.getValue().contains("name_norm LIKE :namePrefix"));
        assertFalse(sqlCaptor.getValue().contains("issn_norm LIKE :issnPrefix"));
    }

    @Test
    void invalidSortThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "bad", "asc", null));
    }

    @Test
    void invalidDirectionThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "name", "up", null));
    }

    @Test
    void invalidQueryLengthThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "name", "asc", "x".repeat(101)));
    }
}
