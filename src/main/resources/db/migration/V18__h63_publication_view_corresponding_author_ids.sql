-- H63: denormalize corresponding-author canonical ids onto the publication read model so the scoring engine can
-- select first-OR-corresponding authorship without an authorship-edge join. Populated by the projection build from
-- ScholardexAuthorshipFact edges where corresponding=true (parallel to author_ids). Empty until the next projection
-- rebuild repopulates the view; the column defaults to '{}' so existing rows remain valid.
ALTER TABLE reporting_read.scholardex_publication_view
    ADD COLUMN IF NOT EXISTS corresponding_author_ids TEXT[] NOT NULL DEFAULT '{}';
