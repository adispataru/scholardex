ALTER TABLE reporting_read.scholardex_forum_view
    ADD COLUMN IF NOT EXISTS publisher TEXT,
    ADD COLUMN IF NOT EXISTS isbn TEXT;
