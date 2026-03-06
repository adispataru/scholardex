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
        when(mongoTemplate.indexOps(ScopusForumSearchView.class)).thenReturn(forumViewOps);
        when(mongoTemplate.indexOps(ScopusAuthorSearchView.class)).thenReturn(authorViewOps);
        when(mongoTemplate.indexOps(ScopusAffiliationSearchView.class)).thenReturn(affiliationViewOps);
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
        when(forumViewOps.getIndexInfo()).thenReturn(List.of());
        when(authorViewOps.getIndexInfo()).thenReturn(List.of());
        when(affiliationViewOps.getIndexInfo()).thenReturn(List.of());
        when(mergedPublicationViewOps.getIndexInfo()).thenReturn(List.of());

        ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult result = service.ensureIndexes();

        assertEquals(40, result.created().size());
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
        when(mergedPublicationViewOps.getIndexInfo()).thenReturn(List.of(
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_EID, false, "eid"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_TITLE, false, "title"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_COVERDATE, false, "coverDate"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_AUTHORS, false, "authorIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_AFFILIATIONS, false, "affiliationIds"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_FORUM, false, "forumId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_WOS, false, "wosId"),
                info(ScopusCanonicalIndexMaintenanceService.IDX_MERGED_PUBLICATION_GOOGLE_SCHOLAR, false, "googleScholarId")
        ));

        ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult result = service.ensureIndexes();

        assertEquals(40, result.present().size());
        assertTrue(result.created().isEmpty());
        assertTrue(result.invalid().isEmpty());
        assertTrue(result.errors().isEmpty());
        verify(importOps, never()).ensureIndex(any());
        verify(publicationOps, never()).ensureIndex(any());
        verify(citationOps, never()).ensureIndex(any());
        verify(forumFactOps, never()).ensureIndex(any());
        verify(authorFactOps, never()).ensureIndex(any());
        verify(affiliationFactOps, never()).ensureIndex(any());
        verify(fundingFactOps, never()).ensureIndex(any());
        verify(forumViewOps, never()).ensureIndex(any());
        verify(authorViewOps, never()).ensureIndex(any());
        verify(affiliationViewOps, never()).ensureIndex(any());
        verify(mergedPublicationViewOps, never()).ensureIndex(any());
    }

    private IndexInfo info(String name, boolean unique, String... keys) {
        return new IndexInfo(
                java.util.Arrays.stream(keys).map(k -> IndexField.create(k, Sort.Direction.ASC)).toList(),
                name,
                unique,
                false,
                null
        );
    }
}
