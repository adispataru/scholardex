CREATE TABLE IF NOT EXISTS reporting_read.projection_checkpoint (
    slice_name TEXT PRIMARY KEY,
    source_fingerprint TEXT NOT NULL,
    last_run_id TEXT NOT NULL,
    last_success_at TIMESTAMPTZ NOT NULL,
    last_mode TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS reporting_read.projection_run (
    run_id TEXT PRIMARY KEY,
    mode TEXT NOT NULL,
    status TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    error_sample TEXT
);

CREATE TABLE IF NOT EXISTS reporting_read.projection_slice_run (
    id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL,
    slice_name TEXT NOT NULL,
    status TEXT NOT NULL,
    source_fingerprint TEXT,
    inserted_rows BIGINT NOT NULL DEFAULT 0,
    note TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_projection_slice_run_run
        FOREIGN KEY (run_id) REFERENCES reporting_read.projection_run (run_id)
);

CREATE INDEX IF NOT EXISTS idx_projection_run_started_at
    ON reporting_read.projection_run (started_at DESC);

CREATE INDEX IF NOT EXISTS idx_projection_slice_run_run_id
    ON reporting_read.projection_slice_run (run_id);

CREATE INDEX IF NOT EXISTS idx_projection_slice_run_slice_started
    ON reporting_read.projection_slice_run (slice_name, started_at DESC);
