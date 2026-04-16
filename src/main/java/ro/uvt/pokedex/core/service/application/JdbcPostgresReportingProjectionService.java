package ro.uvt.pokedex.core.service.application;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Sorts.descending;

@Service
@ConditionalOnProperty(prefix = "core.h22.projection", name = "enabled", havingValue = "true")
public class JdbcPostgresReportingProjectionService implements PostgresReportingProjectionService {

    private static final Logger log = LoggerFactory.getLogger(JdbcPostgresReportingProjectionService.class);

    private static final String SLICE_WOS = "wos";
    private static final String SLICE_SCOPUS = "scopus";

    private static final String MODE_FULL_REBUILD = "FULL_REBUILD";
    private static final String MODE_INCREMENTAL_SYNC = "INCREMENTAL_SYNC";

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private final MongoTemplate mongoTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PostgresReportingProjectionProperties properties;
    private final PostgresMaterializedViewRefreshService materializedViewRefreshService;
    private final WosProjectionBuilderService wosProjectionBuilderService;
    private final ScopusProjectionBuilderService scopusProjectionBuilderService;

    public JdbcPostgresReportingProjectionService(
            MongoTemplate mongoTemplate,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            PostgresReportingProjectionProperties properties,
            PostgresMaterializedViewRefreshService materializedViewRefreshService,
            WosProjectionBuilderService wosProjectionBuilderService,
            ScopusProjectionBuilderService scopusProjectionBuilderService
    ) {
        this.mongoTemplate = mongoTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.materializedViewRefreshService = materializedViewRefreshService;
        this.wosProjectionBuilderService = wosProjectionBuilderService;
        this.scopusProjectionBuilderService = scopusProjectionBuilderService;
    }

    @Override
    public ProjectionRunSummary runFullRebuild() {
        return runProjection(MODE_FULL_REBUILD, true);
    }

    @Override
    public ProjectionRunSummary runIncrementalSync() {
        return runProjection(MODE_INCREMENTAL_SYNC, false);
    }

    @Override
    public ProjectionStatusSnapshot latestRunStatus() {
        ProjectionRunSummary latestRun = queryLatestRun();
        Map<String, CheckpointSummary> checkpoints = queryCheckpoints();
        return new ProjectionStatusSnapshot(latestRun, checkpoints);
    }

    @Override
    public void resetProjectionState() {
        jdbcTemplate.update("DELETE FROM reporting_read.projection_checkpoint");
        log.info("Postgres projection checkpoints reset");
    }

    static boolean shouldRebuildSlice(boolean fullRebuild, String sourceFingerprint, String checkpointFingerprint) {
        return fullRebuild || !Objects.equals(sourceFingerprint, checkpointFingerprint);
    }

    private ProjectionRunSummary runProjection(String mode, boolean fullRebuild) {
        String runId = "h22-projection-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        long runStartedNs = System.nanoTime();

        log.info(
                "Postgres projection run started: runId={} mode={} fullRebuild={} chunkSize={} statementTimeoutMs={}",
                runId, mode, fullRebuild, properties.getChunkSize(), properties.getStatementTimeoutMs()
        );

        jdbcTemplate.update("""
                INSERT INTO reporting_read.projection_run
                (run_id, mode, status, started_at)
                VALUES (?, ?, ?, ?)
                """, runId, mode, STATUS_RUNNING, timestamp(startedAt));

        Map<String, String> sourceFingerprints = Map.of(
                SLICE_WOS, computeWosFingerprint(),
                SLICE_SCOPUS, computeScopusFingerprint()
        );
        Map<String, CheckpointSummary> checkpoints = queryCheckpoints();

        List<SliceRunSummary> slices = new ArrayList<>();
        String errorSample = null;
        String finalStatus = STATUS_SUCCESS;

        for (String slice : List.of(SLICE_WOS, SLICE_SCOPUS)) {
            String sourceFingerprint = sourceFingerprints.get(slice);
            CheckpointSummary checkpoint = checkpoints.get(slice);
            String checkpointFingerprint = checkpoint == null ? null : checkpoint.sourceFingerprint();
            boolean rebuildSlice = shouldRebuildSlice(fullRebuild, sourceFingerprint, checkpointFingerprint);

            Instant sliceStartedAt = Instant.now();
            long sliceStartedNs = System.nanoTime();
            log.info(
                    "Postgres projection slice planned: runId={} slice={} rebuild={} sourceFingerprint={} checkpointFingerprint={}",
                    runId, slice, rebuildSlice, shortFingerprint(sourceFingerprint), shortFingerprint(checkpointFingerprint)
            );

            if (!rebuildSlice) {
                SliceRunSummary summary = new SliceRunSummary(
                        slice, STATUS_SKIPPED, sourceFingerprint, 0, "source fingerprint unchanged", sliceStartedAt, Instant.now()
                );
                persistSliceRun(runId, summary);
                slices.add(summary);
                log.info(
                        "Postgres projection slice skipped: runId={} slice={} durationMs={} reason={}",
                        runId, slice, elapsedMs(sliceStartedNs), summary.note()
                );
                continue;
            }

            try {
                SliceProjectionResult result = SLICE_WOS.equals(slice)
                        ? rebuildWosSlice(runId, startedAt)
                        : rebuildScopusSlice(runId, startedAt);
                materializedViewRefreshService.refreshForSlices(Set.of(slice), runId);
                upsertCheckpoint(slice, sourceFingerprint, runId, mode);
                SliceRunSummary summary = new SliceRunSummary(
                        slice, STATUS_SUCCESS, sourceFingerprint, result.insertedRows(), result.note(), sliceStartedAt, Instant.now()
                );
                persistSliceRun(runId, summary);
                slices.add(summary);
                log.info(
                        "Postgres projection slice completed: runId={} slice={} insertedRows={} durationMs={} note={}",
                        runId, slice, summary.insertedRows(), elapsedMs(sliceStartedNs), summary.note()
                );
            } catch (Exception e) {
                finalStatus = STATUS_FAILED;
                errorSample = trimError(e.getMessage());
                log.error("Postgres projection slice failed: runId={} slice={} mode={}", runId, slice, mode, e);

                SliceRunSummary failedSlice = new SliceRunSummary(
                        slice, STATUS_FAILED, sourceFingerprint, 0, errorSample, sliceStartedAt, Instant.now()
                );
                persistSliceRun(runId, failedSlice);
                slices.add(failedSlice);
                break;
            }
        }

        Instant completedAt = Instant.now();
        jdbcTemplate.update("""
                UPDATE reporting_read.projection_run
                SET status = ?, completed_at = ?, error_sample = ?
                WHERE run_id = ?
                """, finalStatus, timestamp(completedAt), errorSample, runId);

        long successSlices = slices.stream().filter(slice -> STATUS_SUCCESS.equals(slice.status())).count();
        long skippedSlices = slices.stream().filter(slice -> STATUS_SKIPPED.equals(slice.status())).count();
        long failedSlices = slices.stream().filter(slice -> STATUS_FAILED.equals(slice.status())).count();
        log.info(
                "Postgres projection run completed: runId={} status={} durationMs={} successSlices={} skippedSlices={} failedSlices={} error={}",
                runId, finalStatus, elapsedMs(runStartedNs), successSlices, skippedSlices, failedSlices, errorSample == null ? "none" : errorSample
        );

        return new ProjectionRunSummary(runId, mode, finalStatus, startedAt, completedAt, slices, errorSample);
    }

    private SliceProjectionResult rebuildWosSlice(String runId, Instant runStartedAt) {
        ImportProcessingResult result = wosProjectionBuilderService.rebuildWosProjections();
        if (result.getErrorCount() > 0) {
            String error = result.getErrorsSample().isEmpty() ? "unknown error" : result.getErrorsSample().getFirst();
            throw new IllegalStateException("WoS projection builder failed: " + error);
        }
        return new SliceProjectionResult(result.getImportedCount(), "wos slice projected");
    }

    private SliceProjectionResult rebuildScopusSlice(String runId, Instant runStartedAt) {
        ImportProcessingResult result = scopusProjectionBuilderService.rebuildViews();
        if (result.getErrorCount() > 0) {
            String error = result.getErrorsSample().isEmpty() ? "unknown error" : result.getErrorsSample().getFirst();
            throw new IllegalStateException("Scopus projection builder failed: " + error);
        }
        return new SliceProjectionResult(result.getImportedCount(), "scopus slice projected");
    }


    private String computeWosFingerprint() {
        String raw = String.join("|",
                "journal.count=" + countDocuments("wos.journal_identity"),
                "journal.maxUpdated=" + normalizeInstant(maxInstant("wos.journal_identity", "updatedAt")),
                "metric.count=" + countDocuments("wos.metric_facts"),
                "metric.maxCreated=" + normalizeInstant(maxInstant("wos.metric_facts", "createdAt")),
                "metric.maxVersion=" + nullToEmpty(maxString("wos.metric_facts", "sourceVersion")),
                "category.count=" + countDocuments("wos.category_facts"),
                "category.maxCreated=" + normalizeInstant(maxInstant("wos.category_facts", "createdAt")),
                "category.maxVersion=" + nullToEmpty(maxString("wos.category_facts", "sourceVersion"))
        );
        return sha256Hex(raw);
    }

    private String computeScopusFingerprint() {
        String raw = String.join("|",
                fingerprintPart("scholardex.publication_facts", "updatedAt", "createdAt"),
                fingerprintPart("scholardex.author_facts", "updatedAt", "createdAt"),
                fingerprintPart("scholardex.forum_facts", "updatedAt", "createdAt"),
                fingerprintPart("scholardex.affiliation_facts", "updatedAt", "createdAt"),
                fingerprintPart("scholardex.citation_facts", "updatedAt", "createdAt"),
                fingerprintPart("scholardex.authorship_facts", "updatedAt", "createdAt"),
                fingerprintPart("scholardex.author_affiliation_facts", "updatedAt", "createdAt")
        );
        return sha256Hex(raw);
    }

    private String fingerprintPart(String collectionName, String maxPrimaryField, String maxSecondaryField) {
        return collectionName + ":count=" + countDocuments(collectionName)
                + ",maxPrimary=" + normalizeInstant(maxInstant(collectionName, maxPrimaryField))
                + ",maxSecondary=" + normalizeInstant(maxInstant(collectionName, maxSecondaryField));
    }

    private long countDocuments(String collectionName) {
        return mongoTemplate.getCollection(collectionName).countDocuments();
    }

    private Instant maxInstant(String collectionName, String fieldName) {
        Document doc = mongoTemplate.getCollection(collectionName)
                .find(and(exists(fieldName), ne(fieldName, null)))
                .sort(descending(fieldName))
                .projection(new Document(fieldName, 1).append("_id", 0))
                .first();
        if (doc == null) {
            return null;
        }
        Object value = doc.get(fieldName);
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    private String maxString(String collectionName, String fieldName) {
        Document doc = mongoTemplate.getCollection(collectionName)
                .find(and(exists(fieldName), ne(fieldName, null)))
                .sort(descending(fieldName))
                .projection(new Document(fieldName, 1).append("_id", 0))
                .first();
        if (doc == null) {
            return null;
        }
        Object value = doc.get(fieldName);
        return value == null ? null : value.toString();
    }

    private void persistSliceRun(String runId, SliceRunSummary summary) {
        jdbcTemplate.update("""
                INSERT INTO reporting_read.projection_slice_run
                (run_id, slice_name, status, source_fingerprint, inserted_rows, note, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                summary.sliceName(),
                summary.status(),
                summary.sourceFingerprint(),
                summary.insertedRows(),
                summary.note(),
                timestamp(summary.startedAt()),
                timestamp(summary.completedAt())
        );
    }

    private void upsertCheckpoint(String sliceName, String sourceFingerprint, String runId, String mode) {
        jdbcTemplate.update("""
                INSERT INTO reporting_read.projection_checkpoint
                (slice_name, source_fingerprint, last_run_id, last_success_at, last_mode)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (slice_name)
                DO UPDATE SET
                  source_fingerprint = EXCLUDED.source_fingerprint,
                  last_run_id = EXCLUDED.last_run_id,
                  last_success_at = EXCLUDED.last_success_at,
                  last_mode = EXCLUDED.last_mode
                """, sliceName, sourceFingerprint, runId, timestamp(Instant.now()), mode);
    }

    private ProjectionRunSummary queryLatestRun() {
        List<ProjectionRunSummary> runs = jdbcTemplate.query("""
                SELECT run_id, mode, status, started_at, completed_at, error_sample
                FROM reporting_read.projection_run
                ORDER BY started_at DESC
                LIMIT 1
                """, (rs, rowNum) -> {
            String runId = rs.getString("run_id");
            List<SliceRunSummary> slices = querySliceRuns(runId);
            return new ProjectionRunSummary(
                    runId,
                    rs.getString("mode"),
                    rs.getString("status"),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("completed_at")),
                    slices,
                    rs.getString("error_sample")
            );
        });
        return runs.isEmpty() ? null : runs.getFirst();
    }

    private List<SliceRunSummary> querySliceRuns(String runId) {
        return jdbcTemplate.query("""
                SELECT slice_name, status, source_fingerprint, inserted_rows, note, started_at, completed_at
                FROM reporting_read.projection_slice_run
                WHERE run_id = ?
                ORDER BY started_at ASC
                """, new Object[]{runId}, (rs, rowNum) -> new SliceRunSummary(
                rs.getString("slice_name"),
                rs.getString("status"),
                rs.getString("source_fingerprint"),
                rs.getLong("inserted_rows"),
                rs.getString("note"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at"))
        ));
    }

    private Map<String, CheckpointSummary> queryCheckpoints() {
        List<CheckpointSummary> rows = jdbcTemplate.query("""
                SELECT slice_name, source_fingerprint, last_run_id, last_success_at, last_mode
                FROM reporting_read.projection_checkpoint
                """, checkpointRowMapper());
        Map<String, CheckpointSummary> out = new LinkedHashMap<>();
        for (CheckpointSummary row : rows) {
            out.put(row.sliceName(), row);
        }
        return out;
    }

    private RowMapper<CheckpointSummary> checkpointRowMapper() {
        return (rs, rowNum) -> new CheckpointSummary(
                rs.getString("slice_name"),
                rs.getString("source_fingerprint"),
                rs.getString("last_run_id"),
                toInstant(rs.getTimestamp("last_success_at")),
                rs.getString("last_mode")
        );
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String trimError(String raw) {
        if (raw == null) {
            return "unknown error";
        }
        String trimmed = raw.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= 500) {
            return trimmed;
        }
        return trimmed.substring(0, 500);
    }

    private static String normalizeInstant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String tempSuffix(String runId) {
        String normalized = runId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isBlank()) {
            normalized = UUID.randomUUID().toString().replace("-", "");
        }
        return normalized.length() > 12 ? normalized.substring(0, 12) : normalized;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private record SliceProjectionResult(long insertedRows, String note) {
    }
}
