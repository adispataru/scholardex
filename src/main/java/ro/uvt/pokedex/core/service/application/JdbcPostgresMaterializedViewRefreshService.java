package ro.uvt.pokedex.core.service.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnBean(DataSource.class)
public class JdbcPostgresMaterializedViewRefreshService implements PostgresMaterializedViewRefreshService {

    static final String SLICE_WOS = "wos";
    static final String SLICE_SCOPUS = "scopus";

    static final String MV_WOS_TOP_RANKINGS = "reporting_read.mv_wos_top_rankings_q1_ais";
    static final String MV_SCOPUS_CITATION_CONTEXT = "reporting_read.mv_scholardex_citation_context";

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private static final String TRIGGER_PROJECTION = "PROJECTION";
    private static final String TRIGGER_MANUAL = "MANUAL";

    private final JdbcTemplate jdbcTemplate;

    public JdbcPostgresMaterializedViewRefreshService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public MaterializedViewRefreshRunSummary refreshForSlices(Set<String> changedSlices, String projectionRunId) {
        Set<String> views = mapSlicesToViews(changedSlices);
        if (views.isEmpty()) {
            Instant now = Instant.now();
            return new MaterializedViewRefreshRunSummary(
                    "h22-mv-refresh-skipped",
                    TRIGGER_PROJECTION,
                    projectionRunId,
                    STATUS_SKIPPED,
                    now,
                    now,
                    List.of(),
                    "no changed slices"
            );
        }
        return runRefresh(TRIGGER_PROJECTION, projectionRunId, views);
    }

    @Override
    public MaterializedViewRefreshRunSummary refreshAllManual() {
        return runRefresh(
                TRIGGER_MANUAL,
                null,
                Set.of(MV_WOS_TOP_RANKINGS, MV_SCOPUS_CITATION_CONTEXT)
        );
    }

    @Override
    public MaterializedViewRefreshRunSummary refreshManualForSlices(Set<String> slices) {
        Set<String> views = mapSlicesToViews(slices);
        if (views.isEmpty()) {
            Instant now = Instant.now();
            return new MaterializedViewRefreshRunSummary(
                    "h22-mv-refresh-skipped",
                    TRIGGER_MANUAL,
                    null,
                    STATUS_SKIPPED,
                    now,
                    now,
                    List.of(),
                    "no recognized slices"
            );
        }
        return runRefresh(TRIGGER_MANUAL, null, views);
    }

    @Override
    public MaterializedViewRefreshStatusSnapshot latestStatus() {
        List<MaterializedViewRefreshRunSummary> rows = jdbcTemplate.query(
                """
                        SELECT run_id, trigger_mode, trigger_reference, status, started_at, completed_at, error_sample
                        FROM reporting_read.mv_refresh_run
                        ORDER BY started_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> {
                    String runId = rs.getString("run_id");
                    List<MaterializedViewRefreshItemSummary> views = queryViewRuns(runId);
                    return new MaterializedViewRefreshRunSummary(
                            runId,
                            rs.getString("trigger_mode"),
                            rs.getString("trigger_reference"),
                            rs.getString("status"),
                            toInstant(rs.getTimestamp("started_at")),
                            toInstant(rs.getTimestamp("completed_at")),
                            views,
                            rs.getString("error_sample")
                    );
                }
        );

        return new MaterializedViewRefreshStatusSnapshot(rows.isEmpty() ? null : rows.getFirst());
    }

    private MaterializedViewRefreshRunSummary runRefresh(String triggerMode, String triggerReference, Set<String> views) {
        String runId = "h22-mv-refresh-" + UUID.randomUUID();
        Instant startedAt = Instant.now();

        jdbcTemplate.update(
                """
                        INSERT INTO reporting_read.mv_refresh_run
                        (run_id, trigger_mode, trigger_reference, status, started_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                runId,
                triggerMode,
                triggerReference,
                STATUS_RUNNING,
                timestamp(startedAt)
        );

        List<MaterializedViewRefreshItemSummary> viewRuns = new ArrayList<>();
        String status = STATUS_SUCCESS;
        String errorSample = null;

        for (String viewName : views) {
            Instant viewStartedAt = Instant.now();
            try {
                jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + viewName);
                MaterializedViewRefreshItemSummary success = new MaterializedViewRefreshItemSummary(
                        viewName,
                        STATUS_SUCCESS,
                        "refreshed",
                        viewStartedAt,
                        Instant.now()
                );
                persistViewRun(runId, success);
                viewRuns.add(success);
            } catch (Exception e) {
                status = STATUS_FAILED;
                errorSample = trimError(e.getMessage());

                MaterializedViewRefreshItemSummary failure = new MaterializedViewRefreshItemSummary(
                        viewName,
                        STATUS_FAILED,
                        errorSample,
                        viewStartedAt,
                        Instant.now()
                );
                persistViewRun(runId, failure);
                viewRuns.add(failure);
                break;
            }
        }

        Instant completedAt = Instant.now();
        jdbcTemplate.update(
                """
                        UPDATE reporting_read.mv_refresh_run
                        SET status = ?, completed_at = ?, error_sample = ?
                        WHERE run_id = ?
                        """,
                status,
                timestamp(completedAt),
                errorSample,
                runId
        );

        MaterializedViewRefreshRunSummary summary = new MaterializedViewRefreshRunSummary(
                runId,
                triggerMode,
                triggerReference,
                status,
                startedAt,
                completedAt,
                viewRuns,
                errorSample
        );

        if (STATUS_FAILED.equals(status)) {
            throw new IllegalStateException("Materialized view refresh failed: " + errorSample);
        }

        return summary;
    }

    private List<MaterializedViewRefreshItemSummary> queryViewRuns(String runId) {
        return jdbcTemplate.query(
                """
                        SELECT view_name, status, note, started_at, completed_at
                        FROM reporting_read.mv_refresh_view_run
                        WHERE run_id = ?
                        ORDER BY started_at ASC
                        """,
                ps -> ps.setString(1, runId),
                (rs, rowNum) -> new MaterializedViewRefreshItemSummary(
                        rs.getString("view_name"),
                        rs.getString("status"),
                        rs.getString("note"),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("completed_at"))
                )
        );
    }

    private void persistViewRun(String runId, MaterializedViewRefreshItemSummary summary) {
        jdbcTemplate.update(
                """
                        INSERT INTO reporting_read.mv_refresh_view_run
                        (run_id, view_name, status, note, started_at, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                runId,
                summary.viewName(),
                summary.status(),
                summary.note(),
                timestamp(summary.startedAt()),
                timestamp(summary.completedAt())
        );
    }

    static Set<String> mapSlicesToViews(Set<String> slices) {
        Set<String> views = new LinkedHashSet<>();
        if (slices == null) {
            return views;
        }
        for (String slice : slices) {
            if (slice == null) {
                continue;
            }
            String normalized = slice.trim().toLowerCase();
            if (SLICE_WOS.equals(normalized)) {
                views.add(MV_WOS_TOP_RANKINGS);
            }
            if (SLICE_SCOPUS.equals(normalized)) {
                views.add(MV_SCOPUS_CITATION_CONTEXT);
            }
        }
        return views;
    }

    private static String trimError(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() <= 500) {
            return normalized;
        }
        return normalized.substring(0, 500);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
