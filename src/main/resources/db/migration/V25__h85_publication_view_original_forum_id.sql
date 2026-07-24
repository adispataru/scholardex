-- H85 — durable venue provenance: when a DBLP evidence re-stamp moves a publication's forum_id onto the
-- conf/X stream forum, the raw per-year proceedings forum (whose name carries publisher signals like
-- "IEEE/ACM") is preserved as original_forum_id so scoring can consult it (2026 OM ACM/EPTCS -> C floor).
-- Additive, nullable column on the publication read projection.
ALTER TABLE reporting_read.scholardex_publication_view
    ADD COLUMN IF NOT EXISTS original_forum_id text;
