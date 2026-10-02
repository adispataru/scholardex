-- H131 — the cited-by count each source reports, kept apart from cited_by_count (the platform's scalar, the
-- maximum of the two) so a displayed number can say where it comes from: the Scopus one with its attribution
-- link, the OpenAlex one shown publicly. Additive, nullable: NULL until the next derivation of a record.
ALTER TABLE reporting_read.scholardex_publication_view
    ADD COLUMN IF NOT EXISTS cited_by_count_scopus integer,
    ADD COLUMN IF NOT EXISTS cited_by_count_openalex integer;
