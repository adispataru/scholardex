package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusCanonicalIndexMaintenanceServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private IndexOperations importOps;
    @Mock
    private IndexOperations publicationOps;
    @Mock
    private IndexOperations citationOps;
    @Mock
    private IndexOperations forumFactOps;
    @Mock
    private IndexOperations authorFactOps;
    @Mock
    private IndexOperations affiliationFactOps;
    @Mock
    private IndexOperations fundingFactOps;
    @Mock
    private IndexOperations forumViewOps;
    @Mock
    private IndexOperations authorViewOps;
    @Mock
    private IndexOperations affiliationViewOps;
    @Mock
    private IndexOperations mergedPublicationViewOps;
    @Mock
    private IndexOperations canonicalPublicationFactOps;
    @Mock
    private IndexOperations canonicalAuthorFactOps;
    @Mock
    private IndexOperations canonicalAffiliationFactOps;
    @Mock
    private IndexOperations canonicalForumFactOps;
    @Mock
    private IndexOperations authorAffiliationFactOps;
    @Mock
    private IndexOperations scholardexForumViewOps;
    @Mock
    private IndexOperations scholardexAuthorViewOps;
    @Mock
    private IndexOperations scholardexAffiliationViewOps;
    @Mock
    private IndexOperations sourceLinkOps;
    @Mock
    private IndexOperations identityConflictOps;
    @Mock
    private IndexOperations authorshipFactOps;
    @Mock
    private IndexOperations canonicalCitationFactOps;
    @Mock
    private IndexOperations publicationAuthorAffiliationFactOps;
    @Mock
    private IndexOperations affiliationTouchOps;
    @Mock
    private IndexOperations authorTouchOps;
    @Mock
    private IndexOperations forumTouchOps;
    @Mock
    private IndexOperations publicationTouchOps;
    @Mock
    private IndexOperations citationTouchOps;

    private ScopusCanonicalIndexMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new ScopusCanonicalIndexMaintenanceService(mongoTemplate);
        when(mongoTemplate.indexOps(ScopusImportEvent.class)).thenReturn(importOps);
        when(mongoTemplate.indexOps(ScopusPublicationFact.class)).thenReturn(publicationOps);
        when(mongoTemplate.indexOps(ScopusCitationFact.class)).thenReturn(citationOps);
        when(mongoTemplate.indexOps(ScopusForumFact.class)).thenReturn(forumFactOps);
        when(mongoTemplate.indexOps(ScopusAuthorFact.class)).thenReturn(authorFactOps);
        when(mongoTemplate.indexOps(ScopusAffiliationFact.class)).thenReturn(affiliationFactOps);
        when(mongoTemplate.indexOps(ScopusFundingFact.class)).thenReturn(fundingFactOps);
        when(mongoTemplate.indexOps(ScopusAffiliationTouch.class)).thenReturn(affiliationTouchOps);
        when(mongoTemplate.indexOps(ScopusAuthorTouch.class)).thenReturn(authorTouchOps);
        when(mongoTemplate.indexOps(ScopusForumTouch.class)).thenReturn(forumTouchOps);
        when(mongoTemplate.indexOps(ScopusPublicationTouch.class)).thenReturn(publicationTouchOps);
        when(mongoTemplate.indexOps(ScopusCitationTouch.class)).thenReturn(citationTouchOps);
        when(mongoTemplate.indexOps(ScopusForumSearchView.class)).thenReturn(forumViewOps);
        when(mongoTemplate.indexOps(ScopusAuthorSearchView.class)).thenReturn(authorViewOps);
        when(mongoTemplate.indexOps(ScopusAffiliationSearchView.class)).thenReturn(affiliationViewOps);
        when(mongoTemplate.indexOps(ScholardexPublicationFact.class)).thenReturn(canonicalPublicationFactOps);
        when(mongoTemplate.indexOps(ScholardexAuthorFact.class)).thenReturn(canonicalAuthorFactOps);
        when(mongoTemplate.indexOps(ScholardexAffiliationFact.class)).thenReturn(canonicalAffiliationFactOps);
        when(mongoTemplate.indexOps(ScholardexForumFact.class)).thenReturn(canonicalForumFactOps);
        when(mongoTemplate.indexOps(ScholardexForumView.class)).thenReturn(scholardexForumViewOps);
        when(mongoTemplate.indexOps(ScholardexAuthorView.class)).thenReturn(scholardexAuthorViewOps);
        when(mongoTemplate.indexOps(ScholardexAffiliationView.class)).thenReturn(scholardexAffiliationViewOps);
        when(mongoTemplate.indexOps(ScholardexAuthorshipFact.class)).thenReturn(authorshipFactOps);
        when(mongoTemplate.indexOps(ScholardexCitationFact.class)).thenReturn(canonicalCitationFactOps);
        when(mongoTemplate.indexOps(ScholardexAuthorAffiliationFact.class)).thenReturn(authorAffiliationFactOps);
        when(mongoTemplate.indexOps(ScholardexPublicationAuthorAffiliationFact.class)).thenReturn(publicationAuthorAffiliationFactOps);
        when(mongoTemplate.indexOps(ScholardexSourceLink.class)).thenReturn(sourceLinkOps);
        when(mongoTemplate.indexOps(ScholardexIdentityConflict.class)).thenReturn(identityConflictOps);
        when(mongoTemplate.indexOps(ScholardexPublicationView.class)).thenReturn(mergedPublicationViewOps);
    }

    @Test
    void ensureIndexesCreatesAllMissingIndexes() {
        when(importOps.getIndexInfo()).thenReturn(List.of());
        when(publicationOps.getIndexInfo()).thenReturn(List.of());
        when(citationOps.getIndexInfo()).thenReturn(List.of());
        when(forumFactOps.getIndexInfo()).thenReturn(List.of());
        when(authorFactOps.getIndexInfo()).thenReturn(List.of());
        when(affiliationFactOps.getIndexInfo()).thenReturn(List.of());
        when(fundingFactOps.getIndexInfo()).thenReturn(List.of());
        when(affiliationTouchOps.getIndexInfo()).thenReturn(List.of());
        when(authorTouchOps.getIndexInfo()).thenReturn(List.of());
        when(forumTouchOps.getIndexInfo()).thenReturn(List.of());
        when(publicationTouchOps.getIndexInfo()).thenReturn(List.of());
        when(citationTouchOps.getIndexInfo()).thenReturn(List.of());
        when(forumViewOps.getIndexInfo()).thenReturn(List.of());
        when(authorViewOps.getIndexInfo()).thenReturn(List.of());
        when(affiliationViewOps.getIndexInfo()).thenReturn(List.of());
        when(canonicalPublicationFactOps.getIndexInfo()).thenReturn(List.of());
        when(canonicalAuthorFactOps.getIndexInfo()).thenReturn(List.of());
        when(canonicalAffiliationFactOps.getIndexInfo()).thenReturn(List.of());
        when(canonicalForumFactOps.getIndexInfo()).thenReturn(List.of());
        when(scholardexForumViewOps.getIndexInfo()).thenReturn(List.of());
        when(scholardexAuthorViewOps.getIndexInfo()).thenReturn(List.of());
        when(scholardexAffiliationViewOps.getIndexInfo()).thenReturn(List.of());
        when(authorshipFactOps.getIndexInfo()).thenReturn(List.of());
        when(canonicalCitationFactOps.getIndexInfo()).thenReturn(List.of());
        when(authorAffiliationFactOps.getIndexInfo()).thenReturn(List.of());
        when(publicationAuthorAffiliationFactOps.getIndexInfo()).thenReturn(List.of());
        when(sourceLinkOps.getIndexInfo()).thenReturn(List.of());
        when(identityConflictOps.getIndexInfo()).thenReturn(List.of());
        when(mergedPublicationViewOps.getIndexInfo()).thenReturn(List.of());

        ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult result = service.ensureIndexes();

        assertEquals(106, result.created().size());
        assertTrue(result.present().isEmpty());
        assertTrue(result.invalid().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void ensureIndexesMarksExistingIndexesAsPresent() {
        when(importOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_IMPORT_UNIQ, true, "entityType", "source", "sourceRecordId", "payloadHash"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_IMPORT_BATCH_CORRELATION, false, "batchId", "correlationId", "entityType")
        ));
        when(publicationOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_PUBLICATION_UNIQ_EID, true, "eid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_PUBLICATION_AUTHOR, false, "authors"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_PUBLICATION_AFFILIATION, false, "affiliations"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_PUBLICATION_FORUM_COVERDATE, false, "forumId", "coverDate")
        ));
        when(citationOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_CITATION_UNIQ_EDGE, true, "citedEid", "citingEid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CITATION_CITED, false, "citedEid")
        ));
        when(forumFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_UNIQ_SOURCE_ID, true, "sourceId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_NAME, false, "publicationName"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_ISSN, false, "issn"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_EISSN, false, "eIssn"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_AGG, false, "aggregationType")
        ));
        when(authorFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHOR_UNIQ, true, "authorId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHOR_NAME, false, "name"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHOR_AFFILIATIONS, false, "affiliationIds")
        ));
        when(affiliationFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_AFFILIATION_UNIQ, true, "afid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AFFILIATION_NAME, false, "name"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AFFILIATION_CITY, false, "city"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AFFILIATION_COUNTRY, false, "country")
        ));
        when(fundingFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_FUNDING_UNIQ, true, "fundingKey"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_FUNDING_SPONSOR, false, "sponsor")
        ));
        when(affiliationTouchOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_AFFILIATION_UNIQ, true, "source", "afid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_AFFILIATION_TOUCHED, false, "touchedAt")
        ));
        when(authorTouchOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_AUTHOR_UNIQ, true, "source", "authorId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_AUTHOR_TOUCHED, false, "touchedAt")
        ));
        when(forumTouchOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_FORUM_UNIQ, true, "source", "sourceId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_FORUM_TOUCHED, false, "touchedAt")
        ));
        when(publicationTouchOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_PUBLICATION_UNIQ, true, "source", "eid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_PUBLICATION_TOUCHED, false, "touchedAt")
        ));
        when(citationTouchOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_CITATION_UNIQ, true, "source", "citedEid", "citingEid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_TOUCH_CITATION_TOUCHED, false, "touchedAt")
        ));
        when(forumViewOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_VIEW_NAME, false, "publicationName"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_VIEW_ISSN, false, "issn"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_VIEW_EISSN, false, "eIssn"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_FORUM_VIEW_AGG, false, "aggregationType")
        ));
        when(authorViewOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHOR_VIEW_NAME, false, "name"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHOR_VIEW_AFFILIATIONS, false, "affiliationIds")
        ));
        when(affiliationViewOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_AFFILIATION_VIEW_NAME, false, "name"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AFFILIATION_VIEW_CITY, false, "city"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AFFILIATION_VIEW_COUNTRY, false, "country"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AFFILIATION_VIEW_AFID, false, "_id")
        ));
        when(canonicalPublicationFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_EID, true, true, "eid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_WOS, true, true, "wosId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_GS, true, true, "googleScholarId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_USER, true, true, "userSourceId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_DOI_NORMALIZED, true, true, "doiNormalized"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_TITLE_NORMALIZED, false, "titleNormalized"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_FORUM, false, "forumId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_AUTHORS, false, "authorIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_AFFILIATIONS, false, "affiliationIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_PUBLICATION_PENDING_AUTHOR_SOURCE_IDS, false, "pendingAuthorSourceIds")
        ));
        when(canonicalAuthorFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_AUTHOR_SCOPUS, true, true, "scopusAuthorIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_AUTHOR_NAME_NORMALIZED, false, "nameNormalized"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_AUTHOR_AFFILIATIONS, false, "affiliationIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_AUTHOR_PENDING_AFF_SOURCE_IDS, false, "pendingAffiliationSourceIds")
        ));
        when(canonicalAffiliationFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_AFFILIATION_SCOPUS, true, true, "scopusAffiliationIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_AFFILIATION_NAME_NORMALIZED, false, "nameNormalized"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_AFFILIATION_COUNTRY, false, "country")
        ));
        when(canonicalForumFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_FORUM_SCOPUS, true, true, "scopusForumIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_FORUM_WOS, true, true, "wosForumIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_FORUM_NAME_NORMALIZED, false, "nameNormalized"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_FORUM_ISSN, false, "issn"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_FORUM_EISSN, false, "eIssn"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_FORUM_ALIAS_ISSNS, false, "aliasIssns"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_FORUM_AGG_TYPE, false, "aggregationTypeNormalized")
        ));
        when(authorshipFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHORSHIP_UNIQ_EDGE, true, "publicationId", "authorId", "source"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHORSHIP_PUBLICATION, false, "publicationId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHORSHIP_AUTHOR, false, "authorId")
        ));
        when(canonicalCitationFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_CITATION_UNIQ_EDGE, true, "citedPublicationId", "citingPublicationId", "source"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_CITATION_CITED, false, "citedPublicationId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_CANON_CITATION_CITING, false, "citingPublicationId")
        ));
        when(scholardexForumViewOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_FORUM_VIEW_NAME, false, "publicationName"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_FORUM_VIEW_ISSN, false, "issn"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_FORUM_VIEW_EISSN, false, "eIssn"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_FORUM_VIEW_AGG, false, "aggregationType")
        ));
        when(scholardexAuthorViewOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_AUTHOR_VIEW_NAME, false, "name"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_AUTHOR_VIEW_AFFILIATIONS, false, "affiliationIds")
        ));
        when(scholardexAffiliationViewOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_AFFILIATION_VIEW_NAME, false, "name"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_AFFILIATION_VIEW_CITY, false, "city"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SCHOLARDEX_AFFILIATION_VIEW_COUNTRY, false, "country")
        ));
        when(authorAffiliationFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHOR_AFFILIATION_UNIQ_EDGE, true, "authorId", "affiliationId", "source"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHOR_AFFILIATION_AUTHOR, false, "authorId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_AUTHOR_AFFILIATION_AFFILIATION, false, "affiliationId")
        ));
        when(publicationAuthorAffiliationFactOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_PUBLICATION_AUTHOR_AFFILIATION_UNIQ_EDGE, true, "publicationId", "authorId", "affiliationId", "source"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_PUBLICATION_AUTHOR_AFFILIATION_PUBLICATION, false, "publicationId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_PUBLICATION_AUTHOR_AFFILIATION_AUTHOR, false, "authorId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_PUBLICATION_AUTHOR_AFFILIATION_AFFILIATION, false, "affiliationId")
        ));
        when(sourceLinkOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_SOURCE_LINK_UNIQ, true, "entityType", "source", "sourceRecordId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SOURCE_LINK_CANONICAL, false, "canonicalEntityId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SOURCE_LINK_SOURCE_RECORD, false, "entityType", "sourceRecordId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SOURCE_LINK_ENTITY_CANONICAL, false, "entityType", "canonicalEntityId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SOURCE_LINK_STATE_UPDATED, false, "linkState", "entityType", "updatedAt"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SOURCE_LINK_BATCH_ENTITY, false, "sourceBatchId", "entityType"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_SOURCE_LINK_CORRELATION_ENTITY, false, "sourceCorrelationId", "entityType")
        ));
        when(identityConflictOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_IDENTITY_CONFLICT_OPEN, true,
                        "entityType", "incomingSource", "incomingSourceRecordId", "reasonCode", "status"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_IDENTITY_CONFLICT_STATUS, false, "status", "entityType")
        ));
        when(mergedPublicationViewOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_EID, false, "eid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_DOI_NORMALIZED, false, "doiNormalized"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_TITLE, false, "title"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_COVERDATE, false, "coverDate"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_AUTHORS, false, "authorIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_AFFILIATIONS, false, "affiliationIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_FORUM, false, "forumId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_WOS, false, "wosId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_GOOGLE_SCHOLAR, false, "googleScholarId")
        ));

        ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult result = service.ensureIndexes();

        assertEquals(106, result.present().size());
        assertTrue(result.created().isEmpty());
        assertTrue(result.invalid().isEmpty());
        assertTrue(result.errors().isEmpty());
        verify(importOps, never()).createIndex(any());
        verify(publicationOps, never()).createIndex(any());
        verify(citationOps, never()).createIndex(any());
        verify(forumFactOps, never()).createIndex(any());
        verify(authorFactOps, never()).createIndex(any());
        verify(affiliationFactOps, never()).createIndex(any());
        verify(fundingFactOps, never()).createIndex(any());
        verify(affiliationTouchOps, never()).createIndex(any());
        verify(authorTouchOps, never()).createIndex(any());
        verify(forumTouchOps, never()).createIndex(any());
        verify(publicationTouchOps, never()).createIndex(any());
        verify(citationTouchOps, never()).createIndex(any());
        verify(forumViewOps, never()).createIndex(any());
        verify(authorViewOps, never()).createIndex(any());
        verify(affiliationViewOps, never()).createIndex(any());
        verify(canonicalPublicationFactOps, never()).createIndex(any());
        verify(canonicalAuthorFactOps, never()).createIndex(any());
        verify(canonicalAffiliationFactOps, never()).createIndex(any());
        verify(canonicalForumFactOps, never()).createIndex(any());
        verify(scholardexForumViewOps, never()).createIndex(any());
        verify(scholardexAuthorViewOps, never()).createIndex(any());
        verify(scholardexAffiliationViewOps, never()).createIndex(any());
        verify(authorshipFactOps, never()).createIndex(any());
        verify(canonicalCitationFactOps, never()).createIndex(any());
        verify(authorAffiliationFactOps, never()).createIndex(any());
        verify(publicationAuthorAffiliationFactOps, never()).createIndex(any());
        verify(sourceLinkOps, never()).createIndex(any());
        verify(identityConflictOps, never()).createIndex(any());
        verify(mergedPublicationViewOps, never()).createIndex(any());
    }

    private IndexInfo info(String name, boolean unique, String... keys) {
        return info(name, unique, false, keys);
    }

    private IndexInfo info(String name, boolean unique, boolean sparse, String... keys) {
        return new IndexInfo(
                java.util.Arrays.stream(keys).map(k -> IndexField.create(k, Sort.Direction.ASC)).toList(),
                name,
                unique,
                sparse,
                null
        );
    }
}
