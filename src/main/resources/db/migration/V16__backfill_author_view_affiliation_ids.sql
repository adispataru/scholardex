-- Backfill scholardex_author_view.affiliation_ids from the author->affiliation edge table.
--
-- V2 keeps an author's affiliations only in scholardex_author_affiliation_fact (the author fact's affiliationIds is
-- never populated). The projection builder now denormalizes them onto the author view, but read models projected
-- before that change have an empty affiliation_ids column. This one-time backfill populates existing rows so consumers
-- can read author_view.affiliation_ids directly (retiring per-consumer edge-read workarounds).
--
-- No-op on a fresh/empty read model: the first projection rebuild populates the column going forward.
UPDATE reporting_read.scholardex_author_view av
SET affiliation_ids = sub.affs
FROM (
    SELECT author_id, array_agg(DISTINCT affiliation_id) AS affs
    FROM reporting_read.scholardex_author_affiliation_fact
    WHERE author_id IS NOT NULL AND affiliation_id IS NOT NULL
    GROUP BY author_id
) sub
WHERE av.id = sub.author_id;
