package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H54.5c — manual-edit orchestration relocated out of {@code ScholardexProjectionReadService}.
 * Constructs the service with real per-entity writers + resolver over mocked repos / source-link
 * service, so the persistence + source-link collaboration is exercised end to end.
 */
@ExtendWith(MockitoExtension.class)
class ScholardexManualEditServiceTest {

    @Mock
    private ScholardexForumFactRepository forumFactRepository;
    @Mock
    private ScholardexAuthorFactRepository authorFactRepository;
    @Mock
    private ScholardexAffiliationFactRepository affiliationFactRepository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexEdgeWriterService edgeWriterService;

    private ScholardexManualEditService service() {
        return new ScholardexManualEditService(
                forumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                new ScholardexForumWriter(forumFactRepository, sourceLinkService),
                new ScholardexAuthorWriter(authorFactRepository, sourceLinkService),
                new ScholardexAffiliationWriter(affiliationFactRepository, sourceLinkService),
                edgeWriterService,
                new ScholardexCanonicalIdResolver(sourceLinkService));
    }

    @Test
    void saveForumSaveAuthorSaveAffiliationPersistAndLink() {
        ScholardexManualEditService service = service();

        when(forumFactRepository.findById(any())).thenReturn(Optional.empty());
        when(authorFactRepository.findById("legacy_a")).thenReturn(Optional.empty());
        when(affiliationFactRepository.findById("legacy_af")).thenReturn(Optional.empty());
        ScholardexSourceLink forumMapped = new ScholardexSourceLink();
        forumMapped.setCanonicalEntityId("sforum_m");
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(ScholardexEntityType.FORUM, "legacy_f"))
                .thenReturn(List.of(new ScholardexSourceLink(), forumMapped));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(ScholardexEntityType.AUTHOR, "legacy_a"))
                .thenReturn(List.of());
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(ScholardexEntityType.AFFILIATION, "legacy_af"))
                .thenReturn(List.of());

        ScholardexForumView forumIn = new ScholardexForumView();
        forumIn.setId("legacy_f");
        forumIn.setPublicationName("Forum N");
        forumIn.setIssn("1234-5678");
        forumIn.setEIssn("8765-4321");
        forumIn.setAggregationType("Journal");
        ScholardexForumView forumOut = service.saveForum(forumIn);
        assertEquals("sforum_m", forumOut.getId());
        assertEquals("Forum N", forumOut.getPublicationName());
        assertEquals("1234-5678", forumOut.getIssn());
        assertEquals("8765-4321", forumOut.getEIssn());
        assertEquals("Journal", forumOut.getAggregationType());
        ArgumentCaptor<ScholardexForumFact> forumFactCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(forumFactRepository).save(forumFactCaptor.capture());
        ScholardexForumFact savedForumFact = forumFactCaptor.getValue();
        assertEquals("sforum_m", savedForumFact.getId());
        assertEquals("Forum N", savedForumFact.getName());
        assertEquals("forum n", savedForumFact.getNameNormalized());
        assertEquals("1234-5678", savedForumFact.getIssn());
        assertEquals("8765-4321", savedForumFact.getEIssn());
        assertEquals("Journal", savedForumFact.getAggregationType());
        assertEquals("journal", savedForumFact.getAggregationTypeNormalized());
        assertEquals("MANUAL_FORUM_EDIT", savedForumFact.getSource());
        assertEquals("legacy_f", savedForumFact.getSourceRecordId());
        assertNotNull(savedForumFact.getCreatedAt());
        assertNotNull(savedForumFact.getUpdatedAt());
        verify(sourceLinkService).link(
                eq(ScholardexEntityType.FORUM), eq("MANUAL_FORUM_EDIT"), eq("legacy_f"), eq("sforum_m"),
                eq("manual-forum-save"), eq(null), eq(null), eq(null), eq(false)
        );

        ScholardexAuthorView authorIn = new ScholardexAuthorView();
        authorIn.setId("legacy_a");
        authorIn.setName("John Doe");
        ScholardexAffiliationView affRef = new ScholardexAffiliationView();
        affRef.setAfid("legacy_af");
        authorIn.setAffiliations(List.of(affRef));

        ScholardexSourceLink affMap = new ScholardexSourceLink();
        affMap.setCanonicalEntityId("saff_mapped");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of(affMap));

        ScholardexAuthorView authorOut = service.saveAuthor(authorIn);
        assertEquals("legacy_a", authorOut.getId());
        ArgumentCaptor<ScholardexAuthorFact> authorFactCaptor = ArgumentCaptor.forClass(ScholardexAuthorFact.class);
        verify(authorFactRepository).save(authorFactCaptor.capture());
        ScholardexAuthorFact savedAuthorFact = authorFactCaptor.getValue();
        assertEquals("legacy_a", savedAuthorFact.getId());
        assertEquals("John Doe", savedAuthorFact.getDisplayName());
        assertEquals("john doe", savedAuthorFact.getNameNormalized());
        assertEquals(List.of("legacy_af", "saff_mapped"), savedAuthorFact.getAffiliationIds());
        assertEquals("MANUAL_AUTHOR_EDIT", savedAuthorFact.getSource());
        assertEquals("legacy_a", savedAuthorFact.getSourceRecordId());
        assertNotNull(savedAuthorFact.getCreatedAt());
        assertNotNull(savedAuthorFact.getUpdatedAt());
        verify(sourceLinkService).link(
                eq(ScholardexEntityType.AUTHOR), eq("MANUAL_AUTHOR_EDIT"), eq("legacy_a"), eq("legacy_a"),
                eq("manual-author-save"), eq(null), eq(null), eq(null), eq(false)
        );
        verify(edgeWriterService, times(2)).upsertAuthorAffiliationEdge(any());
        assertEquals("John Doe", authorOut.getName());
        assertTrue(authorOut.getAlternativeNames().isEmpty());
        assertEquals(2, authorOut.getAffiliations().size());
        List<String> returnedAffiliationIds = authorOut.getAffiliations().stream()
                .map(ScholardexAffiliationView::getAfid)
                .toList();
        assertEquals(List.of("legacy_af", "saff_mapped"), returnedAffiliationIds);

        ScholardexAffiliationView affIn = new ScholardexAffiliationView();
        affIn.setAfid("legacy_af");
        affIn.setName("UVT");
        affIn.setCity("Timisoara");
        affIn.setCountry("RO");
        ScholardexAffiliationView affOut = service.saveAffiliation(affIn);
        assertEquals("legacy_af", affOut.getAfid());
        ArgumentCaptor<ScholardexAffiliationFact> affFactCaptor = ArgumentCaptor.forClass(ScholardexAffiliationFact.class);
        verify(affiliationFactRepository).save(affFactCaptor.capture());
        ScholardexAffiliationFact savedAffFact = affFactCaptor.getValue();
        assertEquals("legacy_af", savedAffFact.getId());
        assertEquals("UVT", savedAffFact.getName());
        assertEquals("uvt", savedAffFact.getNameNormalized());
        assertEquals("Timisoara", savedAffFact.getCity());
        assertEquals("RO", savedAffFact.getCountry());
        assertEquals("MANUAL_AFFILIATION_EDIT", savedAffFact.getSource());
        assertEquals("legacy_af", savedAffFact.getSourceRecordId());
        assertNotNull(savedAffFact.getCreatedAt());
        assertNotNull(savedAffFact.getUpdatedAt());
        verify(sourceLinkService).link(
                eq(ScholardexEntityType.AFFILIATION), eq("MANUAL_AFFILIATION_EDIT"), eq("legacy_af"), eq("legacy_af"),
                eq("manual-affiliation-save"), eq(null), eq(null), eq(null), eq(false)
        );
        assertEquals("UVT", affOut.getName());
        assertEquals("Timisoara", affOut.getCity());
        assertEquals("RO", affOut.getCountry());
    }

    @Test
    void saveMethodsSupportNullSourceIdsWithoutLinkWrites() {
        ScholardexManualEditService service = service();
        when(forumFactRepository.findById(any())).thenReturn(Optional.empty());
        when(authorFactRepository.findById(any())).thenReturn(Optional.empty());
        when(affiliationFactRepository.findById(any())).thenReturn(Optional.empty());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Forum X");
        forum.setAggregationType("Journal");
        service.saveForum(forum);

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setName("Author X");
        author.setAffiliations(List.of());
        service.saveAuthor(author);

        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setName("Aff X");
        service.saveAffiliation(affiliation);

        verify(sourceLinkService, never()).link(
                eq(ScholardexEntityType.FORUM), any(), any(), any(), any(), any(), any(), any(), eq(false)
        );
    }
}
