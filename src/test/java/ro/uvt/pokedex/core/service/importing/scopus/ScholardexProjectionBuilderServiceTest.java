package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeast;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class ScholardexProjectionBuilderServiceTest {

    @Mock
    private ScopusForumFactRepository forumFactRepository;
    @Mock
    private ScholardexAuthorFactRepository authorFactRepository;
    @Mock
    private ScholardexAffiliationFactRepository affiliationFactRepository;
    @Mock
    private ScholardexForumFactRepository canonicalForumFactRepository;
    @Mock
    private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock
    private ScholardexCitationFactRepository citationFactRepository;
    @Mock
    private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Mock
    private ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void toForumViewMapsAllCoreFields() {
        ScholardexProjectionBuilderService service = newService();
        Instant buildAt = Instant.parse("2026-04-29T10:15:30Z");

        ScopusForumFact fact = new ScopusForumFact();
        fact.setSourceId("forum-1");
        fact.setPublicationName("Forum One");
        fact.setIssn("1234-5678");
        fact.setEIssn("8765-4321");
        fact.setAggregationType("Journal");
        fact.setSourceEventId("ev-forum-1");

        ScholardexForumView view = ReflectionTestUtils.invokeMethod(service, "toForumView", fact, "build-v1", buildAt);

        assertEquals("forum-1", view.getId());
        assertEquals("Forum One", view.getPublicationName());
        assertEquals("1234-5678", view.getIssn());
        assertEquals("8765-4321", view.getEIssn());
        assertEquals("Journal", view.getAggregationType());
        assertEquals("build-v1", view.getBuildVersion());
        assertEquals(buildAt, view.getBuildAt());
        assertEquals(buildAt, view.getUpdatedAt());
        assertEquals("ev-forum-1", view.getSourceEventId());
    }

    @Test
    void toAuthorViewMapsCollectionsAndMetadata() {
        ScholardexProjectionBuilderService service = newService();
        Instant buildAt = Instant.parse("2026-04-29T10:15:30Z");

        ScholardexAuthorFact fact = new ScholardexAuthorFact();
        fact.setId("a1");
        fact.setDisplayName("Author One");
        fact.setAlternativeNames(List.of("Alt 1", "Alt 2"));
        fact.setAffiliationIds(List.of("af1", "af2"));
        fact.setSourceEventId("ev-author-1");

        ScholardexAuthorView view = ReflectionTestUtils.invokeMethod(service, "toAuthorView", fact, "build-v1", buildAt);

        assertEquals("a1", view.getId());
        assertEquals("Author One", view.getName());
        assertEquals(List.of("Alt 1", "Alt 2"), view.getAlternativeNames());
        assertEquals(List.of("af1", "af2"), view.getAffiliationIds());
        assertEquals("build-v1", view.getBuildVersion());
        assertEquals(buildAt, view.getBuildAt());
        assertEquals(buildAt, view.getUpdatedAt());
        assertEquals("ev-author-1", view.getSourceEventId());
    }

    @Test
    void toPublicationViewMapsDerivedAndLineageFields() {
        ScholardexProjectionBuilderService service = newService();
        Instant buildAt = Instant.parse("2026-04-29T10:15:30Z");

        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setId("p1");
        fact.setDoi(" https://doi.org/10.1000/AbC ");
        fact.setEid("2-s2.0-1");
        fact.setTitle("Paper");
        fact.setSubtype("ar");
        fact.setSubtypeDescription("Article");
        fact.setScopusSubtype("ar");
        fact.setScopusSubtypeDescription("Article");
        fact.setCreator("Creator");
        fact.setCoverDate("2025-01-01");
        fact.setCoverDisplayDate("January 2025");
        fact.setVolume("42");
        fact.setIssueIdentifier("7");
        fact.setDescription("Abstract");
        fact.setCorrespondingAuthors(List.of("a1"));
        fact.setOpenAccess(true);
        fact.setFreetoread("all");
        fact.setFreetoreadLabel("Open");
        fact.setFundingId("fund-1");
        fact.setArticleNumber("ART-1");
        fact.setPageRange("1-10");
        fact.setApproved(true);
        fact.setAuthorIds(List.of("a1", "a2"));
        fact.setAffiliationIds(List.of("af1"));
        fact.setForumId("forum-1");
        fact.setCitedByCount(null);
        fact.setWosId("WOS:1");
        fact.setGoogleScholarId("GS:1");
        fact.setSource("SCOPUS_JSON_UPLOAD");
        fact.setSourceEventId("ev-p1");

        Map<String, List<String>> citingByCited = Map.of("p1", List.of("p2", "p3"));

        ScholardexPublicationView view = ReflectionTestUtils.invokeMethod(
                service, "toPublicationView", fact, citingByCited, "build-v1", buildAt);

        assertEquals("p1", view.getId());
        assertEquals(" https://doi.org/10.1000/AbC ", view.getDoi());
        assertEquals("10.1000/abc", view.getDoiNormalized());
        assertEquals("2-s2.0-1", view.getEid());
        assertEquals("Paper", view.getTitle());
        assertEquals("ar", view.getSubtype());
        assertEquals("Article", view.getSubtypeDescription());
        assertEquals("Creator", view.getCreator());
        assertEquals(List.of("a1"), view.getCorrespondingAuthors());
        assertTrue(view.isOpenAccess());
        assertEquals("all", view.getFreetoread());
        assertEquals("Open", view.getFreetoreadLabel());
        assertEquals("fund-1", view.getFundingId());
        assertEquals("ART-1", view.getArticleNumber());
        assertEquals("1-10", view.getPageRange());
        assertTrue(view.isApproved());
        assertEquals(List.of("a1", "a2"), view.getAuthorIds());
        assertEquals(2, view.getAuthorCount());
        assertEquals(List.of("af1"), view.getAffiliationIds());
        assertEquals("forum-1", view.getForumId());
        assertEquals(Set.of("p2", "p3"), view.getCitingPublicationIds());
        assertEquals(2, view.getCitedByCount());
        assertEquals("ev-p1", view.getScopusLineage());
        assertEquals("SCOPUS_JSON_UPLOAD", view.getWosLineage());
        assertEquals("SCOPUS_JSON_UPLOAD", view.getScholarLineage());
        assertEquals("build-v1", view.getBuildVersion());
        assertEquals(buildAt, view.getBuildAt());
        assertEquals(buildAt, view.getUpdatedAt());
    }

    @Test
    void buildCitingMapForPublicationsReturnsSortedUniqueIdsPerPublication() {
        ScholardexProjectionBuilderService service = newService();

        ScholardexCitationFact later = new ScholardexCitationFact();
        later.setCitedPublicationId("p1");
        later.setCitingPublicationId("p3");

        ScholardexCitationFact earlier = new ScholardexCitationFact();
        earlier.setCitedPublicationId("p1");
        earlier.setCitingPublicationId("p2");

        ScholardexCitationFact duplicate = new ScholardexCitationFact();
        duplicate.setCitedPublicationId("p1");
        duplicate.setCitingPublicationId("p2");

        ScholardexCitationFact nullEndpoint = new ScholardexCitationFact();
        nullEndpoint.setCitedPublicationId("p1");

        when(citationFactRepository.findByCitedPublicationIdIn(Set.of("p1"))).thenReturn(List.of(later, earlier, duplicate, nullEndpoint));

        Map<String, List<String>> citingMap = ReflectionTestUtils.invokeMethod(
                service, "buildCitingMapForPublications", Set.of("p1"));

        assertEquals(Map.of("p1", List.of("p2", "p3")), citingMap);
    }

    @Test
    void rebuildViewsProcessesPublicationWithCitationEdges() {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setAuthorCount(1);
        publicationFact.setDoi("https://doi.org/10.1000/AbC");
        publicationFact.setAuthorIds(List.of("a1"));
        publicationFact.setAffiliationIds(List.of("af1"));
        publicationFact.setCitedByCount(1);
        publicationFact.setSourceEventId("ev1");

        ScholardexCitationFact citationFact = new ScholardexCitationFact();
        citationFact.setCitedPublicationId("p1");
        citationFact.setCitingPublicationId("p2");
        citationFact.setSource("SCOPUS_JSON_BOOTSTRAP");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of(publicationFact));
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of(citationFact));
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        ImportProcessingResult result = service.rebuildViews();

        // 1 publication = 1 imported
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void rebuildViewsIncludesCanonicalUserDefinedForumsWithoutScopusForumIds() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexForumFact laterForum = new ScholardexForumFact();
        laterForum.setId("sforum_z");
        laterForum.setName("Wizard Forum Z");
        laterForum.setIssn("1234-5678");
        laterForum.setEIssn("8765-4321");
        laterForum.setAggregationType("Journal");
        laterForum.setSourceEventId("ev-z");

        ScholardexForumFact earlierForum = new ScholardexForumFact();
        earlierForum.setId("sforum_a");
        earlierForum.setName("Wizard Forum A");
        earlierForum.setIssn("1111-2222");
        earlierForum.setEIssn("3333-4444");
        earlierForum.setAggregationType("Conference");
        earlierForum.setSourceEventId("ev-a");

        ScholardexForumFact scopusLinkedForum = new ScholardexForumFact();
        scopusLinkedForum.setId("sforum_skip");
        scopusLinkedForum.setName("Skip Me");
        scopusLinkedForum.setScopusForumIds(List.of("scopus-1"));

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of(laterForum, earlierForum, scopusLinkedForum));
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of());
        lenient().when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of());
        lenient().when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        lenient().when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        lenient().when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        ImportProcessingResult result = service.rebuildViews();

        assertEquals(2, result.getImportedCount());

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO reporting_read.scholardex_forum_view"), setterCaptor.capture());
        assertEquals(2, setterCaptor.getValue().getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(ps, 0);
        verify(ps).setString(1, "sforum_a");
        verify(ps).setString(2, "Wizard Forum A");
        verify(ps).setString(3, "1111-2222");
        verify(ps).setString(4, "3333-4444");
        verify(ps).setString(5, "Conference");
        verify(ps).setString(9, "ev-a");
        verify(ps, times(2)).setTimestamp(anyInt(), any(Timestamp.class));
    }

    @Test
    void rebuildViewsForBatchUsesBatchRepositoriesInsteadOfGlobalReload() {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);
        when(forumFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());
        when(authorFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());
        when(affiliationFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());
        when(publicationFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());

        service.rebuildViewsForBatch("upload-batch-7");
        verify(forumFactRepository).findBySourceBatchId("upload-batch-7");
        verify(authorFactRepository).findBySourceBatchId("upload-batch-7");
        verify(affiliationFactRepository).findBySourceBatchId("upload-batch-7");
        verify(publicationFactRepository).findBySourceBatchId("upload-batch-7");
        verify(forumFactRepository, never()).findAll();
        verify(authorFactRepository, never()).findAll();
        verify(affiliationFactRepository, never()).findAll();
        verify(publicationFactRepository, never()).findAll();
        verify(jdbcTemplate, never()).execute(contains("TRUNCATE TABLE"));
    }

    @Test
    void rebuildViewsForBatchKeepsCitationWhenOnlyOneEndpointIsInAffectedBatchScope() {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-affected");
        publicationFact.setTitle("Affected Paper");
        publicationFact.setAuthorCount(1);

        ScholardexCitationFact citationFact = new ScholardexCitationFact();
        citationFact.setId("scit_1");
        citationFact.setCitedPublicationId("p_existing");
        citationFact.setCitingPublicationId("p1");
        citationFact.setSource("SCOPUS_JSON_UPLOAD");

        when(forumFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());
        when(authorFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());
        when(affiliationFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());
        when(publicationFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of(publicationFact));
        when(citationFactRepository.findByCitedPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        when(citationFactRepository.findByCitingPublicationIdIn(Set.of("p1"))).thenReturn(List.of(citationFact));
        when(authorshipFactRepository.findByPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        lenient().when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        ImportProcessingResult result = service.rebuildViewsForBatch("upload-batch-7");

        assertEquals(2, result.getImportedCount());
        verify(citationFactRepository, times(2)).findByCitedPublicationIdIn(Set.of("p1"));
        verify(citationFactRepository).findByCitingPublicationIdIn(Set.of("p1"));
    }

    @Test
    void rebuildViewsForBatchKeepsCitationWhenOnlyCitedEndpointIsInAffectedBatchScope() {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-affected");
        publicationFact.setTitle("Affected Paper");
        publicationFact.setAuthorCount(1);

        ScholardexCitationFact citationFact = new ScholardexCitationFact();
        citationFact.setId("scit_2");
        citationFact.setCitedPublicationId("p1");
        citationFact.setCitingPublicationId("p_external");
        citationFact.setSource("SCOPUS_JSON_UPLOAD");

        when(forumFactRepository.findBySourceBatchId("upload-batch-9")).thenReturn(List.of());
        when(authorFactRepository.findBySourceBatchId("upload-batch-9")).thenReturn(List.of());
        when(affiliationFactRepository.findBySourceBatchId("upload-batch-9")).thenReturn(List.of());
        when(publicationFactRepository.findBySourceBatchId("upload-batch-9")).thenReturn(List.of(publicationFact));
        when(citationFactRepository.findByCitedPublicationIdIn(Set.of("p1"))).thenReturn(List.of(citationFact));
        when(citationFactRepository.findByCitingPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        when(authorshipFactRepository.findByPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        lenient().when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        ImportProcessingResult result = service.rebuildViewsForBatch("upload-batch-9");

        assertEquals(2, result.getImportedCount());
        verify(citationFactRepository, times(2)).findByCitedPublicationIdIn(Set.of("p1"));
        verify(citationFactRepository).findByCitingPublicationIdIn(Set.of("p1"));
        verify(jdbcTemplate, never()).execute(contains("TRUNCATE TABLE"));
    }

    @Test
    void rebuildViewsForBatchSkipsEdgeRefreshWhenAffectedScopesAreEmpty() {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);
        when(forumFactRepository.findBySourceBatchId("upload-empty")).thenReturn(List.of());
        when(authorFactRepository.findBySourceBatchId("upload-empty")).thenReturn(List.of());
        when(affiliationFactRepository.findBySourceBatchId("upload-empty")).thenReturn(List.of());
        when(publicationFactRepository.findBySourceBatchId("upload-empty")).thenReturn(List.of());

        ImportProcessingResult result = service.rebuildViewsForBatch("upload-empty");

        assertEquals(0, result.getImportedCount());
        verify(citationFactRepository, never()).findByCitedPublicationIdIn(any());
        verify(citationFactRepository, never()).findByCitingPublicationIdIn(any());
        verify(authorshipFactRepository, never()).findByPublicationIdIn(any());
        verify(authorAffiliationFactRepository, never()).findByAuthorIdIn(any());
        verify(jdbcTemplate, never()).execute(contains("DELETE FROM reporting_read.scholardex_citation_fact"));
        verify(jdbcTemplate, never()).execute(contains("TRUNCATE TABLE"));
    }

    @Test
    void rebuildViewsForBatchRefreshesAuthorshipAndAuthorAffiliationWithinAffectedScopes() {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-affected");
        publicationFact.setTitle("Affected Paper");
        publicationFact.setAuthorCount(1);

        ScholardexAuthorFact authorFact = new ScholardexAuthorFact();
        authorFact.setId("a1");
        authorFact.setDisplayName("Author One");

        ScholardexAffiliationFact affiliationFact = new ScholardexAffiliationFact();
        affiliationFact.setId("af1");
        affiliationFact.setName("Affiliation One");

        ScholardexAuthorshipFact authorshipFact = new ScholardexAuthorshipFact();
        authorshipFact.setId("sedge_authorship_1");
        authorshipFact.setPublicationId("p1");
        authorshipFact.setAuthorId("a1");

        ScholardexAuthorAffiliationFact authorAffiliationFact = new ScholardexAuthorAffiliationFact();
        authorAffiliationFact.setId("sedge_author_affiliation_1");
        authorAffiliationFact.setAuthorId("a1");
        authorAffiliationFact.setAffiliationId("af1");

        when(forumFactRepository.findBySourceBatchId("upload-batch-8")).thenReturn(List.of());
        when(authorFactRepository.findBySourceBatchId("upload-batch-8")).thenReturn(List.of(authorFact));
        when(affiliationFactRepository.findBySourceBatchId("upload-batch-8")).thenReturn(List.of(affiliationFact));
        when(publicationFactRepository.findBySourceBatchId("upload-batch-8")).thenReturn(List.of(publicationFact));
        when(citationFactRepository.findByCitedPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        when(citationFactRepository.findByCitingPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        when(authorshipFactRepository.findByPublicationIdIn(Set.of("p1"))).thenReturn(List.of(authorshipFact));
        when(authorAffiliationFactRepository.findByAuthorIdIn(Set.of("a1"))).thenReturn(List.of(authorAffiliationFact));
        lenient().when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        ImportProcessingResult result = service.rebuildViewsForBatch("upload-batch-8");

        assertEquals(5, result.getImportedCount());
        verify(authorshipFactRepository).findByPublicationIdIn(Set.of("p1"));
        verify(authorAffiliationFactRepository).findByAuthorIdIn(Set.of("a1"));
        verify(jdbcTemplate, never()).execute(contains("TRUNCATE TABLE"));
    }

    @Test
    void rebuildViewsProjectsAuthorAlternativeNames() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexAuthorFact authorFact = new ScholardexAuthorFact();
        authorFact.setId("a1");
        authorFact.setDisplayName("Spataru A.");
        authorFact.setAlternativeNames(List.of("Spataru, Adrian", "Adrian Spataru"));

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(authorFact));
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        service.rebuildViews();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(sqlCaptor.capture(), setterCaptor.capture());
        int authorWriteIndex = -1;
        List<String> sqls = sqlCaptor.getAllValues();
        for (int i = 0; i < sqls.size(); i++) {
            if (sqls.get(i).contains("reporting_read.scholardex_author_view")) {
                authorWriteIndex = i;
                break;
            }
        }
        assertTrue(authorWriteIndex >= 0);
        assertTrue(sqls.get(authorWriteIndex).contains("alternative_names"));
        assertEquals(1, setterCaptor.getAllValues().get(authorWriteIndex).getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        Array alternativeNamesArray = mock(Array.class);
        Array affiliationIdsArray = mock(Array.class);
        when(ps.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("text"), argThat(values -> java.util.Arrays.equals((Object[]) values, new Object[]{"Spataru, Adrian", "Adrian Spataru"})))).thenReturn(alternativeNamesArray);
        when(connection.createArrayOf(eq("text"), argThat(values -> java.util.Arrays.equals((Object[]) values, new Object[]{})))).thenReturn(affiliationIdsArray);

        setterCaptor.getAllValues().get(authorWriteIndex).setValues(ps, 0);
        verify(ps).setString(1, "a1");
        verify(ps).setString(2, "Spataru A.");
        verify(ps).setArray(3, alternativeNamesArray);
        verify(ps).setArray(4, affiliationIdsArray);
    }

    @Test
    void rebuildViewsDedupesCanonicalCitationPairsBeforeWritingProjectionRows() {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact citedPublication = new ScholardexPublicationFact();
        citedPublication.setId("p1");
        citedPublication.setEid("2-s2.0-1");
        citedPublication.setTitle("Cited");
        citedPublication.setAuthorCount(1);

        ScholardexPublicationFact citingPublication = new ScholardexPublicationFact();
        citingPublication.setId("p2");
        citingPublication.setEid("2-s2.0-2");
        citingPublication.setTitle("Citing");
        citingPublication.setAuthorCount(1);

        ScholardexCitationFact bootstrapCitation = new ScholardexCitationFact();
        bootstrapCitation.setId("scit_bootstrap");
        bootstrapCitation.setCitedPublicationId("p1");
        bootstrapCitation.setCitingPublicationId("p2");
        bootstrapCitation.setSource("SCOPUS_JSON_BOOTSTRAP");

        ScholardexCitationFact schedulerCitation = new ScholardexCitationFact();
        schedulerCitation.setId("scit_scheduler");
        schedulerCitation.setCitedPublicationId("p1");
        schedulerCitation.setCitingPublicationId("p2");
        schedulerCitation.setSource("SCOPUS_PYTHON_CITATIONS_EDGE");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of(citedPublication, citingPublication));
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of(bootstrapCitation, schedulerCitation));
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        ImportProcessingResult result = service.rebuildViews();

        assertEquals(2, result.getImportedCount());
        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO reporting_read.scholardex_citation_fact"), setterCaptor.capture());
        assertEquals(1, setterCaptor.getValue().getBatchSize());
    }

    @Test
    void rebuildViewsProjectsNormalizedDoiAndUniqueCitingIdsIntoPublicationRows() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setDoi(" https://doi.org/10.1000/AbC ");
        publicationFact.setAuthorIds(List.of("a1", "a2"));
        publicationFact.setAffiliationIds(List.of("af1"));
        publicationFact.setForumId("forum-1");
        publicationFact.setSource("SCOPUS_JSON_UPLOAD");
        publicationFact.setSourceEventId("event-1");
        publicationFact.setWosId("WOS:1");
        publicationFact.setGoogleScholarId("GS:1");

        ScholardexCitationFact duplicateA = new ScholardexCitationFact();
        duplicateA.setId("scit_a");
        duplicateA.setCitedPublicationId("p1");
        duplicateA.setCitingPublicationId("p2");
        duplicateA.setSource("SCOPUS_JSON_BOOTSTRAP");

        ScholardexCitationFact duplicateB = new ScholardexCitationFact();
        duplicateB.setId("scit_b");
        duplicateB.setCitedPublicationId("p1");
        duplicateB.setCitingPublicationId("p2");
        duplicateB.setSource("SCOPUS_PYTHON_CITATIONS_EDGE");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of(publicationFact));
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of(duplicateA, duplicateB));
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        service.rebuildViews();

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO reporting_read.scholardex_publication_view"), setterCaptor.capture());

        PreparedStatement ps = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        Array authorArray = mock(Array.class);
        Array affiliationArray = mock(Array.class);
        Array citingArray = mock(Array.class);
        when(ps.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenAnswer(invocation -> {
            Object[] values = invocation.getArgument(1);
            if (java.util.Arrays.equals(values, new Object[]{"a1", "a2"})) {
                return authorArray;
            }
            if (java.util.Arrays.equals(values, new Object[]{"af1"})) {
                return affiliationArray;
            }
            if (java.util.Arrays.equals(values, new Object[]{"p2"})) {
                return citingArray;
            }
            return mock(Array.class);
        });

        setterCaptor.getValue().setValues(ps, 0);

        verify(ps).setString(2, " https://doi.org/10.1000/AbC ");
        verify(ps).setString(3, "10.1000/abc");
        verify(ps).setString(27, "forum-1");
        verify(ps).setString(30, "WOS:1");
        verify(ps).setString(31, "GS:1");
        verify(ps).setString(35, "event-1");
        verify(ps).setString(36, "SCOPUS_JSON_UPLOAD");
        verify(ps).setString(37, "SCOPUS_JSON_UPLOAD");
        verify(ps).setArray(25, authorArray);
        verify(ps).setArray(26, affiliationArray);
        verify(ps).setArray(28, citingArray);
        verify(ps).setInt(16, 2);
        verify(ps).setInt(29, 1);
    }

    @Test
    void rebuildViewsWritesDedupedCitationRowMetadataFromRetainedEdge() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact citedPublication = new ScholardexPublicationFact();
        citedPublication.setId("p1");
        citedPublication.setEid("2-s2.0-1");
        citedPublication.setTitle("Cited");
        citedPublication.setAuthorCount(1);

        ScholardexPublicationFact citingPublication = new ScholardexPublicationFact();
        citingPublication.setId("p2");
        citingPublication.setEid("2-s2.0-2");
        citingPublication.setTitle("Citing");
        citingPublication.setAuthorCount(1);

        ScholardexCitationFact retainedCitation = new ScholardexCitationFact();
        retainedCitation.setId("scit_bootstrap");
        retainedCitation.setCitedPublicationId("p1");
        retainedCitation.setCitingPublicationId("p2");
        retainedCitation.setSource("SCOPUS_JSON_BOOTSTRAP");
        retainedCitation.setSourceRecordId("2-s2.0-1->2-s2.0-2");
        retainedCitation.setSourceBatchId("batch-1");
        retainedCitation.setSourceCorrelationId("corr-1");

        ScholardexCitationFact duplicateCitation = new ScholardexCitationFact();
        duplicateCitation.setId("scit_scheduler");
        duplicateCitation.setCitedPublicationId("p1");
        duplicateCitation.setCitingPublicationId("p2");
        duplicateCitation.setSource("SCOPUS_PYTHON_CITATIONS_EDGE");
        duplicateCitation.setSourceRecordId("ignored");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of(citedPublication, citingPublication));
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of(retainedCitation, duplicateCitation));
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        service.rebuildViews();

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO reporting_read.scholardex_citation_fact"), setterCaptor.capture());

        PreparedStatement ps = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(ps, 0);

        verify(ps).setString(1, "scit_bootstrap");
        verify(ps).setString(2, "p1");
        verify(ps).setString(3, "p2");
        verify(ps).setString(4, "SCOPUS_JSON_BOOTSTRAP");
        verify(ps).setString(5, "2-s2.0-1->2-s2.0-2");
        verify(ps).setString(7, "batch-1");
        verify(ps).setString(8, "corr-1");
    }

    @Test
    void rebuildViewsProjectsAffiliationRowsWithCityCountryAndMetadata() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexAffiliationFact affiliationFact = new ScholardexAffiliationFact();
        affiliationFact.setId("af1");
        affiliationFact.setName("Affiliation One");
        affiliationFact.setCity("Timisoara");
        affiliationFact.setCountry("RO");
        affiliationFact.setSourceEventId("ev-af1");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of(affiliationFact));
        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        service.rebuildViews();

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO reporting_read.scholardex_affiliation_view"), setterCaptor.capture());

        PreparedStatement ps = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(ps, 0);
        verify(ps).setString(1, "af1");
        verify(ps).setString(2, "Affiliation One");
        verify(ps).setString(3, "Timisoara");
        verify(ps).setString(4, "RO");
        verify(ps).setString(8, "ev-af1");
    }

    @Test
    void rebuildViewsProjectsAuthorAndAffiliationViewsWithEmptyCollections() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexAuthorFact authorFact = new ScholardexAuthorFact();
        authorFact.setId("a1");
        authorFact.setDisplayName("Author One");
        authorFact.setSourceEventId("ev-a1");

        ScholardexAffiliationFact affiliationFact = new ScholardexAffiliationFact();
        affiliationFact.setId("af1");
        affiliationFact.setName("Affiliation One");
        affiliationFact.setCity("Timisoara");
        affiliationFact.setCountry("RO");
        affiliationFact.setSourceEventId("ev-af1");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(authorFact));
        when(affiliationFactRepository.findAll()).thenReturn(List.of(affiliationFact));
        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        service.rebuildViews();

        ArgumentCaptor<BatchPreparedStatementSetter> authorSetterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO reporting_read.scholardex_author_view"), authorSetterCaptor.capture());
        PreparedStatement authorPs = mock(PreparedStatement.class);
        Connection authorConnection = mock(Connection.class);
        Array emptyArray = mock(Array.class);
        when(authorPs.getConnection()).thenReturn(authorConnection);
        when(authorConnection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(emptyArray);
        authorSetterCaptor.getValue().setValues(authorPs, 0);
        verify(authorPs).setString(1, "a1");
        verify(authorPs).setString(2, "Author One");
        verify(authorPs).setArray(3, emptyArray);
        verify(authorPs).setArray(4, emptyArray);
        verify(authorPs).setString(8, "ev-a1");
        verify(authorPs, times(2)).setTimestamp(anyInt(), any(Timestamp.class));

        ArgumentCaptor<BatchPreparedStatementSetter> affiliationSetterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO reporting_read.scholardex_affiliation_view"), affiliationSetterCaptor.capture());
        PreparedStatement affiliationPs = mock(PreparedStatement.class);
        affiliationSetterCaptor.getValue().setValues(affiliationPs, 0);
        verify(affiliationPs).setString(1, "af1");
        verify(affiliationPs).setString(2, "Affiliation One");
        verify(affiliationPs).setString(3, "Timisoara");
        verify(affiliationPs).setString(4, "RO");
        verify(affiliationPs).setString(8, "ev-af1");
        verify(affiliationPs, times(2)).setTimestamp(anyInt(), any(Timestamp.class));
    }

    @Test
    void rebuildViewsProjectsFullyPopulatedPublicationRows() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setDoi("doi:10.1000/ABC");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setSubtype("ar");
        publicationFact.setSubtypeDescription("Article");
        publicationFact.setScopusSubtype("ar");
        publicationFact.setScopusSubtypeDescription("Article");
        publicationFact.setCreator("Creator");
        publicationFact.setCoverDate("2025-01-01");
        publicationFact.setCoverDisplayDate("January 2025");
        publicationFact.setVolume("42");
        publicationFact.setIssueIdentifier("7");
        publicationFact.setDescription("Abstract");
        publicationFact.setCorrespondingAuthors(List.of("ca1", "ca2"));
        publicationFact.setOpenAccess(true);
        publicationFact.setFreetoread("publisherhybridgold");
        publicationFact.setFreetoreadLabel("Hybrid Gold");
        publicationFact.setFundingId("fund-1");
        publicationFact.setArticleNumber("ART-7");
        publicationFact.setPageRange("1-10");
        publicationFact.setApproved(true);
        publicationFact.setAuthorIds(List.of("a1", "a2"));
        publicationFact.setAuthorCount(2);
        publicationFact.setAffiliationIds(List.of("af1", "af2"));
        publicationFact.setForumId("forum-1");
        publicationFact.setWosId("WOS:1");
        publicationFact.setGoogleScholarId("GS:1");
        publicationFact.setSource("SCOPUS_JSON_UPLOAD");
        publicationFact.setSourceEventId("ev-p1");

        ScholardexPublicationFact citingPublication = new ScholardexPublicationFact();
        citingPublication.setId("p2");
        citingPublication.setEid("2-s2.0-2");
        citingPublication.setTitle("Citing");
        citingPublication.setAuthorCount(1);

        ScholardexCitationFact citationA = new ScholardexCitationFact();
        citationA.setCitedPublicationId("p1");
        citationA.setCitingPublicationId("p3");
        citationA.setSource("SCOPUS_JSON_BOOTSTRAP");

        ScholardexCitationFact citationB = new ScholardexCitationFact();
        citationB.setCitedPublicationId("p1");
        citationB.setCitingPublicationId("p2");
        citationB.setSource("SCOPUS_JSON_BOOTSTRAP");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of(publicationFact, citingPublication));
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of(citationA, citationB));
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        service.rebuildViews();

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO reporting_read.scholardex_publication_view"), setterCaptor.capture());

        PreparedStatement ps = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        Array correspondingAuthorsArray = mock(Array.class);
        Array authorIdsArray = mock(Array.class);
        Array affiliationIdsArray = mock(Array.class);
        Array citingIdsArray = mock(Array.class);
        when(ps.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenAnswer(invocation -> {
            Object[] values = invocation.getArgument(1);
            if (java.util.Arrays.equals(values, new Object[]{"ca1", "ca2"})) {
                return correspondingAuthorsArray;
            }
            if (java.util.Arrays.equals(values, new Object[]{"a1", "a2"})) {
                return authorIdsArray;
            }
            if (java.util.Arrays.equals(values, new Object[]{"af1", "af2"})) {
                return affiliationIdsArray;
            }
            if (java.util.Arrays.equals(values, new Object[]{"p2", "p3"})) {
                return citingIdsArray;
            }
            if (java.util.Arrays.equals(values, new Object[]{})) {
                return mock(Array.class);
            }
            return mock(Array.class);
        });

        setterCaptor.getValue().setValues(ps, 0);

        verify(ps).setString(3, "10.1000/abc");
        verify(ps).setString(6, "ar");
        verify(ps).setString(7, "Article");
        verify(ps).setString(8, "ar");
        verify(ps).setString(9, "Article");
        verify(ps).setString(10, "Creator");
        verify(ps).setString(11, "2025-01-01");
        verify(ps).setString(12, "January 2025");
        verify(ps).setString(13, "42");
        verify(ps).setString(14, "7");
        verify(ps).setString(15, "Abstract");
        verify(ps).setArray(17, correspondingAuthorsArray);
        verify(ps).setBoolean(18, true);
        verify(ps).setString(19, "publisherhybridgold");
        verify(ps).setString(20, "Hybrid Gold");
        verify(ps).setString(21, "fund-1");
        verify(ps).setString(22, "ART-7");
        verify(ps).setString(23, "1-10");
        verify(ps).setBoolean(24, true);
        verify(ps).setArray(25, authorIdsArray);
        verify(ps).setArray(26, affiliationIdsArray);
        verify(ps).setArray(28, citingIdsArray);
        verify(ps).setInt(29, 2);
        verify(ps).setString(eq(32), argThat(value -> value != null && !value.isBlank()));
        verify(ps, times(2)).setTimestamp(anyInt(), any(Timestamp.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rebuildViewsPerformsFullReplacementAcrossAllReportingTables() {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScopusForumFact forumFact = new ScopusForumFact();
        forumFact.setSourceId("forum-1");
        forumFact.setPublicationName("Forum");
        forumFact.setSourceEventId("ev-forum");

        ScholardexAuthorFact authorFact = new ScholardexAuthorFact();
        authorFact.setId("a1");
        authorFact.setDisplayName("Author");
        authorFact.setAffiliationIds(List.of("af1"));
        authorFact.setSourceEventId("ev-author");

        ScholardexAffiliationFact affiliationFact = new ScholardexAffiliationFact();
        affiliationFact.setId("af1");
        affiliationFact.setName("Affiliation");
        affiliationFact.setSourceEventId("ev-aff");

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setForumId("forum-1");
        publicationFact.setAuthorIds(List.of("a1"));
        publicationFact.setAffiliationIds(List.of("af1"));
        publicationFact.setAuthorCount(1);
        publicationFact.setSourceEventId("ev-pub");

        ScholardexPublicationFact citingPublicationFact = new ScholardexPublicationFact();
        citingPublicationFact.setId("p2");
        citingPublicationFact.setEid("2-s2.0-2");
        citingPublicationFact.setTitle("Citing Paper");
        citingPublicationFact.setForumId("forum-1");
        citingPublicationFact.setAuthorIds(List.of("a1"));
        citingPublicationFact.setAffiliationIds(List.of("af1"));
        citingPublicationFact.setAuthorCount(1);
        citingPublicationFact.setSourceEventId("ev-pub-2");

        ScholardexCitationFact citationFact = new ScholardexCitationFact();
        citationFact.setId("c1");
        citationFact.setCitedPublicationId("p1");
        citationFact.setCitingPublicationId("p2");

        ScholardexAuthorshipFact authorshipFact = new ScholardexAuthorshipFact();
        authorshipFact.setId("auth-1");
        authorshipFact.setPublicationId("p1");
        authorshipFact.setAuthorId("a1");

        ScholardexAuthorAffiliationFact authorAffiliationFact = new ScholardexAuthorAffiliationFact();
        authorAffiliationFact.setId("aa-1");
        authorAffiliationFact.setAuthorId("a1");
        authorAffiliationFact.setAffiliationId("af1");

        when(forumFactRepository.findAll()).thenReturn(List.of(forumFact));
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(authorFact));
        when(affiliationFactRepository.findAll()).thenReturn(List.of(affiliationFact));
        when(publicationFactRepository.findAll()).thenReturn(List.of(publicationFact, citingPublicationFact));
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of(citationFact));
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of(authorshipFact));
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of(authorAffiliationFact));
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        ImportProcessingResult result = service.rebuildViews();

        assertEquals(5, result.getImportedCount());
        verify(jdbcTemplate).execute(contains("TRUNCATE TABLE"));
        verify(jdbcTemplate).batchUpdate(contains("reporting_read.scholardex_forum_view"), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate).batchUpdate(contains("reporting_read.scholardex_author_view"), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate).batchUpdate(contains("reporting_read.scholardex_affiliation_view"), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate).batchUpdate(contains("reporting_read.scholardex_publication_view"), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate).batchUpdate(contains("reporting_read.scholardex_citation_fact"), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate).batchUpdate(contains("reporting_read.scholardex_authorship_fact"), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate).batchUpdate(contains("reporting_read.scholardex_author_affiliation_fact"), any(BatchPreparedStatementSetter.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rebuildViewsForBatchRefreshesAllAffectedReportingTables() throws Exception {
        ScholardexProjectionBuilderService service = newService();

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScopusForumFact forumFact = new ScopusForumFact();
        forumFact.setSourceId("forum-1");
        forumFact.setPublicationName("Forum");

        ScholardexAuthorFact authorFact = new ScholardexAuthorFact();
        authorFact.setId("a1");
        authorFact.setDisplayName("Author");
        authorFact.setAffiliationIds(List.of("af1"));

        ScholardexAffiliationFact affiliationFact = new ScholardexAffiliationFact();
        affiliationFact.setId("af1");
        affiliationFact.setName("Affiliation");

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setForumId("forum-1");
        publicationFact.setAuthorIds(List.of("a1"));
        publicationFact.setAffiliationIds(List.of("af1"));

        ScholardexCitationFact citationFact = new ScholardexCitationFact();
        citationFact.setId("c1");
        citationFact.setCitedPublicationId("p1");
        citationFact.setCitingPublicationId("p2");

        ScholardexAuthorshipFact authorshipFact = new ScholardexAuthorshipFact();
        authorshipFact.setId("auth-1");
        authorshipFact.setPublicationId("p1");
        authorshipFact.setAuthorId("a1");

        ScholardexAuthorAffiliationFact authorAffiliationFact = new ScholardexAuthorAffiliationFact();
        authorAffiliationFact.setId("aa-1");
        authorAffiliationFact.setAuthorId("a1");
        authorAffiliationFact.setAffiliationId("af1");

        when(forumFactRepository.findBySourceBatchId("upload-batch-all")).thenReturn(List.of(forumFact));
        when(authorFactRepository.findBySourceBatchId("upload-batch-all")).thenReturn(List.of(authorFact));
        when(affiliationFactRepository.findBySourceBatchId("upload-batch-all")).thenReturn(List.of(affiliationFact));
        when(publicationFactRepository.findBySourceBatchId("upload-batch-all")).thenReturn(List.of(publicationFact));
        when(citationFactRepository.findByCitedPublicationIdIn(Set.of("p1"))).thenReturn(List.of(citationFact));
        when(citationFactRepository.findByCitingPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        when(authorshipFactRepository.findByPublicationIdIn(Set.of("p1"))).thenReturn(List.of(authorshipFact));
        when(authorAffiliationFactRepository.findByAuthorIdIn(Set.of("a1"))).thenReturn(List.of(authorAffiliationFact));
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            Connection connection = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            Array array = mock(Array.class);
            when(connection.prepareStatement(anyString())).thenReturn(ps);
            when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(array);
            return callback.doInConnection(connection);
        });

        ImportProcessingResult result = service.rebuildViewsForBatch("upload-batch-all");

        assertEquals(7, result.getImportedCount());
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(contains("ON CONFLICT (id) DO UPDATE"), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate, atLeast(4)).batchUpdate(contains("reporting_read"), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate, times(3)).execute(any(ConnectionCallback.class));
        verify(jdbcTemplate, never()).execute(contains("TRUNCATE TABLE"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(sqlCaptor.capture(), any(BatchPreparedStatementSetter.class));
        List<String> sqls = sqlCaptor.getAllValues();
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("scholardex_forum_view") && sql.contains("ON CONFLICT (id) DO UPDATE")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("scholardex_author_view") && sql.contains("ON CONFLICT (id) DO UPDATE")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("scholardex_affiliation_view") && sql.contains("ON CONFLICT (id) DO UPDATE")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("scholardex_publication_view") && sql.contains("ON CONFLICT (id) DO UPDATE")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("INSERT INTO reporting_read.scholardex_citation_fact")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("INSERT INTO reporting_read.scholardex_authorship_fact")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("INSERT INTO reporting_read.scholardex_author_affiliation_fact")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rebuildViewsForBatchDeletesAffectedRowsUsingTextArraysBeforeReinserting() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScopusForumFact forumFact = new ScopusForumFact();
        forumFact.setSourceId("forum-1");

        ScholardexAuthorFact authorFact = new ScholardexAuthorFact();
        authorFact.setId("a1");
        authorFact.setDisplayName("Author");
        authorFact.setAffiliationIds(List.of("af1"));

        ScholardexAffiliationFact affiliationFact = new ScholardexAffiliationFact();
        affiliationFact.setId("af1");
        affiliationFact.setName("Affiliation");

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setForumId("forum-1");
        publicationFact.setAuthorIds(List.of("a1"));
        publicationFact.setAffiliationIds(List.of("af1"));

        ScholardexCitationFact citationFact = new ScholardexCitationFact();
        citationFact.setId("c1");
        citationFact.setCitedPublicationId("p1");
        citationFact.setCitingPublicationId("p2");

        ScholardexAuthorshipFact authorshipFact = new ScholardexAuthorshipFact();
        authorshipFact.setId("auth-1");
        authorshipFact.setPublicationId("p1");
        authorshipFact.setAuthorId("a1");

        ScholardexAuthorAffiliationFact authorAffiliationFact = new ScholardexAuthorAffiliationFact();
        authorAffiliationFact.setId("aa-1");
        authorAffiliationFact.setAuthorId("a1");
        authorAffiliationFact.setAffiliationId("af1");

        when(forumFactRepository.findBySourceBatchId("upload-batch-delete")).thenReturn(List.of(forumFact));
        when(authorFactRepository.findBySourceBatchId("upload-batch-delete")).thenReturn(List.of(authorFact));
        when(affiliationFactRepository.findBySourceBatchId("upload-batch-delete")).thenReturn(List.of(affiliationFact));
        when(publicationFactRepository.findBySourceBatchId("upload-batch-delete")).thenReturn(List.of(publicationFact));
        when(citationFactRepository.findByCitedPublicationIdIn(Set.of("p1"))).thenReturn(List.of(citationFact));
        when(citationFactRepository.findByCitingPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        when(authorshipFactRepository.findByPublicationIdIn(Set.of("p1"))).thenReturn(List.of(authorshipFact));
        when(authorAffiliationFactRepository.findByAuthorIdIn(Set.of("a1"))).thenReturn(List.of(authorAffiliationFact));
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        PreparedStatement deleteCitationPs = mock(PreparedStatement.class);
        PreparedStatement deleteAuthorshipPs = mock(PreparedStatement.class);
        PreparedStatement deleteAuthorAffiliationPs = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        Array p1Array = mock(Array.class);
        Array a1Array = mock(Array.class);
        when(connection.prepareStatement(contains("scholardex_citation_fact"))).thenReturn(deleteCitationPs);
        when(connection.prepareStatement(contains("scholardex_authorship_fact"))).thenReturn(deleteAuthorshipPs);
        when(connection.prepareStatement(contains("scholardex_author_affiliation_fact"))).thenReturn(deleteAuthorAffiliationPs);
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenAnswer(invocation -> {
            Object[] values = invocation.getArgument(1);
            if (java.util.Arrays.equals(values, new Object[]{"a1"})) {
                return a1Array;
            }
            return p1Array;
        });
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });

        service.rebuildViewsForBatch("upload-batch-delete");

        verify(deleteCitationPs).setArray(1, p1Array);
        verify(deleteCitationPs).setArray(2, p1Array);
        verify(deleteCitationPs).executeUpdate();
        verify(deleteAuthorshipPs).setArray(1, p1Array);
        verify(deleteAuthorshipPs).executeUpdate();
        verify(deleteAuthorAffiliationPs).setArray(1, a1Array);
        verify(deleteAuthorAffiliationPs).executeUpdate();
    }

    @Test
    void rebuildViewsForBatchProjectsSortedDerivedCitingIdsWhenCountMissing() throws Exception {
        ScholardexProjectionBuilderService service = new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setAuthorIds(List.of("a1"));
        publicationFact.setAffiliationIds(List.of("af1"));
        publicationFact.setForumId("forum-1");

        ScholardexCitationFact duplicateLater = new ScholardexCitationFact();
        duplicateLater.setId("c-later");
        duplicateLater.setCitedPublicationId("p1");
        duplicateLater.setCitingPublicationId("p3");

        ScholardexCitationFact duplicateEarlier = new ScholardexCitationFact();
        duplicateEarlier.setId("c-earlier");
        duplicateEarlier.setCitedPublicationId("p1");
        duplicateEarlier.setCitingPublicationId("p2");

        ScholardexCitationFact duplicatePair = new ScholardexCitationFact();
        duplicatePair.setId("c-dup");
        duplicatePair.setCitedPublicationId("p1");
        duplicatePair.setCitingPublicationId("p2");

        when(forumFactRepository.findBySourceBatchId("upload-batch-cites")).thenReturn(List.of());
        when(authorFactRepository.findBySourceBatchId("upload-batch-cites")).thenReturn(List.of());
        when(affiliationFactRepository.findBySourceBatchId("upload-batch-cites")).thenReturn(List.of());
        when(publicationFactRepository.findBySourceBatchId("upload-batch-cites")).thenReturn(List.of(publicationFact));
        when(citationFactRepository.findByCitedPublicationIdIn(Set.of("p1"))).thenReturn(List.of(duplicateLater, duplicateEarlier, duplicatePair));
        when(citationFactRepository.findByCitingPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        when(authorshipFactRepository.findByPublicationIdIn(Set.of("p1"))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            Connection connection = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            Array deleteArray = mock(Array.class);
            when(connection.prepareStatement(anyString())).thenReturn(ps);
            when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(deleteArray);
            return callback.doInConnection(connection);
        });

        service.rebuildViewsForBatch("upload-batch-cites");

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(contains("scholardex_publication_view"), setterCaptor.capture());

        PreparedStatement ps = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        Array emptyArray = mock(Array.class);
        Array authorIdsArray = mock(Array.class);
        Array affiliationIdsArray = mock(Array.class);
        Array citingIdsArray = mock(Array.class);
        when(ps.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenAnswer(invocation -> {
            Object[] values = invocation.getArgument(1);
            if (java.util.Arrays.equals(values, new Object[]{"a1"})) {
                return authorIdsArray;
            }
            if (java.util.Arrays.equals(values, new Object[]{"af1"})) {
                return affiliationIdsArray;
            }
            if (java.util.Arrays.equals(values, new Object[]{"p2", "p3"})) {
                return citingIdsArray;
            }
            return emptyArray;
        });

        setterCaptor.getValue().setValues(ps, 0);
        verify(ps).setArray(17, emptyArray);
        verify(ps).setArray(25, authorIdsArray);
        verify(ps).setArray(26, affiliationIdsArray);
        verify(ps).setArray(28, citingIdsArray);
        verify(ps).setInt(29, 2);
    }

    private ScholardexProjectionBuilderService newService() {
        return new ScholardexProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                mongoTemplate,
                jdbcTemplate,
                transactionManager
        );
    }
}
