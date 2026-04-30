package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.eq;

class JdbcPostgresReportingProjectionServiceTest {

    @Test
    void shouldRebuildSliceReturnsTrueForFullRebuild() {
        assertTrue(JdbcPostgresReportingProjectionService.shouldRebuildSlice(true, "abc", "abc"));
    }

    @Test
    void shouldRebuildSliceReturnsFalseWhenFingerprintsMatchInIncrementalMode() {
        assertFalse(JdbcPostgresReportingProjectionService.shouldRebuildSlice(false, "abc", "abc"));
    }

    @Test
    void shouldRebuildSliceReturnsTrueWhenFingerprintsDifferInIncrementalMode() {
        assertTrue(JdbcPostgresReportingProjectionService.shouldRebuildSlice(false, "abc", "def"));
    }

    @Test
    void helperMethodsContracts() throws Exception {
        assertEquals("unknown error", invokeStatic("trimError", new Class[]{String.class}, (Object) null));
        assertEquals(500, ((String) invokeStatic("trimError", new Class[]{String.class}, "x".repeat(700))).length());
        assertEquals("", invokeStatic("nullToEmpty", new Class[]{String.class}, (Object) null));
        assertEquals("abc", invokeStatic("shortFingerprint", new Class[]{String.class}, "abc"));
        assertEquals("none", invokeStatic("shortFingerprint", new Class[]{String.class}, " "));
        assertEquals(64, ((String) invokeStatic("sha256Hex", new Class[]{String.class}, "x")).length());
        assertTrue((long) invokeStatic("elapsedMs", new Class[]{long.class}, System.nanoTime() - 1_000_000L) >= 0);
        assertTrue(((String) invokeStatic("tempSuffix", new Class[]{String.class}, "***")).length() > 0);
        assertTrue(((String) invokeStatic("tempSuffix", new Class[]{String.class}, "abcdefghijklmnop")).length() <= 12);
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), invokeStatic(
                "toInstant",
                new Class[]{java.sql.Timestamp.class},
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        ));
    }

    @Test
    void queryLatestRunAndSliceRunsExecuteRowMappers() throws Exception {
        WosProjectionBuilderService wosBuilder = mock(WosProjectionBuilderService.class);
        ScholardexProjectionBuilderService scopusBuilder = mock(ScholardexProjectionBuilderService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("FROM reporting_read.projection_run")) {
                @SuppressWarnings("unchecked")
                RowMapper<PostgresReportingProjectionService.ProjectionRunSummary> mapper = inv.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                Instant now = Instant.parse("2026-04-30T10:15:30Z");
                when(rs.getString("run_id")).thenReturn("run-42");
                when(rs.getString("mode")).thenReturn("INCREMENTAL_SYNC");
                when(rs.getString("status")).thenReturn("SUCCESS");
                when(rs.getString("error_sample")).thenReturn(null);
                when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(now));
                when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(now.plusSeconds(4)));
                return List.of(mapper.mapRow(rs, 0));
            }
            return List.of();
        });
        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            RowMapper<PostgresReportingProjectionService.SliceRunSummary> mapper = inv.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            Instant now = Instant.parse("2026-04-30T10:15:30Z");
            when(rs.getString("slice_name")).thenReturn("wos");
            when(rs.getString("status")).thenReturn("SUCCESS");
            when(rs.getString("source_fingerprint")).thenReturn("fp");
            when(rs.getLong("inserted_rows")).thenReturn(7L);
            when(rs.getString("note")).thenReturn("ok");
            when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(now));
            when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(now.plusSeconds(1)));
            return List.of(mapper.mapRow(rs, 0));
        });

        JdbcPostgresReportingProjectionService service = new JdbcPostgresReportingProjectionService(
                mongoTemplate,
                jdbcTemplate,
                mock(PlatformTransactionManager.class),
                new PostgresReportingProjectionProperties(),
                mock(PostgresMaterializedViewRefreshService.class),
                wosBuilder,
                scopusBuilder
        );

        Object latestRun = invokeInstance(service, "queryLatestRun", new Class[]{});
        assertNotNull(latestRun);
        Method slicesAccessor = latestRun.getClass().getDeclaredMethod("slices");
        slicesAccessor.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PostgresReportingProjectionService.SliceRunSummary> slices =
                (List<PostgresReportingProjectionService.SliceRunSummary>) slicesAccessor.invoke(latestRun);
        assertEquals(1, slices.size());
        assertEquals("wos", slices.getFirst().sliceName());

        @SuppressWarnings("unchecked")
        List<PostgresReportingProjectionService.SliceRunSummary> directSlices =
                (List<PostgresReportingProjectionService.SliceRunSummary>) invokeInstance(
                        service, "querySliceRuns", new Class[]{String.class}, "run-42"
                );
        assertEquals(1, directSlices.size());
        verify(jdbcTemplate, atLeastOnce()).query(anyString(), any(Object[].class), any(RowMapper.class));
    }

    @Test
    void maxInstantSupportsInstantTypedMongoValue() throws Exception {
        WosProjectionBuilderService wosBuilder = mock(WosProjectionBuilderService.class);
        ScholardexProjectionBuilderService scopusBuilder = mock(ScholardexProjectionBuilderService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        Instant expected = Instant.parse("2026-04-30T11:00:00Z");
        org.bson.Document doc = new org.bson.Document("updatedAt", expected);
        when(mongoTemplate.getCollection(eq("wos.journal_identity"))
                .find(any(org.bson.conversions.Bson.class))
                .sort(any())
                .projection(any())
                .first()).thenReturn(doc);

        JdbcPostgresReportingProjectionService service = new JdbcPostgresReportingProjectionService(
                mongoTemplate,
                jdbcTemplate,
                mock(PlatformTransactionManager.class),
                new PostgresReportingProjectionProperties(),
                mock(PostgresMaterializedViewRefreshService.class),
                wosBuilder,
                scopusBuilder
        );

        Instant actual = (Instant) invokeInstance(
                service,
                "maxInstant",
                new Class[]{String.class, String.class},
                "wos.journal_identity",
                "updatedAt"
        );
        assertEquals(expected, actual);
    }

    @Test
    void rebuildSlicesThrowWhenBuilderReturnsErrors() throws Exception {
        WosProjectionBuilderService wosBuilder = mock(WosProjectionBuilderService.class);
        ScholardexProjectionBuilderService scopusBuilder = mock(ScholardexProjectionBuilderService.class);

        ImportProcessingResult wosError = new ImportProcessingResult(5);
        wosError.markError("wos err");
        ImportProcessingResult scopusError = new ImportProcessingResult(5);
        scopusError.markError("scopus err");
        when(wosBuilder.rebuildWosProjections()).thenReturn(wosError);
        when(scopusBuilder.rebuildViews()).thenReturn(scopusError);

        JdbcPostgresReportingProjectionService service = newService(wosBuilder, scopusBuilder);

        InvocationTargetException wosEx = assertThrows(
                InvocationTargetException.class,
                () -> invokeInstance(service, "rebuildWosSlice", new Class[]{String.class, Instant.class}, "r1", Instant.now())
        );
        assertTrue(wosEx.getCause() instanceof IllegalStateException);

        InvocationTargetException scopusEx = assertThrows(
                InvocationTargetException.class,
                () -> invokeInstance(service, "rebuildScopusSlice", new Class[]{String.class, Instant.class}, "r1", Instant.now())
        );
        assertTrue(scopusEx.getCause() instanceof IllegalStateException);
    }

    @Test
    void rebuildSlicesReturnResultWhenBuildersSucceed() throws Exception {
        WosProjectionBuilderService wosBuilder = mock(WosProjectionBuilderService.class);
        ScholardexProjectionBuilderService scopusBuilder = mock(ScholardexProjectionBuilderService.class);

        ImportProcessingResult wosOk = new ImportProcessingResult(5);
        wosOk.markImported();
        ImportProcessingResult scopusOk = new ImportProcessingResult(5);
        scopusOk.markImported();
        scopusOk.markImported();
        when(wosBuilder.rebuildWosProjections()).thenReturn(wosOk);
        when(scopusBuilder.rebuildViews()).thenReturn(scopusOk);

        JdbcPostgresReportingProjectionService service = newService(wosBuilder, scopusBuilder);
        Object wosResult = invokeInstance(service, "rebuildWosSlice", new Class[]{String.class, Instant.class}, "r1", Instant.now());
        Object scopusResult = invokeInstance(service, "rebuildScopusSlice", new Class[]{String.class, Instant.class}, "r1", Instant.now());

        Method insertedRows = wosResult.getClass().getDeclaredMethod("insertedRows");
        insertedRows.setAccessible(true);
        assertEquals(1L, insertedRows.invoke(wosResult));
        assertEquals(2L, insertedRows.invoke(scopusResult));
    }

    @Test
    void runIncrementalSyncSkipsSlicesWhenFingerprintsMatchCheckpoints() throws Exception {
        WosProjectionBuilderService wosBuilder = mock(WosProjectionBuilderService.class);
        ScholardexProjectionBuilderService scopusBuilder = mock(ScholardexProjectionBuilderService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        when(mongoTemplate.getCollection(anyString()).countDocuments()).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        JdbcPostgresReportingProjectionService service = new JdbcPostgresReportingProjectionService(
                mongoTemplate,
                jdbcTemplate,
                mock(PlatformTransactionManager.class),
                new PostgresReportingProjectionProperties(),
                mock(PostgresMaterializedViewRefreshService.class),
                wosBuilder,
                scopusBuilder
        );

        String wosFp = (String) invokeInstance(service, "computeWosFingerprint", new Class[]{});
        String scopusFp = (String) invokeInstance(service, "computeScopusFingerprint", new Class[]{});
        List<PostgresReportingProjectionService.CheckpointSummary> checkpoints = List.of(
                new PostgresReportingProjectionService.CheckpointSummary("wos", wosFp, "r0", Instant.now(), "INCREMENTAL_SYNC"),
                new PostgresReportingProjectionService.CheckpointSummary("scopus", scopusFp, "r0", Instant.now(), "INCREMENTAL_SYNC")
        );
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn((List) checkpoints);

        PostgresReportingProjectionService.ProjectionRunSummary summary = service.runIncrementalSync();
        assertEquals("SUCCESS", summary.status());
        assertEquals(2, summary.slices().size());
        assertTrue(summary.slices().stream().allMatch(s -> "SKIPPED".equals(s.status())));
    }

    @Test
    void latestRunStatusAndResetProjectionStateAreQueryable() {
        WosProjectionBuilderService wosBuilder = mock(WosProjectionBuilderService.class);
        ScholardexProjectionBuilderService scopusBuilder = mock(ScholardexProjectionBuilderService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM reporting_read.projection_run")) {
                        return List.of(new PostgresReportingProjectionService.ProjectionRunSummary(
                                "r1", "INCREMENTAL_SYNC", "SUCCESS", Instant.now(), Instant.now(), List.of(), null
                        ));
                    }
                    if (sql.contains("FROM reporting_read.projection_slice_run")) {
                        return List.of(new PostgresReportingProjectionService.SliceRunSummary(
                                "wos", "SUCCESS", "fp", 1L, "ok", Instant.now(), Instant.now()
                        ));
                    }
                    if (sql.contains("FROM reporting_read.projection_checkpoint")) {
                        return List.of(new PostgresReportingProjectionService.CheckpointSummary(
                                "wos", "fp", "r1", Instant.now(), "INCREMENTAL_SYNC"
                        ));
                    }
                    return List.of();
                });

        JdbcPostgresReportingProjectionService service = new JdbcPostgresReportingProjectionService(
                mongoTemplate,
                jdbcTemplate,
                mock(PlatformTransactionManager.class),
                new PostgresReportingProjectionProperties(),
                mock(PostgresMaterializedViewRefreshService.class),
                wosBuilder,
                scopusBuilder
        );

        PostgresReportingProjectionService.ProjectionStatusSnapshot snapshot = service.latestRunStatus();
        assertNotNull(snapshot.latestRun());
        assertEquals(1, snapshot.checkpoints().size());

        service.resetProjectionState();
        verify(jdbcTemplate).update("DELETE FROM reporting_read.projection_checkpoint");
    }

    private static JdbcPostgresReportingProjectionService newService(
            WosProjectionBuilderService wosBuilder,
            ScholardexProjectionBuilderService scopusBuilder
    ) {
        PostgresReportingProjectionProperties properties = new PostgresReportingProjectionProperties();
        return new JdbcPostgresReportingProjectionService(
                mock(MongoTemplate.class, RETURNS_DEEP_STUBS),
                mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class),
                properties,
                mock(PostgresMaterializedViewRefreshService.class),
                wosBuilder,
                scopusBuilder
        );
    }

    private static Object invokeStatic(String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = JdbcPostgresReportingProjectionService.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static Object invokeInstance(Object target, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}
