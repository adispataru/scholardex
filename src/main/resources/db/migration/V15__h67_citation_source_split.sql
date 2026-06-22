-- H67: per-publication incoming-citation counts split by the indexing of the CITING paper's forum, for the
-- source-attributed h-index (Scopus-venue h, WoS-venue h) alongside the existing citedByCount-based Scholardex h.
ALTER TABLE reporting_read.scholardex_publication_view
    ADD COLUMN IF NOT EXISTS graph_citation_count  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS scopus_citation_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS wos_citation_count    INTEGER NOT NULL DEFAULT 0;
