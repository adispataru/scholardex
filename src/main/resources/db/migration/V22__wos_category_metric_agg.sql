-- Per-category aggregated JCR metrics for the signed-in WoS rankings UX.
-- Rolls up wos_metric_fact (numeric IF / AIS / RIS values per journal-year) by WoS category, edition,
-- year and metric type, joined through wos_category_fact (which journals belong to a category). Lets the
-- categories list sort by average / top Impact Factor & AIS and drives the per-category trend charts and
-- row sparklines, without aggregating ~1.4M fact rows on every request.
-- Fully rebuilt (TRUNCATE + INSERT..SELECT) by WosProjectionBuilderService; no incremental upsert.

CREATE TABLE IF NOT EXISTS reporting_read.wos_category_metric_agg (
    category_name_canonical TEXT                                        NOT NULL,
    edition_normalized      reporting_read.edition_normalized_enum      NOT NULL,
    year                    INTEGER                                     NOT NULL,
    metric_type             reporting_read.metric_type_enum             NOT NULL,
    journal_count           INTEGER                                     NOT NULL,
    avg_value               DOUBLE PRECISION,
    max_value               DOUBLE PRECISION,
    CONSTRAINT pk_wos_category_metric_agg
        PRIMARY KEY (category_name_canonical, edition_normalized, year, metric_type)
);

-- Latest-per-metric lookups (list columns/sort) and per-category time series (trend chart, sparkline).
CREATE INDEX IF NOT EXISTS idx_wos_category_metric_agg_lookup
    ON reporting_read.wos_category_metric_agg
        (category_name_canonical, edition_normalized, metric_type, year DESC);
