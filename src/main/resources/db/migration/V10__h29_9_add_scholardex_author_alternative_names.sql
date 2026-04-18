ALTER TABLE reporting_read.scholardex_author_view
    ADD COLUMN IF NOT EXISTS alternative_names TEXT[] NOT NULL DEFAULT '{}';
