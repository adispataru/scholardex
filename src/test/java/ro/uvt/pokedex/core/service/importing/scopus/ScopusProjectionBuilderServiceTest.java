package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class ScopusProjectionBuilderServiceTest {

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
    void rebuildViewsProcessesPublicationWithCitationEdges() {
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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
    void rebuildViewsIncludesCanonicalUserDefinedForumsWithoutScopusForumIds() {
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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

        ScholardexForumFact canonicalUserDefinedForum = new ScholardexForumFact();
        canonicalUserDefinedForum.setId("sforum_ud");
        canonicalUserDefinedForum.setName("Wizard Forum");
        canonicalUserDefinedForum.setIssn("1234-5678");
        canonicalUserDefinedForum.setAggregationType("Journal");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of(canonicalUserDefinedForum));
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of());
        lenient().when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of());
        lenient().when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        lenient().when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        lenient().when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenReturn(new int[0]);

        ImportProcessingResult result = service.rebuildViews();

        // 1 forum from canonical
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void rebuildViewsForBatchUsesBatchRepositoriesInsteadOfGlobalReload() {
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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
    void rebuildViewsProjectsAuthorAlternativeNames() {
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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
    }

    @Test
    void rebuildViewsDedupesCanonicalCitationPairsBeforeWritingProjectionRows() {
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
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
}
