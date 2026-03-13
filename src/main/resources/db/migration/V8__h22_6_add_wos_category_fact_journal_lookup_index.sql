CREATE INDEX IF NOT EXISTS idx_wos_category_fact_journal_edition_metric_year
    ON reporting_read.wos_category_fact (journal_id, edition_normalized, metric_type, year);
