-- H79 slice 5b — carry the DOAJ APC flag on the forum membership snapshot so scoring can gate fee-conditioned
-- (gold-OA) journals out of the 2026 Informatică threshold points. Only DOAJ rows populate it (DOAJ lists fully-OA
-- journals); other databases leave it null. Nullable — pre-existing rows are null until the next projection.
ALTER TABLE reporting_read.scholardex_forum_membership_view
    ADD COLUMN IF NOT EXISTS apc BOOLEAN;
