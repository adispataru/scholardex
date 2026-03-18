package ro.uvt.pokedex.core.service.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumSearchView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusFundingFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationTouch;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorTouch;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationTouch;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumTouch;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationTouch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ScopusCanonicalIndexMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(ScopusCanonicalIndexMaintenanceService.class);

    static final String IDX_IMPORT_UNIQ = "uniq_scopus_import_event_idempotence";
    static final String IDX_IMPORT_BATCH_CORRELATION = "idx_scopus_import_batch_correlation";

    static final String IDX_PUBLICATION_UNIQ_EID = "uniq_scopus_publication_fact_eid";
    static final String IDX_PUBLICATION_AUTHOR = "idx_scopus_publication_author";
    static final String IDX_PUBLICATION_AFFILIATION = "idx_scopus_publication_affiliation";
    static final String IDX_PUBLICATION_FORUM_COVERDATE = "idx_scopus_publication_forum_coverdate";

    static final String IDX_CITATION_UNIQ_EDGE = "uniq_scopus_citation_fact_edge";
    static final String IDX_CITATION_CITED = "idx_scopus_citation_cited";

    static final String IDX_FORUM_UNIQ_SOURCE_ID = "uniq_scopus_forum_fact_source_id";
    static final String IDX_FORUM_NAME = "idx_scopus_forum_name";
    static final String IDX_FORUM_ISSN = "idx_scopus_forum_issn";
    static final String IDX_FORUM_EISSN = "idx_scopus_forum_eissn";
    static final String IDX_FORUM_AGG = "idx_scopus_forum_agg";

    static final String IDX_AUTHOR_UNIQ = "uniq_scopus_author_fact_author_id";
    static final String IDX_AUTHOR_NAME = "idx_scopus_author_name";
    static final String IDX_AUTHOR_AFFILIATIONS = "idx_scopus_author_affiliations";

    static final String IDX_AFFILIATION_UNIQ = "uniq_scopus_affiliation_fact_afid";
    static final String IDX_AFFILIATION_NAME = "idx_scopus_affiliation_name";
    static final String IDX_AFFILIATION_CITY = "idx_scopus_affiliation_city";
    static final String IDX_AFFILIATION_COUNTRY = "idx_scopus_affiliation_country";

    static final String IDX_FUNDING_UNIQ = "uniq_scopus_funding_fact_key";
    static final String IDX_FUNDING_SPONSOR = "idx_scopus_funding_sponsor";
    static final String IDX_TOUCH_AFFILIATION_UNIQ = "uniq_scopus_affiliation_touch";
    static final String IDX_TOUCH_AFFILIATION_TOUCHED = "idx_scopus_affiliation_touch_touched";
    static final String IDX_TOUCH_AUTHOR_UNIQ = "uniq_scopus_author_touch";
    static final String IDX_TOUCH_AUTHOR_TOUCHED = "idx_scopus_author_touch_touched";
    static final String IDX_TOUCH_FORUM_UNIQ = "uniq_scopus_forum_touch";
    static final String IDX_TOUCH_FORUM_TOUCHED = "idx_scopus_forum_touch_touched";
    static final String IDX_TOUCH_PUBLICATION_UNIQ = "uniq_scopus_publication_touch";
    static final String IDX_TOUCH_PUBLICATION_TOUCHED = "idx_scopus_publication_touch_touched";
    static final String IDX_TOUCH_CITATION_UNIQ = "uniq_scopus_citation_touch";
    static final String IDX_TOUCH_CITATION_TOUCHED = "idx_scopus_citation_touch_touched";

    static final String IDX_FORUM_VIEW_NAME = "idx_scopus_forum_view_name";
    static final String IDX_FORUM_VIEW_ISSN = "idx_scopus_forum_view_issn";
    static final String IDX_FORUM_VIEW_EISSN = "idx_scopus_forum_view_eissn";
    static final String IDX_FORUM_VIEW_AGG = "idx_scopus_forum_view_agg";

    static final String IDX_AUTHOR_VIEW_NAME = "idx_scopus_author_view_name";
    static final String IDX_AUTHOR_VIEW_AFFILIATIONS = "idx_scopus_author_view_affiliations";

    static final String IDX_AFFILIATION_VIEW_NAME = "idx_scopus_affiliation_view_name";
    static final String IDX_AFFILIATION_VIEW_CITY = "idx_scopus_affiliation_view_city";
    static final String IDX_AFFILIATION_VIEW_COUNTRY = "idx_scopus_affiliation_view_country";
    static final String IDX_AFFILIATION_VIEW_AFID = "idx_scopus_affiliation_view_afid";

    static final String IDX_SCHOLARDEX_FORUM_VIEW_NAME = "idx_scholardex_forum_view_name";
    static final String IDX_SCHOLARDEX_FORUM_VIEW_ISSN = "idx_scholardex_forum_view_issn";
    static final String IDX_SCHOLARDEX_FORUM_VIEW_EISSN = "idx_scholardex_forum_view_eissn";
    static final String IDX_SCHOLARDEX_FORUM_VIEW_AGG = "idx_scholardex_forum_view_agg";

    static final String IDX_SCHOLARDEX_AUTHOR_VIEW_NAME = "idx_scholardex_author_view_name";
    static final String IDX_SCHOLARDEX_AUTHOR_VIEW_AFFILIATIONS = "idx_scholardex_author_view_affiliations";

    static final String IDX_SCHOLARDEX_AFFILIATION_VIEW_NAME = "idx_scholardex_affiliation_view_name";
    static final String IDX_SCHOLARDEX_AFFILIATION_VIEW_CITY = "idx_scholardex_affiliation_view_city";
    static final String IDX_SCHOLARDEX_AFFILIATION_VIEW_COUNTRY = "idx_scholardex_affiliation_view_country";

    static final String IDX_MERGED_PUBLICATION_EID = "idx_scholardex_publication_eid";
    static final String IDX_MERGED_PUBLICATION_DOI_NORMALIZED = "idx_scholardex_publication_doi_normalized";
    static final String IDX_MERGED_PUBLICATION_TITLE = "idx_scholardex_publication_title";
    static final String IDX_MERGED_PUBLICATION_COVERDATE = "idx_scholardex_publication_coverdate";
    static final String IDX_MERGED_PUBLICATION_AUTHORS = "idx_scholardex_publication_authors";
    static final String IDX_MERGED_PUBLICATION_AFFILIATIONS = "idx_scholardex_publication_affiliations";
    static final String IDX_MERGED_PUBLICATION_FORUM = "idx_scholardex_publication_forum";
    static final String IDX_MERGED_PUBLICATION_WOS = "idx_scholardex_publication_wosid";
    static final String IDX_MERGED_PUBLICATION_GOOGLE_SCHOLAR = "idx_scholardex_publication_google_scholar_id";

    static final String IDX_CANON_PUBLICATION_DOI_NORMALIZED = "idx_scholardex_publication_fact_doi_normalized";
    static final String IDX_CANON_PUBLICATION_TITLE_NORMALIZED = "idx_scholardex_publication_fact_title_normalized";
    static final String IDX_CANON_PUBLICATION_EID = "uniq_scholardex_publication_fact_eid";
    static final String IDX_CANON_PUBLICATION_WOS = "uniq_scholardex_publication_fact_wos_id";
    static final String IDX_CANON_PUBLICATION_GS = "uniq_scholardex_publication_fact_google_scholar_id";
    static final String IDX_CANON_PUBLICATION_USER = "uniq_scholardex_publication_fact_user_source_id";
    static final String IDX_CANON_PUBLICATION_FORUM = "idx_scholardex_publication_fact_forum";
    static final String IDX_CANON_PUBLICATION_AUTHORS = "idx_scholardex_publication_fact_authors";
    static final String IDX_CANON_PUBLICATION_AFFILIATIONS = "idx_scholardex_publication_fact_affiliations";
    static final String IDX_CANON_PUBLICATION_PENDING_AUTHOR_SOURCE_IDS = "idx_scholardex_publication_fact_pending_author_source_ids";

    static final String IDX_CANON_AUTHOR_SCOPUS = "uniq_scholardex_author_scopus_id";
    static final String IDX_CANON_AUTHOR_NAME_NORMALIZED = "idx_scholardex_author_name_normalized";
    static final String IDX_CANON_AUTHOR_AFFILIATIONS = "idx_scholardex_author_affiliations";
    static final String IDX_CANON_AUTHOR_PENDING_AFF_SOURCE_IDS = "idx_scholardex_author_pending_aff_source_ids";

    static final String IDX_CANON_AFFILIATION_SCOPUS = "uniq_scholardex_affiliation_scopus_id";
    static final String IDX_CANON_AFFILIATION_NAME_NORMALIZED = "idx_scholardex_affiliation_name_normalized";
    static final String IDX_CANON_AFFILIATION_COUNTRY = "idx_scholardex_affiliation_country";

    static final String IDX_CANON_FORUM_SCOPUS = "uniq_scholardex_forum_scopus_id";
    static final String IDX_CANON_FORUM_WOS = "uniq_scholardex_forum_wos_id";
    static final String IDX_CANON_FORUM_NAME_NORMALIZED = "idx_scholardex_forum_name_normalized";
    static final String IDX_CANON_FORUM_ISSN = "idx_scholardex_forum_issn";
    static final String IDX_CANON_FORUM_EISSN = "idx_scholardex_forum_eissn";
    static final String IDX_CANON_FORUM_ALIAS_ISSNS = "idx_scholardex_forum_alias_issns";
    static final String IDX_CANON_FORUM_AGG_TYPE = "idx_scholardex_forum_aggregation_type";

    static final String IDX_AUTHORSHIP_UNIQ_EDGE = "uniq_scholardex_authorship_edge";
    static final String IDX_AUTHORSHIP_PUBLICATION = "idx_scholardex_authorship_publication";
    static final String IDX_AUTHORSHIP_AUTHOR = "idx_scholardex_authorship_author";

    static final String IDX_CANON_CITATION_UNIQ_EDGE = "uniq_scholardex_citation_edge";
    static final String IDX_CANON_CITATION_CITED = "idx_scholardex_citation_cited";
    static final String IDX_CANON_CITATION_CITING = "idx_scholardex_citation_citing";

    static final String IDX_AUTHOR_AFFILIATION_UNIQ_EDGE = "uniq_scholardex_author_affiliation_edge";
    static final String IDX_AUTHOR_AFFILIATION_AUTHOR = "idx_scholardex_author_affiliation_author";
    static final String IDX_AUTHOR_AFFILIATION_AFFILIATION = "idx_scholardex_author_affiliation_affiliation";
    static final String IDX_PUBLICATION_AUTHOR_AFFILIATION_UNIQ_EDGE = "uniq_scholardex_publication_author_affiliation_edge";
    static final String IDX_PUBLICATION_AUTHOR_AFFILIATION_PUBLICATION = "idx_scholardex_publication_author_affiliation_publication";
    static final String IDX_PUBLICATION_AUTHOR_AFFILIATION_AUTHOR = "idx_scholardex_publication_author_affiliation_author";
    static final String IDX_PUBLICATION_AUTHOR_AFFILIATION_AFFILIATION = "idx_scholardex_publication_author_affiliation_affiliation";

    static final String IDX_SOURCE_LINK_UNIQ = "uniq_scholardex_source_link";
    static final String IDX_SOURCE_LINK_CANONICAL = "idx_scholardex_source_link_canonical";
    static final String IDX_SOURCE_LINK_SOURCE_RECORD = "idx_scholardex_source_link_source_record";
    static final String IDX_SOURCE_LINK_ENTITY_CANONICAL = "idx_scholardex_source_link_entity_canonical";
    static final String IDX_SOURCE_LINK_STATE_UPDATED = "idx_scholardex_source_link_state_updated";
    static final String IDX_SOURCE_LINK_BATCH_ENTITY = "idx_scholardex_source_link_batch_entity";
    static final String IDX_SOURCE_LINK_CORRELATION_ENTITY = "idx_scholardex_source_link_correlation_entity";

    static final String IDX_IDENTITY_CONFLICT_OPEN = "uniq_scholardex_open_identity_conflict";
    static final String IDX_IDENTITY_CONFLICT_STATUS = "idx_scholardex_identity_conflict_status";

    private final MongoTemplate mongoTemplate;

    public ScopusCanonicalIndexMaintenanceService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public ScopusCanonicalIndexEnsureResult ensureIndexes() {
        List<String> created = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        ensureImportIndexes(created, present, invalid, errors);
        ensurePublicationFactIndexes(created, present, invalid, errors);
        ensureCitationFactIndexes(created, present, invalid, errors);
        ensureForumFactIndexes(created, present, invalid, errors);
        ensureAuthorFactIndexes(created, present, invalid, errors);
        ensureAffiliationFactIndexes(created, present, invalid, errors);
        ensureFundingFactIndexes(created, present, invalid, errors);
        ensureTouchQueueIndexes(created, present, invalid, errors);
        ensureForumViewIndexes(created, present, invalid, errors);
        ensureAuthorViewIndexes(created, present, invalid, errors);
        ensureAffiliationViewIndexes(created, present, invalid, errors);
        ensureScholardexForumViewIndexes(created, present, invalid, errors);
        ensureScholardexAuthorViewIndexes(created, present, invalid, errors);
        ensureScholardexAffiliationViewIndexes(created, present, invalid, errors);
        ensureCanonicalPublicationFactIndexes(created, present, invalid, errors);
        ensureCanonicalAuthorFactIndexes(created, present, invalid, errors);
        ensureCanonicalAffiliationFactIndexes(created, present, invalid, errors);
        ensureCanonicalForumFactIndexes(created, present, invalid, errors);
        ensureAuthorshipIndexes(created, present, invalid, errors);
        ensureCanonicalCitationIndexes(created, present, invalid, errors);
        ensureAuthorAffiliationIndexes(created, present, invalid, errors);
        ensurePublicationAuthorAffiliationIndexes(created, present, invalid, errors);
        ensureSourceLinkIndexes(created, present, invalid, errors);
        ensureIdentityConflictIndexes(created, present, invalid, errors);
        ensureMergedPublicationViewIndexes(created, present, invalid, errors);

        ScopusCanonicalIndexEnsureResult result = new ScopusCanonicalIndexEnsureResult(created, present, invalid, errors);
        log.info("Scopus canonical index ensure summary: created={}, present={}, invalid={}, errors={}",
                created.size(), present.size(), invalid.size(), errors.size());
        return result;
    }

    private void ensureImportIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusImportEvent.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_IMPORT_UNIQ, true,
                List.of(field("entityType"), field("source"), field("sourceRecordId"), field("payloadHash"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_IMPORT_BATCH_CORRELATION, false,
                List.of(field("batchId"), field("correlationId"), field("entityType"))),
                created, present, invalid, errors);
    }

    private void ensurePublicationFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusPublicationFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_PUBLICATION_UNIQ_EID, true, List.of(field("eid"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_PUBLICATION_AUTHOR, false, List.of(field("authors"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_PUBLICATION_AFFILIATION, false, List.of(field("affiliations"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_PUBLICATION_FORUM_COVERDATE, false, List.of(field("forumId"), field("coverDate"))),
                created, present, invalid, errors);
    }

    private void ensureCitationFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusCitationFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CITATION_UNIQ_EDGE, true, List.of(field("citedEid"), field("citingEid"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CITATION_CITED, false, List.of(field("citedEid"))),
                created, present, invalid, errors);
    }

    private void ensureForumFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusForumFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_UNIQ_SOURCE_ID, true, List.of(field("sourceId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_NAME, false, List.of(field("publicationName"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_ISSN, false, List.of(field("issn"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_EISSN, false, List.of(field("eIssn"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_AGG, false, List.of(field("aggregationType"))),
                created, present, invalid, errors);
    }

    private void ensureAuthorFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusAuthorFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHOR_UNIQ, true, List.of(field("authorId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHOR_NAME, false, List.of(field("name"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHOR_AFFILIATIONS, false, List.of(field("affiliationIds"))),
                created, present, invalid, errors);
    }

    private void ensureAffiliationFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusAffiliationFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AFFILIATION_UNIQ, true, List.of(field("afid"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AFFILIATION_NAME, false, List.of(field("name"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AFFILIATION_CITY, false, List.of(field("city"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AFFILIATION_COUNTRY, false, List.of(field("country"))),
                created, present, invalid, errors);
    }

    private void ensureFundingFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusFundingFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FUNDING_UNIQ, true, List.of(field("fundingKey"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FUNDING_SPONSOR, false, List.of(field("sponsor"))),
                created, present, invalid, errors);
    }

    private void ensureTouchQueueIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations affiliationOps = mongoTemplate.indexOps(ScopusAffiliationTouch.class);
        ensureNamedIndex(affiliationOps, new IndexDefinition(IDX_TOUCH_AFFILIATION_UNIQ, true, List.of(field("source"), field("afid"))),
                created, present, invalid, errors);
        ensureNamedIndex(affiliationOps, new IndexDefinition(IDX_TOUCH_AFFILIATION_TOUCHED, false, List.of(field("touchedAt"))),
                created, present, invalid, errors);

        IndexOperations authorOps = mongoTemplate.indexOps(ScopusAuthorTouch.class);
        ensureNamedIndex(authorOps, new IndexDefinition(IDX_TOUCH_AUTHOR_UNIQ, true, List.of(field("source"), field("authorId"))),
                created, present, invalid, errors);
        ensureNamedIndex(authorOps, new IndexDefinition(IDX_TOUCH_AUTHOR_TOUCHED, false, List.of(field("touchedAt"))),
                created, present, invalid, errors);

        IndexOperations forumOps = mongoTemplate.indexOps(ScopusForumTouch.class);
        ensureNamedIndex(forumOps, new IndexDefinition(IDX_TOUCH_FORUM_UNIQ, true, List.of(field("source"), field("sourceId"))),
                created, present, invalid, errors);
        ensureNamedIndex(forumOps, new IndexDefinition(IDX_TOUCH_FORUM_TOUCHED, false, List.of(field("touchedAt"))),
                created, present, invalid, errors);

        IndexOperations publicationOps = mongoTemplate.indexOps(ScopusPublicationTouch.class);
        ensureNamedIndex(publicationOps, new IndexDefinition(IDX_TOUCH_PUBLICATION_UNIQ, true, List.of(field("source"), field("eid"))),
                created, present, invalid, errors);
        ensureNamedIndex(publicationOps, new IndexDefinition(IDX_TOUCH_PUBLICATION_TOUCHED, false, List.of(field("touchedAt"))),
                created, present, invalid, errors);

        IndexOperations citationOps = mongoTemplate.indexOps(ScopusCitationTouch.class);
        ensureNamedIndex(citationOps, new IndexDefinition(IDX_TOUCH_CITATION_UNIQ, true, List.of(field("source"), field("citedEid"), field("citingEid"))),
                created, present, invalid, errors);
        ensureNamedIndex(citationOps, new IndexDefinition(IDX_TOUCH_CITATION_TOUCHED, false, List.of(field("touchedAt"))),
                created, present, invalid, errors);
    }

    private void ensureForumViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusForumSearchView.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_VIEW_NAME, false, List.of(field("publicationName"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_VIEW_ISSN, false, List.of(field("issn"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_VIEW_EISSN, false, List.of(field("eIssn"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_FORUM_VIEW_AGG, false, List.of(field("aggregationType"))),
                created, present, invalid, errors);
    }

    private void ensureAuthorViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusAuthorSearchView.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHOR_VIEW_NAME, false, List.of(field("name"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHOR_VIEW_AFFILIATIONS, false, List.of(field("affiliationIds"))),
                created, present, invalid, errors);
    }

    private void ensureAffiliationViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScopusAffiliationSearchView.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AFFILIATION_VIEW_NAME, false, List.of(field("name"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AFFILIATION_VIEW_CITY, false, List.of(field("city"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AFFILIATION_VIEW_COUNTRY, false, List.of(field("country"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AFFILIATION_VIEW_AFID, false, List.of(field("_id"))),
                created, present, invalid, errors);
    }

    private void ensureScholardexForumViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexForumView.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_FORUM_VIEW_NAME, false, List.of(field("publicationName"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_FORUM_VIEW_ISSN, false, List.of(field("issn"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_FORUM_VIEW_EISSN, false, List.of(field("eIssn"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_FORUM_VIEW_AGG, false, List.of(field("aggregationType"))),
                created, present, invalid, errors);
    }

    private void ensureScholardexAuthorViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexAuthorView.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_AUTHOR_VIEW_NAME, false, List.of(field("name"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_AUTHOR_VIEW_AFFILIATIONS, false, List.of(field("affiliationIds"))),
                created, present, invalid, errors);
    }

    private void ensureScholardexAffiliationViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexAffiliationView.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_AFFILIATION_VIEW_NAME, false, List.of(field("name"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_AFFILIATION_VIEW_CITY, false, List.of(field("city"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SCHOLARDEX_AFFILIATION_VIEW_COUNTRY, false, List.of(field("country"))),
                created, present, invalid, errors);
    }

    private void ensureMergedPublicationViewIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexPublicationView.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_EID, false, List.of(field("eid"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_DOI_NORMALIZED, false, List.of(field("doiNormalized"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_TITLE, false, List.of(field("title"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_COVERDATE, false, List.of(field("coverDate"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_AUTHORS, false, List.of(field("authorIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_AFFILIATIONS, false, List.of(field("affiliationIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_FORUM, false, List.of(field("forumId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_WOS, false, List.of(field("wosId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_MERGED_PUBLICATION_GOOGLE_SCHOLAR, false, List.of(field("googleScholarId"))),
                created, present, invalid, errors);
    }

    private void ensureCanonicalPublicationFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexPublicationFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_EID, true, true, List.of(field("eid"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_WOS, true, true, List.of(field("wosId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_GS, true, true, List.of(field("googleScholarId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_USER, true, true, List.of(field("userSourceId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_DOI_NORMALIZED, true, true, List.of(field("doiNormalized"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_TITLE_NORMALIZED, false, List.of(field("titleNormalized"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_FORUM, false, List.of(field("forumId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_AUTHORS, false, List.of(field("authorIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_AFFILIATIONS, false, List.of(field("affiliationIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_PUBLICATION_PENDING_AUTHOR_SOURCE_IDS, false, List.of(field("pendingAuthorSourceIds"))),
                created, present, invalid, errors);
    }

    private void ensureAuthorshipIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexAuthorshipFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHORSHIP_UNIQ_EDGE, true,
                        List.of(field("publicationId"), field("authorId"), field("source"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHORSHIP_PUBLICATION, false, List.of(field("publicationId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHORSHIP_AUTHOR, false, List.of(field("authorId"))),
                created, present, invalid, errors);
    }

    private void ensureCanonicalCitationIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexCitationFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_CITATION_UNIQ_EDGE, true,
                        List.of(field("citedPublicationId"), field("citingPublicationId"), field("source"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_CITATION_CITED, false, List.of(field("citedPublicationId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_CITATION_CITING, false, List.of(field("citingPublicationId"))),
                created, present, invalid, errors);
    }

    private void ensureCanonicalAuthorFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexAuthorFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_AUTHOR_SCOPUS, true, true, List.of(field("scopusAuthorIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_AUTHOR_NAME_NORMALIZED, false, List.of(field("nameNormalized"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_AUTHOR_AFFILIATIONS, false, List.of(field("affiliationIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_AUTHOR_PENDING_AFF_SOURCE_IDS, false, List.of(field("pendingAffiliationSourceIds"))),
                created, present, invalid, errors);
    }

    private void ensureCanonicalAffiliationFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexAffiliationFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_AFFILIATION_SCOPUS, true, true, List.of(field("scopusAffiliationIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_AFFILIATION_NAME_NORMALIZED, false, List.of(field("nameNormalized"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_AFFILIATION_COUNTRY, false, List.of(field("country"))),
                created, present, invalid, errors);
    }

    private void ensureCanonicalForumFactIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexForumFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_FORUM_SCOPUS, true, true, List.of(field("scopusForumIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_FORUM_WOS, true, true, List.of(field("wosForumIds"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_FORUM_NAME_NORMALIZED, false, List.of(field("nameNormalized"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_FORUM_ISSN, false, List.of(field("issn"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_FORUM_EISSN, false, List.of(field("eIssn"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_FORUM_ALIAS_ISSNS, false, List.of(field("aliasIssns"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_CANON_FORUM_AGG_TYPE, false, List.of(field("aggregationTypeNormalized"))),
                created, present, invalid, errors);
    }

    private void ensureAuthorAffiliationIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexAuthorAffiliationFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHOR_AFFILIATION_UNIQ_EDGE, true,
                        List.of(field("authorId"), field("affiliationId"), field("source"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHOR_AFFILIATION_AUTHOR, false, List.of(field("authorId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_AUTHOR_AFFILIATION_AFFILIATION, false, List.of(field("affiliationId"))),
                created, present, invalid, errors);
    }

    private void ensurePublicationAuthorAffiliationIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexPublicationAuthorAffiliationFact.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_PUBLICATION_AUTHOR_AFFILIATION_UNIQ_EDGE, true,
                        List.of(field("publicationId"), field("authorId"), field("affiliationId"), field("source"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_PUBLICATION_AUTHOR_AFFILIATION_PUBLICATION, false, List.of(field("publicationId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_PUBLICATION_AUTHOR_AFFILIATION_AUTHOR, false, List.of(field("authorId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_PUBLICATION_AUTHOR_AFFILIATION_AFFILIATION, false, List.of(field("affiliationId"))),
                created, present, invalid, errors);
    }

    private void ensureSourceLinkIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexSourceLink.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SOURCE_LINK_UNIQ, true, List.of(field("entityType"), field("source"), field("sourceRecordId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SOURCE_LINK_CANONICAL, false, List.of(field("canonicalEntityId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SOURCE_LINK_SOURCE_RECORD, false, List.of(field("entityType"), field("sourceRecordId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SOURCE_LINK_ENTITY_CANONICAL, false, List.of(field("entityType"), field("canonicalEntityId"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SOURCE_LINK_STATE_UPDATED, false, List.of(field("linkState"), field("entityType"), field("updatedAt"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SOURCE_LINK_BATCH_ENTITY, false, List.of(field("sourceBatchId"), field("entityType"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_SOURCE_LINK_CORRELATION_ENTITY, false, List.of(field("sourceCorrelationId"), field("entityType"))),
                created, present, invalid, errors);
    }

    private void ensureIdentityConflictIndexes(List<String> created, List<String> present, List<String> invalid, List<String> errors) {
        IndexOperations ops = mongoTemplate.indexOps(ScholardexIdentityConflict.class);
        ensureNamedIndex(ops, new IndexDefinition(IDX_IDENTITY_CONFLICT_OPEN, true,
                        List.of(field("entityType"), field("incomingSource"), field("incomingSourceRecordId"), field("reasonCode"), field("status"))),
                created, present, invalid, errors);
        ensureNamedIndex(ops, new IndexDefinition(IDX_IDENTITY_CONFLICT_STATUS, false, List.of(field("status"), field("entityType"))),
                created, present, invalid, errors);
    }

    private void ensureNamedIndex(
            IndexOperations ops,
            IndexDefinition definition,
            List<String> created,
            List<String> present,
            List<String> invalid,
            List<String> errors
    ) {
        try {
            List<IndexInfo> indexInfo = ops.getIndexInfo();
            IndexInfo exact = indexInfo.stream().filter(info -> definition.matchesByNameAndShape(info)).findFirst().orElse(null);
            if (exact != null) {
                present.add(definition.name());
                return;
            }

            IndexInfo sameShapeDifferentName = indexInfo.stream().filter(definition::matchesByShape).findFirst().orElse(null);
            if (sameShapeDifferentName != null) {
                invalid.add(definition.name() + " (existing=" + sameShapeDifferentName.getName() + ")");
                return;
            }

            Index index = new Index().named(definition.name());
            for (IndexField field : definition.fields()) {
                index.on(field.getKey(), Sort.Direction.ASC);
            }
            if (definition.unique()) {
                index.unique();
            }
            if (definition.sparse()) {
                index.sparse();
            }
            ops.createIndex(index);
            created.add(definition.name());
        } catch (Exception e) {
            errors.add(definition.name() + ": " + e.getMessage());
        }
    }

    private IndexField field(String key) {
        return IndexField.create(key, Sort.Direction.ASC);
    }

    private record IndexDefinition(String name, boolean unique, boolean sparse, List<IndexField> fields) {
        private IndexDefinition(String name, boolean unique, List<IndexField> fields) {
            this(name, unique, false, fields);
        }

        boolean matchesByNameAndShape(IndexInfo info) {
            if (info == null) {
                return false;
            }
            return Objects.equals(name, info.getName()) && matchesByShape(info);
        }

        boolean matchesByShape(IndexInfo info) {
            if (info == null) {
                return false;
            }
            if (unique != info.isUnique()) {
                return false;
            }
            if (sparse != info.isSparse()) {
                return false;
            }
            if (info.getIndexFields().size() != fields.size()) {
                return false;
            }
            String expected = fields.stream().map(IndexField::getKey).collect(Collectors.joining("|"));
            String actual = info.getIndexFields().stream().map(IndexField::getKey).collect(Collectors.joining("|"));
            return Objects.equals(expected, actual);
        }
    }

    public record ScopusCanonicalIndexEnsureResult(
            List<String> created,
            List<String> present,
            List<String> invalid,
            List<String> errors
    ) {
    }
}
