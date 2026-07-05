-- Add a median to the per-category metric aggregate. Average IF/AIS is skewed by a few very-high-impact
-- journals (e.g. Oncology avg IF ~6 but top ~292), so the median gives a more representative "typical
-- journal" figure for the category header. Populated by WosProjectionBuilderService.rebuildCategoryMetricAgg.
ALTER TABLE reporting_read.wos_category_metric_agg
    ADD COLUMN IF NOT EXISTS median_value DOUBLE PRECISION;
