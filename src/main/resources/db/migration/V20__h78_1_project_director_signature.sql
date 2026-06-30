-- H78 slice 1 — director→researcher attribution for the workspace "My projects" panel. Add a name-signature column
-- (word-order/diacritic-insensitive key over the project director's first+last name, computed at projection time via
-- ProjectCanonicalizationService.signature) so a researcher's own signature can be matched by exact equality.
-- Backfilled on the next projection run (reporting_read.scholardex_project_view is full-replacement rebuilt).
ALTER TABLE reporting_read.scholardex_project_view
    ADD COLUMN IF NOT EXISTS director_signature TEXT;

CREATE INDEX IF NOT EXISTS idx_scholardex_project_view_director_signature
    ON reporting_read.scholardex_project_view (director_signature);
