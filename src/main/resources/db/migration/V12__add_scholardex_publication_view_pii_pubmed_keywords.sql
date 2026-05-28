ALTER TABLE reporting_read.scholardex_publication_view
    ADD COLUMN IF NOT EXISTS pii TEXT,
    ADD COLUMN IF NOT EXISTS pubmed_id TEXT,
    ADD COLUMN IF NOT EXISTS auth_keywords TEXT[];
