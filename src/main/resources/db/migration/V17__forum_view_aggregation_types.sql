-- Multi-type forums: a canonical forum can be classified differently by each source it merges
-- (e.g. LNCS/LNAI is a "Journal" to WoS/Scopus by ISSN but a "Book Series" to OpenAlex). The single
-- aggregation_type keeps the most-specific primary; aggregation_types keeps the full distinct set so a
-- scorer can pick the appropriate type per context (journal vs conference vs book).
ALTER TABLE reporting_read.scholardex_forum_view
    ADD COLUMN IF NOT EXISTS aggregation_types TEXT[];
