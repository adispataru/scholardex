CREATE INDEX IF NOT EXISTS idx_wos_ranking_issn_norm
    ON reporting_read.wos_ranking_view (issn_norm);

CREATE INDEX IF NOT EXISTS idx_wos_ranking_e_issn_norm
    ON reporting_read.wos_ranking_view (e_issn_norm);

CREATE INDEX IF NOT EXISTS idx_wos_ranking_alt_issns_norm_gin
    ON reporting_read.wos_ranking_view USING GIN (alternative_issns_norm);

CREATE INDEX IF NOT EXISTS idx_wos_ranking_name_norm
    ON reporting_read.wos_ranking_view (name_norm);

CREATE INDEX IF NOT EXISTS idx_wos_scoring_lookup
    ON reporting_read.wos_scoring_view (metric_type, year, quarter, category_name_canonical, edition_normalized, journal_id);

CREATE INDEX IF NOT EXISTS idx_wos_scoring_journal_timeline
    ON reporting_read.wos_scoring_view (journal_id, metric_type, year, edition_normalized);

CREATE INDEX IF NOT EXISTS idx_wos_metric_fact_journal_year
    ON reporting_read.wos_metric_fact (journal_id, year);

CREATE INDEX IF NOT EXISTS idx_wos_category_fact_lookup
    ON reporting_read.wos_category_fact (category_name_canonical, year, metric_type, edition_normalized, journal_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_scholardex_publication_eid
    ON reporting_read.scholardex_publication_view (eid)
    WHERE eid IS NOT NULL AND eid <> '';

CREATE UNIQUE INDEX IF NOT EXISTS uq_scholardex_publication_wos_id
    ON reporting_read.scholardex_publication_view (wos_id)
    WHERE wos_id IS NOT NULL AND wos_id <> '';

CREATE UNIQUE INDEX IF NOT EXISTS uq_scholardex_publication_google_scholar_id
    ON reporting_read.scholardex_publication_view (google_scholar_id)
    WHERE google_scholar_id IS NOT NULL AND google_scholar_id <> '';

CREATE INDEX IF NOT EXISTS idx_scholardex_publication_title_lower
    ON reporting_read.scholardex_publication_view (LOWER(title));

CREATE INDEX IF NOT EXISTS idx_scholardex_publication_cover_date
    ON reporting_read.scholardex_publication_view (cover_date);

CREATE INDEX IF NOT EXISTS idx_scholardex_publication_author_ids_gin
    ON reporting_read.scholardex_publication_view USING GIN (author_ids);

CREATE INDEX IF NOT EXISTS idx_scholardex_publication_affiliation_ids_gin
    ON reporting_read.scholardex_publication_view USING GIN (affiliation_ids);

CREATE INDEX IF NOT EXISTS idx_scholardex_publication_doi_norm
    ON reporting_read.scholardex_publication_view (doi_normalized);

CREATE INDEX IF NOT EXISTS idx_scholardex_citation_cited
    ON reporting_read.scholardex_citation_fact (cited_publication_id);

CREATE INDEX IF NOT EXISTS idx_scholardex_citation_citing
    ON reporting_read.scholardex_citation_fact (citing_publication_id);

CREATE INDEX IF NOT EXISTS idx_scholardex_authorship_author
    ON reporting_read.scholardex_authorship_fact (author_id);

CREATE INDEX IF NOT EXISTS idx_scholardex_authorship_publication
    ON reporting_read.scholardex_authorship_fact (publication_id);

CREATE INDEX IF NOT EXISTS idx_scholardex_author_affiliation_author
    ON reporting_read.scholardex_author_affiliation_fact (author_id);

CREATE INDEX IF NOT EXISTS idx_scholardex_author_affiliation_affiliation
    ON reporting_read.scholardex_author_affiliation_fact (affiliation_id);

CREATE INDEX IF NOT EXISTS idx_scholardex_author_name_lower
    ON reporting_read.scholardex_author_view (LOWER(name));

CREATE INDEX IF NOT EXISTS idx_scholardex_author_affiliation_ids_gin
    ON reporting_read.scholardex_author_view USING GIN (affiliation_ids);

CREATE INDEX IF NOT EXISTS idx_scholardex_affiliation_country
    ON reporting_read.scholardex_affiliation_view (country);

CREATE INDEX IF NOT EXISTS idx_scholardex_affiliation_name_lower
    ON reporting_read.scholardex_affiliation_view (LOWER(name));
