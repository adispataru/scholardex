CREATE MATERIALIZED VIEW IF NOT EXISTS reporting_read.mv_wos_top_rankings_q1_ais AS
SELECT
    year,
    category_name_canonical,
    edition_normalized,
    COUNT(DISTINCT journal_id) AS top_journal_count
FROM reporting_read.wos_scoring_view
WHERE metric_type = 'AIS'
  AND quarter = 'Q1'
GROUP BY year, category_name_canonical, edition_normalized;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mv_wos_top_rankings_q1_ais
    ON reporting_read.mv_wos_top_rankings_q1_ais (year, category_name_canonical, edition_normalized);

CREATE MATERIALIZED VIEW IF NOT EXISTS reporting_read.mv_scholardex_citation_context AS
SELECT
    c.cited_publication_id,
    c.citing_publication_id,
    p.title AS citing_title,
    p.cover_date AS citing_cover_date,
    p.forum_id AS citing_forum_id,
    p.author_ids AS citing_author_ids,
    p.eid AS citing_eid,
    p.wos_id AS citing_wos_id,
    p.google_scholar_id AS citing_google_scholar_id
FROM reporting_read.scholardex_citation_fact c
JOIN reporting_read.scholardex_publication_view p
  ON p.id = c.citing_publication_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mv_scholardex_citation_context_edge
    ON reporting_read.mv_scholardex_citation_context (cited_publication_id, citing_publication_id);

CREATE INDEX IF NOT EXISTS idx_mv_scholardex_citation_context_cited
    ON reporting_read.mv_scholardex_citation_context (cited_publication_id);
