package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
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
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), eq(500), any())).thenReturn(new int[0][]);

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
        when(mongoTemplate.find(any(), eq(ScholardexCitationFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(List.of());
        when(mongoTemplate.find(any(), eq(ScholardexAuthorAffiliationFact.class))).thenReturn(List.of());
        when(jdbcTemplate.batchUpdate(anyString(), anyList(), eq(500), any())).thenReturn(new int[0][]);

        ImportProcessingResult result = service.rebuildViews();

        // 1 forum from canonical
        assertEquals(1, result.getImportedCount());
    }
}
