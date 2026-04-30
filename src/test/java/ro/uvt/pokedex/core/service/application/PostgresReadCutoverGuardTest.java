package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresReadCutoverGuardTest {

    @Test
    void runThrowsWhenCheckpointCountIsNull() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(null);

        PostgresReadCutoverGuard guard = new PostgresReadCutoverGuard(
                jdbcTemplate,
                mock(PostgresReportingLookupFacade.class),
                mock(PostgresWosRankingReadPort.class),
                mock(PostgresScholardexAuthorReadPort.class),
                mock(PostgresScholardexForumReadPort.class),
                mock(PostgresScholardexAffiliationReadPort.class),
                mock(PostgresScholardexAdminReadPort.class)
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.run(mock(ApplicationArguments.class)));
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("requires completed projection checkpoints");
    }

    @Test
    void runThrowsWhenCheckpointCountIsLessThanTwo() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);

        PostgresReadCutoverGuard guard = new PostgresReadCutoverGuard(
                jdbcTemplate,
                mock(PostgresReportingLookupFacade.class),
                mock(PostgresWosRankingReadPort.class),
                mock(PostgresScholardexAuthorReadPort.class),
                mock(PostgresScholardexForumReadPort.class),
                mock(PostgresScholardexAffiliationReadPort.class),
                mock(PostgresScholardexAdminReadPort.class)
        );

        assertThrows(IllegalStateException.class, () -> guard.run(mock(ApplicationArguments.class)));
    }

    @Test
    void runPassesWhenBothCheckpointsExist() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);

        PostgresReadCutoverGuard guard = new PostgresReadCutoverGuard(
                jdbcTemplate,
                mock(PostgresReportingLookupFacade.class),
                mock(PostgresWosRankingReadPort.class),
                mock(PostgresScholardexAuthorReadPort.class),
                mock(PostgresScholardexForumReadPort.class),
                mock(PostgresScholardexAffiliationReadPort.class),
                mock(PostgresScholardexAdminReadPort.class)
        );

        assertDoesNotThrow(() -> guard.run(mock(ApplicationArguments.class)));
    }
}

