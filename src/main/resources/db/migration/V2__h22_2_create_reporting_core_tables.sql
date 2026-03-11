CREATE TABLE IF NOT EXISTS reporting_read.wos_ranking_view (
    journal_id TEXT PRIMARY KEY,
    name TEXT,
    issn TEXT,
    e_issn TEXT,
    alternative_issns TEXT[] NOT NULL DEFAULT '{}',
    alternative_names TEXT[] NOT NULL DEFAULT '{}',
    name_norm TEXT,
    issn_norm TEXT,
    e_issn_norm TEXT,
    alternative_issns_norm TEXT[] NOT NULL DEFAULT '{}',
    latest_ais_year INTEGER,
    latest_ris_year INTEGER,
    latest_edition_normalized reporting_read.edition_normalized_enum,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS reporting_read.wos_metric_fact (
    id TEXT PRIMARY KEY,
    journal_id TEXT NOT NULL,
    year INTEGER NOT NULL,
    metric_type reporting_read.metric_type_enum NOT NULL,
    value DOUBLE PRECISION,
    source_type reporting_read.wos_source_type_enum,
    source_event_id TEXT,
    source_file TEXT,
    source_version TEXT,
    source_row_item TEXT,
    created_at TIMESTAMPTZ,
    CONSTRAINT fk_wos_metric_fact_journal
        FOREIGN KEY (journal_id) REFERENCES reporting_read.wos_ranking_view (journal_id),
    CONSTRAINT uq_wos_metric_fact UNIQUE (journal_id, year, metric_type)
);

CREATE TABLE IF NOT EXISTS reporting_read.wos_category_fact (
    id TEXT PRIMARY KEY,
    journal_id TEXT NOT NULL,
    year INTEGER NOT NULL,
    category_name_canonical TEXT NOT NULL,
    edition_raw TEXT,
    edition_normalized reporting_read.edition_normalized_enum NOT NULL,
    metric_type reporting_read.metric_type_enum NOT NULL,
    quarter TEXT,
    quartile_rank INTEGER,
    rank INTEGER,
    source_type reporting_read.wos_source_type_enum,
    source_event_id TEXT,
    source_file TEXT,
    source_version TEXT,
    source_row_item TEXT,
    created_at TIMESTAMPTZ,
    CONSTRAINT fk_wos_category_fact_journal
        FOREIGN KEY (journal_id) REFERENCES reporting_read.wos_ranking_view (journal_id),
    CONSTRAINT uq_wos_category_fact UNIQUE (journal_id, year, category_name_canonical, edition_normalized, metric_type)
);

CREATE TABLE IF NOT EXISTS reporting_read.wos_scoring_view (
    id TEXT PRIMARY KEY,
    journal_id TEXT NOT NULL,
    year INTEGER NOT NULL,
    category_name_canonical TEXT NOT NULL,
    edition_normalized reporting_read.edition_normalized_enum NOT NULL,
    metric_type reporting_read.metric_type_enum NOT NULL,
    value DOUBLE PRECISION,
    quarter TEXT,
    quartile_rank INTEGER,
    rank INTEGER,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_wos_scoring_view_journal
        FOREIGN KEY (journal_id) REFERENCES reporting_read.wos_ranking_view (journal_id),
    CONSTRAINT uq_wos_scoring_view UNIQUE (journal_id, year, category_name_canonical, edition_normalized, metric_type)
);

CREATE TABLE IF NOT EXISTS reporting_read.scholardex_publication_view (
    id TEXT PRIMARY KEY,
    doi TEXT,
    doi_normalized TEXT,
    eid TEXT,
    title TEXT,
    subtype TEXT,
    subtype_description TEXT,
    scopus_subtype TEXT,
    scopus_subtype_description TEXT,
    creator TEXT,
    cover_date TEXT,
    cover_display_date TEXT,
    volume TEXT,
    issue_identifier TEXT,
    description TEXT,
    author_count INTEGER,
    corresponding_authors TEXT[] NOT NULL DEFAULT '{}',
    open_access BOOLEAN NOT NULL DEFAULT FALSE,
    freetoread TEXT,
    freetoread_label TEXT,
    funding_id TEXT,
    article_number TEXT,
    page_range TEXT,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    author_ids TEXT[] NOT NULL DEFAULT '{}',
    affiliation_ids TEXT[] NOT NULL DEFAULT '{}',
    forum_id TEXT,
    citing_publication_ids TEXT[] NOT NULL DEFAULT '{}',
    cited_by_count INTEGER,
    wos_id TEXT,
    google_scholar_id TEXT,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    scopus_lineage TEXT,
    wos_lineage TEXT,
    scholar_lineage TEXT,
    linker_version TEXT,
    linker_run_id TEXT,
    linked_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS reporting_read.scholardex_author_view (
    id TEXT PRIMARY KEY,
    name TEXT,
    affiliation_ids TEXT[] NOT NULL DEFAULT '{}',
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    source_event_id TEXT
);

CREATE TABLE IF NOT EXISTS reporting_read.scholardex_forum_view (
    id TEXT PRIMARY KEY,
    publication_name TEXT,
    issn TEXT,
    e_issn TEXT,
    aggregation_type TEXT,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    source_event_id TEXT
);

CREATE TABLE IF NOT EXISTS reporting_read.scholardex_affiliation_view (
    id TEXT PRIMARY KEY,
    name TEXT,
    city TEXT,
    country TEXT,
    build_version TEXT,
    build_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    source_event_id TEXT
);

CREATE TABLE IF NOT EXISTS reporting_read.scholardex_citation_fact (
    id TEXT PRIMARY KEY,
    cited_publication_id TEXT NOT NULL,
    citing_publication_id TEXT NOT NULL,
    source TEXT NOT NULL,
    source_record_id TEXT,
    source_event_id TEXT,
    source_batch_id TEXT,
    source_correlation_id TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_scholardex_citation_fact_cited
        FOREIGN KEY (cited_publication_id) REFERENCES reporting_read.scholardex_publication_view (id),
    CONSTRAINT fk_scholardex_citation_fact_citing
        FOREIGN KEY (citing_publication_id) REFERENCES reporting_read.scholardex_publication_view (id),
    CONSTRAINT uq_scholardex_citation_edge UNIQUE (cited_publication_id, citing_publication_id, source)
);

CREATE TABLE IF NOT EXISTS reporting_read.scholardex_authorship_fact (
    id TEXT PRIMARY KEY,
    publication_id TEXT NOT NULL,
    author_id TEXT NOT NULL,
    source TEXT NOT NULL,
    source_record_id TEXT,
    source_event_id TEXT,
    source_batch_id TEXT,
    source_correlation_id TEXT,
    link_state TEXT,
    link_reason TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_scholardex_authorship_fact_publication
        FOREIGN KEY (publication_id) REFERENCES reporting_read.scholardex_publication_view (id),
    CONSTRAINT fk_scholardex_authorship_fact_author
        FOREIGN KEY (author_id) REFERENCES reporting_read.scholardex_author_view (id),
    CONSTRAINT uq_scholardex_authorship_edge UNIQUE (publication_id, author_id, source)
);

CREATE TABLE IF NOT EXISTS reporting_read.scholardex_author_affiliation_fact (
    id TEXT PRIMARY KEY,
    author_id TEXT NOT NULL,
    affiliation_id TEXT NOT NULL,
    source TEXT NOT NULL,
    source_record_id TEXT,
    source_event_id TEXT,
    source_batch_id TEXT,
    source_correlation_id TEXT,
    link_state TEXT,
    link_reason TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_scholardex_author_affiliation_fact_author
        FOREIGN KEY (author_id) REFERENCES reporting_read.scholardex_author_view (id),
    CONSTRAINT fk_scholardex_author_affiliation_fact_affiliation
        FOREIGN KEY (affiliation_id) REFERENCES reporting_read.scholardex_affiliation_view (id),
    CONSTRAINT uq_scholardex_author_affiliation_edge UNIQUE (author_id, affiliation_id, source)
);
