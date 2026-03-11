package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.service.CacheService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresReportingLookupFacadeTest {

    @Mock
    private CacheService cacheService;

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private PostgresReportingLookupFacade facade;

    @BeforeEach
    void setUp() {
        facade = new PostgresReportingLookupFacade(cacheService, namedParameterJdbcTemplate);
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
        assertEquals("ECONOMICS", paramsCaptor.getValue().getValue("category"));
    }

    @Test
    void getTopRankingsReturnsZeroForBlankCategoryOrNullYear() {
        assertEquals(0, facade.getTopRankings("", 2024));
        assertEquals(0, facade.getTopRankings("ECONOMICS - SCIE", null));
    }

    @Test
    void getRankingsByIssnReturnsEmptyOnBlankInput() {
        assertTrue(facade.getRankingsByIssn(" ").isEmpty());
    }

    @Test
    void parseQuarterFallbackIsNotFoundForUnknownQuarterToken() {
        when(namedParameterJdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(java.util.List.of());

        assertTrue(facade.getRankingsByIssn("1234-5678").isEmpty());
    }
}
