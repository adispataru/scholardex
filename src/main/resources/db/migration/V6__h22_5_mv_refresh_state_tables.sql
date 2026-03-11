CREATE TABLE IF NOT EXISTS reporting_read.mv_refresh_run (
    run_id TEXT PRIMARY KEY,
    trigger_mode TEXT NOT NULL,
    trigger_reference TEXT,
    status TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    error_sample TEXT
);

CREATE TABLE IF NOT EXISTS reporting_read.mv_refresh_view_run (
    run_id TEXT NOT NULL,
    view_name TEXT NOT NULL,
    status TEXT NOT NULL,
    note TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_mv_refresh_view_run PRIMARY KEY (run_id, view_name),
    CONSTRAINT fk_mv_refresh_view_run_run FOREIGN KEY (run_id)
        REFERENCES reporting_read.mv_refresh_run (run_id)
);

CREATE INDEX IF NOT EXISTS idx_mv_refresh_run_started_at
    ON reporting_read.mv_refresh_run (started_at DESC);

CREATE INDEX IF NOT EXISTS idx_mv_refresh_view_run_run_id
    ON reporting_read.mv_refresh_view_run (run_id);
