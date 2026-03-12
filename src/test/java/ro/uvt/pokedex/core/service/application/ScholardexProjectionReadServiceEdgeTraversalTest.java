package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexProjectionReadServiceEdgeTraversalTest {

    @Mock private ScholardexPublicationViewRepository publicationViewRepository;
    @Mock private ScholardexCitationFactRepository citationFactRepository;
    @Mock private ScholardexForumViewRepository forumViewRepository;
    @Mock private ScholardexAuthorViewRepository authorViewRepository;
    @Mock private ScholardexAffiliationViewRepository affiliationViewRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexAuthorFactRepository canonicalAuthorFactRepository;
    @Mock private ScholardexAffiliationFactRepository canonicalAffiliationFactRepository;
    @Mock private ScholardexForumFactRepository canonicalForumFactRepository;
    @Mock private ScholardexAuthorAffiliationFactRepository canonicalAuthorAffiliationFactRepository;
    @Mock private ScholardexAuthorshipFactRepository canonicalAuthorshipFactRepository;
    @Mock private ScholardexEdgeWriterService edgeWriterService;

    @Test
    void findAllPublicationsByAuthorsInUsesAuthorshipEdges() {
        ScholardexProjectionReadService service = buildService();

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(any(), anyCollection()))
                .thenReturn(List.of(authorLink));

        ScholardexAuthorshipFact authorship = new ScholardexAuthorshipFact();
        authorship.setAuthorId("sauth_1");
        authorship.setPublicationId("spub_1");
        when(canonicalAuthorshipFactRepository.findByAuthorIdIn(anyCollection()))
                .thenReturn(List.of(authorship));

        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId("spub_1");
        view.setTitle("Paper");
        when(publicationViewRepository.findAllByIdIn(anyCollection())).thenReturn(List.of(view));

        List<Publication> publications = service.findAllPublicationsByAuthorsIn(List.of("legacy-author"));

        assertEquals(1, publications.size());
        assertEquals("spub_1", publications.getFirst().getId());
        verify(publicationViewRepository, never()).findAllByAuthorIdsIn(any());
    }

    @Test
    void findAllPublicationsByAffiliationsContainingTraversesAffiliationToAuthorToPublicationEdges() {
        ScholardexProjectionReadService service = buildService();

        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setCanonicalEntityId("saff_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(any(), anyCollection()))
                .thenReturn(List.of(affiliationLink));

        ScholardexAuthorAffiliationFact authorAffiliation = new ScholardexAuthorAffiliationFact();
        authorAffiliation.setAffiliationId("saff_1");
        authorAffiliation.setAuthorId("sauth_1");
        when(canonicalAuthorAffiliationFactRepository.findByAffiliationId("legacy-aff"))
                .thenReturn(List.of());
        when(canonicalAuthorAffiliationFactRepository.findByAffiliationId("saff_1"))
                .thenReturn(List.of(authorAffiliation));

        ScholardexAuthorshipFact authorship = new ScholardexAuthorshipFact();
        authorship.setAuthorId("sauth_1");
        authorship.setPublicationId("spub_2");
        when(canonicalAuthorshipFactRepository.findByAuthorIdIn(anyCollection()))
                .thenReturn(List.of(authorship));

        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId("spub_2");
        view.setTitle("From affiliation");
        when(publicationViewRepository.findAllByIdIn(anyCollection())).thenReturn(List.of(view));

        List<Publication> publications = service.findAllPublicationsByAffiliationsContaining("legacy-aff");

        assertEquals(1, publications.size());
        assertEquals("spub_2", publications.getFirst().getId());
        verify(publicationViewRepository, never()).findAllByAffiliationIdsContaining(any());
    }

    @Test
    void findAllCitationsByCitedIdInFastPathsCanonicalIdsWithoutLookupFallback() {
        ScholardexProjectionReadService service = buildService();

        ScholardexCitationFact citation = new ScholardexCitationFact();
        citation.setId("c1");
        citation.setCitedPublicationId("spub_1");
        citation.setCitingPublicationId("spub_citing");
        when(citationFactRepository.findByCitedPublicationIdIn(anyCollection())).thenReturn(List.of(citation));

        ScholardexPublicationView citedView = new ScholardexPublicationView();
        citedView.setId("spub_1");
        ScholardexPublicationView citingView = new ScholardexPublicationView();
        citingView.setId("spub_citing");
        when(publicationViewRepository.findAllByIdIn(anyCollection())).thenReturn(List.of(citedView, citingView));

        var citations = service.findAllCitationsByCitedIdIn(List.of("spub_1", "spub_2"));

        assertEquals(1, citations.size());
        verify(publicationViewRepository, never()).findAllByEidIn(anyCollection());
        verify(publicationViewRepository, never()).findById(any());
        verify(publicationViewRepository, never()).findByEid(any());
        verify(publicationViewRepository, never()).findByWosId(any());
        verify(publicationViewRepository, never()).findByGoogleScholarId(any());
    }

    @Test
    void findAllCitationsByCitedIdInUsesBatchResolutionThenFallbackForUnresolvedKeys() {
        ScholardexProjectionReadService service = buildService();

        ScholardexPublicationView eidView = new ScholardexPublicationView();
        eidView.setId("spub_eid");
        eidView.setEid("2-s2.0-ABC");
        when(publicationViewRepository.findAllByEidIn(anyCollection())).thenReturn(List.of(eidView));

        ScholardexPublicationView wosView = new ScholardexPublicationView();
        wosView.setId("spub_wos");
        when(publicationViewRepository.findByWosId("WOS:0001")).thenReturn(java.util.Optional.of(wosView));
        when(citationFactRepository.findByCitedPublicationIdIn(anyCollection())).thenReturn(List.of());

        service.findAllCitationsByCitedIdIn(List.of("spub_1", "2-s2.0-ABC", "WOS:0001"));

        org.mockito.ArgumentCaptor<java.util.Collection<String>> citedIdsCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(citationFactRepository).findByCitedPublicationIdIn(citedIdsCaptor.capture());
        java.util.Collection<String> resolved = citedIdsCaptor.getValue();
        assertTrue(resolved.contains("spub_1"));
        assertTrue(resolved.contains("spub_eid"));
        assertTrue(resolved.contains("spub_wos"));

        verify(publicationViewRepository, times(1)).findAllByEidIn(anyCollection());
        verify(publicationViewRepository, times(1)).findByWosId(eq("WOS:0001"));
    }

    private ScholardexProjectionReadService buildService() {
        return new ScholardexProjectionReadService(
                publicationViewRepository,
                citationFactRepository,
                forumViewRepository,
                authorViewRepository,
                affiliationViewRepository,
                sourceLinkService,
                canonicalAuthorFactRepository,
                canonicalAffiliationFactRepository,
                canonicalForumFactRepository,
                canonicalAuthorAffiliationFactRepository,
                canonicalAuthorshipFactRepository,
                edgeWriterService
        );
    }
}
