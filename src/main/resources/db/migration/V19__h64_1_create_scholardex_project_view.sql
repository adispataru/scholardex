-- H64 slice 1 — canonical project read model. Projection of scholardex.project_facts (brainmap RO national + FP7 EU
-- tail; CORDIS/user merge in later). Reports + the entity picker read this; rebuilt full-replacement on each run.
-- No budget column-source from brainmap (always null here); A10's € stays declared on the activity.
CREATE TABLE IF NOT EXISTS reporting_read.scholardex_project_view (
    id TEXT PRIMARY KEY,
    eu_grant_id TEXT,
    code TEXT,
    title TEXT,
    title_normalized TEXT,
    plan TEXT,
    competition TEXT,
    funder TEXT,
    director_first TEXT,
    director_last TEXT,
    director_role TEXT,
    coordinator_name TEXT,
    coordinator_affiliation_id TEXT,
    partner_affiliation_ids TEXT[] NOT NULL DEFAULT '{}',
    brainmap_project_ids TEXT[] NOT NULL DEFAULT '{}',
    start_year INTEGER,
    end_year INTEGER,
    budget BIGINT,
    source TEXT,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_scholardex_project_view_code
    ON reporting_read.scholardex_project_view (code);
CREATE INDEX IF NOT EXISTS idx_scholardex_project_view_eu_grant_id
    ON reporting_read.scholardex_project_view (eu_grant_id);
CREATE INDEX IF NOT EXISTS idx_scholardex_project_view_funder
    ON reporting_read.scholardex_project_view (funder);
CREATE INDEX IF NOT EXISTS idx_scholardex_project_view_coordinator
    ON reporting_read.scholardex_project_view (coordinator_affiliation_id);
CREATE INDEX IF NOT EXISTS idx_scholardex_project_view_title_lower
    ON reporting_read.scholardex_project_view (LOWER(title));
