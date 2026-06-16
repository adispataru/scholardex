-- H66 A1: Canonical Forum registry — scaffolding only (no data written until A2–A4 / B2).
--
-- Adds the C-scalar columns to the forum view and creates the three forum-id-keyed sibling
-- views. Time-resolution follows the data:
--   * metric + category views are YEAR-TRUE (sourced from JCR wos.metric_facts / wos.category_facts,
--     projected through forum.wosForumIds in move B2);
--   * membership view is SNAPSHOT-ONLY (current edition/DOAJ/ERIH/Scopus/CNCS, as_of-stamped, from A3/A4).
--
-- No FK to scholardex_forum_view on purpose: that table is rebuilt via a single TRUNCATE
-- (ScholardexProjectionBuilderService), and Postgres rejects TRUNCATE on any FK-referenced table even
-- when the child is empty. These are rebuildable read projections; integrity is enforced by the
-- move-B projection step. forum_id is indexed for lookups instead.

-- C-scalars on the forum row (single-snapshot, single-valued classification).
ALTER TABLE reporting_read.scholardex_forum_view
    ADD COLUMN IF NOT EXISTS forum_type TEXT,
    ADD COLUMN IF NOT EXISTS asjc TEXT[];

-- Year-true: journal-level metrics (AIS/IF/RIS) per (forum, year). Re-key of wos_metric_fact.
CREATE TABLE IF NOT EXISTS reporting_read.scholardex_forum_metric_view (
    id TEXT PRIMARY KEY,
    forum_id TEXT NOT NULL,
    year INTEGER NOT NULL,
    metric_type reporting_read.metric_type_enum NOT NULL,
    value DOUBLE PRECISION,
    source TEXT,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_forum_metric_view UNIQUE (forum_id, year, metric_type)
);
CREATE INDEX IF NOT EXISTS idx_forum_metric_view_forum_id
    ON reporting_read.scholardex_forum_metric_view (forum_id);

-- Year-true: edition + category + quartile travel together per (forum, year). Re-key of wos_category_fact.
CREATE TABLE IF NOT EXISTS reporting_read.scholardex_forum_category_view (
    id TEXT PRIMARY KEY,
    forum_id TEXT NOT NULL,
    year INTEGER NOT NULL,
    edition reporting_read.edition_normalized_enum NOT NULL,
    category TEXT NOT NULL,
    metric_type reporting_read.metric_type_enum NOT NULL,
    quarter TEXT,
    quartile_rank INTEGER,
    rank INTEGER,
    source TEXT,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_forum_category_view UNIQUE (forum_id, year, edition, category, metric_type)
);
CREATE INDEX IF NOT EXISTS idx_forum_category_view_forum_id
    ON reporting_read.scholardex_forum_category_view (forum_id);

-- Snapshot-only: current membership in a database/list (no year — as_of-stamped).
-- database spans editions (current) + DOAJ/ERIH/SCOPUS + CNCS:<tier>, so it is TEXT, not the edition enum.
CREATE TABLE IF NOT EXISTS reporting_read.scholardex_forum_membership_view (
    id TEXT PRIMARY KEY,
    forum_id TEXT NOT NULL,
    database TEXT NOT NULL,
    member BOOLEAN NOT NULL DEFAULT TRUE,
    as_of TEXT,
    source TEXT,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_forum_membership_view UNIQUE (forum_id, database, source)
);
CREATE INDEX IF NOT EXISTS idx_forum_membership_view_forum_id
    ON reporting_read.scholardex_forum_membership_view (forum_id);
