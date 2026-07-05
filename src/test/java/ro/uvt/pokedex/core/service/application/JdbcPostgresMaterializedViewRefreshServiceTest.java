package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcPostgresMaterializedViewRefreshServiceTest {

    @Test
    void refreshForSlicesSkipsWhenNoRecognizedSlices() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcPostgresMaterializedViewRefreshService service = new JdbcPostgresMaterializedViewRefreshService(jdbcTemplate);

        var summary = service.refreshForSlices(Set.of("x"), "r1");
        assertEquals("SKIPPED", summary.status());
        assertEquals("no changed slices", summary.errorSample());
    }

    @Test
    void refreshManualForSlicesSkipsWhenEmpty() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcPostgresMaterializedViewRefreshService service = new JdbcPostgresMaterializedViewRefreshService(jdbcTemplate);

        var summary = service.refreshManualForSlices(Set.of("x"));
        assertEquals("SKIPPED", summary.status());
        assertEquals("no recognized slices", summary.errorSample());
    }

    @Test
    void refreshAllManualThrowsWhenRefreshFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("boom")).when(jdbcTemplate).execute(anyString());
        JdbcPostgresMaterializedViewRefreshService service = new JdbcPostgresMaterializedViewRefreshService(jdbcTemplate);

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::refreshAllManual);
        assertNotNull(ex.getMessage());
    }

    @Test
    void latestStatusMapsLatestRunAndViews() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM reporting_read.mv_refresh_run")) {
                        return List.of(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary(
                                "rid", "MANUAL", null, "SUCCESS", Instant.now(), Instant.now(), List.of(), null
                        ));
                    }
                    return List.of();
                });
        when(jdbcTemplate.query(anyString(), (PreparedStatementSetter) any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshItemSummary(
                        "v1", "SUCCESS", "ok", Instant.now(), Instant.now()
                )));

        JdbcPostgresMaterializedViewRefreshService service = new JdbcPostgresMaterializedViewRefreshService(jdbcTemplate);
        var status = service.latestStatus();
        assertNotNull(status.latestRun());
        assertEquals("SUCCESS", status.latestRun().status());
    }

    @Test
    void helperMethodsContracts() throws Exception {
        assertEquals(
                Set.of(
                        JdbcPostgresMaterializedViewRefreshService.MV_WOS_TOP_RANKINGS,
                        JdbcPostgresMaterializedViewRefreshService.MV_WOS_TOP_RANKINGS_IF,
                        JdbcPostgresMaterializedViewRefreshService.MV_SCOPUS_CITATION_CONTEXT
                ),
                JdbcPostgresMaterializedViewRefreshService.mapSlicesToViews(Set.of(" WOS ", "scopus"))
        );
        assertEquals(Set.of(), JdbcPostgresMaterializedViewRefreshService.mapSlicesToViews(null));

        Method trimError = JdbcPostgresMaterializedViewRefreshService.class.getDeclaredMethod("trimError", String.class);
        trimError.setAccessible(true);
        assertEquals(null, trimError.invoke(null, new Object[]{null}));
        assertEquals("x", trimError.invoke(null, " x "));

        Method timestamp = JdbcPostgresMaterializedViewRefreshService.class.getDeclaredMethod("timestamp", Instant.class);
        timestamp.setAccessible(true);
        Method toInstant = JdbcPostgresMaterializedViewRefreshService.class.getDeclaredMethod("toInstant", Timestamp.class);
        toInstant.setAccessible(true);
        Timestamp ts = (Timestamp) timestamp.invoke(null, Instant.parse("2026-01-01T00:00:00Z"));
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), toInstant.invoke(null, ts));
    }
}
