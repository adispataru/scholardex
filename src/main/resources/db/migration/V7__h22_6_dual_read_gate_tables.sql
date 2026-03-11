CREATE TABLE IF NOT EXISTS reporting_read.dual_read_gate_run (
    run_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    sample_size INTEGER NOT NULL,
    p95_ratio_threshold DOUBLE PRECISION NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    failed_scenarios INTEGER NOT NULL DEFAULT 0,
    error_sample TEXT
);

CREATE TABLE IF NOT EXISTS reporting_read.dual_read_gate_scenario_run (
    run_id TEXT NOT NULL,
    scenario_id TEXT NOT NULL,
    status TEXT NOT NULL,
    parity_passed BOOLEAN NOT NULL,
    performance_passed BOOLEAN NOT NULL,
    mongo_avg_ms DOUBLE PRECISION,
    mongo_p95_ms DOUBLE PRECISION,
    postgres_avg_ms DOUBLE PRECISION,
    postgres_p95_ms DOUBLE PRECISION,
    p95_ratio DOUBLE PRECISION,
    mismatch_sample TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_dual_read_gate_scenario_run PRIMARY KEY (run_id, scenario_id),
    CONSTRAINT fk_dual_read_gate_scenario_run_run
        FOREIGN KEY (run_id) REFERENCES reporting_read.dual_read_gate_run (run_id)
);

CREATE INDEX IF NOT EXISTS idx_dual_read_gate_run_started_at
    ON reporting_read.dual_read_gate_run (started_at DESC);

CREATE INDEX IF NOT EXISTS idx_dual_read_gate_scenario_run_run_id
    ON reporting_read.dual_read_gate_scenario_run (run_id);
