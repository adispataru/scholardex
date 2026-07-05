-- 2026 Informatică journal rule: a journal's category is the BEST of its AIS and JIF quartile placements.
-- The top-20%-of-Q1 boundary (A* vs A) therefore also needs the IF-side Q1 cohort count — the twin of
-- mv_wos_top_rankings_q1_ais.
CREATE MATERIALIZED VIEW IF NOT EXISTS reporting_read.mv_wos_top_rankings_q1_if AS
SELECT
    year,
    category_name_canonical,
    edition_normalized,
    COUNT(DISTINCT journal_id) AS top_journal_count
FROM reporting_read.wos_scoring_view
WHERE metric_type = 'IF'
  AND quarter = 'Q1'
GROUP BY year, category_name_canonical, edition_normalized;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mv_wos_top_rankings_q1_if
    ON reporting_read.mv_wos_top_rankings_q1_if (year, category_name_canonical, edition_normalized);
