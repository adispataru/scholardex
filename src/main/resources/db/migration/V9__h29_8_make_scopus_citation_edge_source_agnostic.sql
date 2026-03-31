ALTER TABLE reporting_read.scholardex_citation_fact
    DROP CONSTRAINT IF EXISTS uq_scholardex_citation_edge;

WITH ranked_duplicates AS (
    SELECT ctid,
           ROW_NUMBER() OVER (
               PARTITION BY cited_publication_id, citing_publication_id
               ORDER BY updated_at DESC NULLS LAST, created_at DESC NULLS LAST, id ASC
           ) AS row_num
    FROM reporting_read.scholardex_citation_fact
)
DELETE FROM reporting_read.scholardex_citation_fact target
USING ranked_duplicates duplicates
WHERE target.ctid = duplicates.ctid
  AND duplicates.row_num > 1;

ALTER TABLE reporting_read.scholardex_citation_fact
    ADD CONSTRAINT uq_scholardex_citation_edge
        UNIQUE (cited_publication_id, citing_publication_id);
