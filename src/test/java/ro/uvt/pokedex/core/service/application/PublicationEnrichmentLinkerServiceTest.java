package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationLinkConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationEnrichmentLinkerServiceTest {

    @Mock
    private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock
    private PublicationLinkConflictRepository conflictRepository;

    private PublicationEnrichmentLinkerService service;

    @BeforeEach
    void setUp() {
        service = new PublicationEnrichmentLinkerService(
                publicationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                conflictRepository
        );
    }

    @Test
    void linkWosEnrichmentResolvesByIdBeforeEidAndDoi() {
        Publication publication = new Publication();
        publication.setId("p-id");
        publication.setEid("2-s2.0-eid");
        publication.setDoi("10.1000/abc");
        publication.setWosId("WOS:1");

        ScholardexPublicationFact target = publicationFact("p-id", "2-s2.0-other", "old-title");
        when(publicationFactRepository.findById("p-id")).thenReturn(Optional.of(target));
        when(publicationFactRepository.findByWosId("WOS:1")).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult result =
                service.linkWosEnrichment(publication, "WOSEXTRACTOR", "h17.10", "run-1");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.LINKED, result.state());
        verify(publicationFactRepository, never()).findByEid(anyString());
        verify(publicationFactRepository, never()).findAllByDoiNormalized(anyString());

        ArgumentCaptor<ScholardexPublicationFact> saved = ArgumentCaptor.forClass(ScholardexPublicationFact.class);
        verify(publicationFactRepository).save(saved.capture());
        assertEquals("WOS:1", saved.getValue().getWosId());
        assertEquals("old-title", saved.getValue().getTitle());
        verify(sourceLinkService).link(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), eq(false));
    }

    @Test
    void linkWosEnrichmentFallsBackToEidAndThenNormalizedDoi() {
        Publication byEidPublication = new Publication();
        byEidPublication.setEid("2-s2.0-eid");
        byEidPublication.setWosId("WOS:EID");
        ScholardexPublicationFact byEidTarget = publicationFact("p-eid", "2-s2.0-eid", null);
        when(publicationFactRepository.findByEid("2-s2.0-eid")).thenReturn(Optional.of(byEidTarget));
        when(publicationFactRepository.findByWosId("WOS:EID")).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult eidResult =
                service.linkWosEnrichment(byEidPublication, "WOSEXTRACTOR", "h17.10", "run-1");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.LINKED, eidResult.state());

        Publication byDoiPublication = new Publication();
        byDoiPublication.setDoi("https://doi.org/10.1000/AbC");
        byDoiPublication.setWosId("WOS:DOI");
        ScholardexPublicationFact byDoiTarget = publicationFact("p-doi", "2-s2.0-other", null);
        when(publicationFactRepository.findAllByDoiNormalized("10.1000/abc")).thenReturn(List.of(byDoiTarget));
        when(publicationFactRepository.findByWosId("WOS:DOI")).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult doiResult =
                service.linkWosEnrichment(byDoiPublication, "WOSEXTRACTOR", "h17.10", "run-2");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.LINKED, doiResult.state());
    }

    @Test
    void linkWosEnrichmentSkipsNonWosSentinel() {
        Publication publication = new Publication();
        publication.setWosId(Publication.NON_WOS_ID);

        PublicationEnrichmentLinkerService.LinkResult result =
                service.linkWosEnrichment(publication, "WOSEXTRACTOR", "h17.10", "run-1");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.SKIPPED, result.state());
        verify(publicationFactRepository, never()).save(any());
        verify(conflictRepository, never()).save(any());
    }

    @Test
    void linkWosEnrichmentQuarantinesConflictWhenKeyAlreadyAssigned() {
        Publication publication = new Publication();
        publication.setId("p-target");
        publication.setWosId("WOS:1");

        ScholardexPublicationFact target = publicationFact("p-target", "2-s2.0-target", null);
        ScholardexPublicationFact other = publicationFact("p-other", "2-s2.0-other", null);
        when(publicationFactRepository.findById("p-target")).thenReturn(Optional.of(target));
        when(publicationFactRepository.findByWosId("WOS:1")).thenReturn(Optional.of(other));

        PublicationEnrichmentLinkerService.LinkResult result =
                service.linkWosEnrichment(publication, "WOSEXTRACTOR", "h17.10", "run-1");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        verify(publicationFactRepository, never()).save(any());

        ArgumentCaptor<PublicationLinkConflict> conflictCaptor = ArgumentCaptor.forClass(PublicationLinkConflict.class);
        verify(conflictRepository).save(conflictCaptor.capture());
        assertEquals("wosId", conflictCaptor.getValue().getKeyType());
        assertEquals("WOS:1", conflictCaptor.getValue().getKeyValue());
        assertEquals("p-target", conflictCaptor.getValue().getTargetPublicationId());
        assertEquals("p-other", conflictCaptor.getValue().getCandidatePublicationIds().getFirst());
    }

    @Test
    void linkWosEnrichmentQuarantinesConflictWhenDoiMatchIsAmbiguous() {
        Publication publication = new Publication();
        publication.setDoi("doi:10.1000/ABC");
        publication.setWosId("WOS:2");
        publication.setId("p-ignore");

        ScholardexPublicationFact p1 = publicationFact("p1", "2-s2.0-1", null);
        ScholardexPublicationFact p2 = publicationFact("p2", "2-s2.0-2", null);
        when(publicationFactRepository.findById("p-ignore")).thenReturn(Optional.empty());
        when(publicationFactRepository.findAllByDoiNormalized(anyString())).thenReturn(List.of(p1, p2));

        PublicationEnrichmentLinkerService.LinkResult result =
                service.linkWosEnrichment(publication, "WOSEXTRACTOR", "h17.10", "run-1");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        assertNull(result.targetPublicationId());
        verify(publicationFactRepository, never()).save(any());

        ArgumentCaptor<PublicationLinkConflict> conflictCaptor = ArgumentCaptor.forClass(PublicationLinkConflict.class);
        verify(conflictRepository).save(conflictCaptor.capture());
        assertEquals("10.1000/abc", conflictCaptor.getValue().getRequestedDoiNormalized());
        assertEquals(2, conflictCaptor.getValue().getCandidatePublicationIds().size());
    }

    @Test
    void linkScholarEnrichmentWritesScholarOwnedFieldsOnly() {
        ScholardexPublicationFact target = publicationFact("p1", "2-s2.0-1", "Title");
        when(publicationFactRepository.findById("p1")).thenReturn(Optional.of(target));
        when(publicationFactRepository.findByGoogleScholarId("GS:1")).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult result = service.linkScholarEnrichment(
                "p1",
                "2-s2.0-1",
                "10.1000/abc",
                "GS:1",
                "SCHOLAR",
                "h17.10",
                "run-1"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.LINKED, result.state());
        ArgumentCaptor<ScholardexPublicationFact> saved = ArgumentCaptor.forClass(ScholardexPublicationFact.class);
        verify(publicationFactRepository).save(saved.capture());
        assertEquals("GS:1", saved.getValue().getGoogleScholarId());
        assertEquals("Title", saved.getValue().getTitle());
        assertNotNull(saved.getValue().getUpdatedAt());
    }

    private ScholardexPublicationFact publicationFact(String id, String eid, String title) {
        ScholardexPublicationFact view = new ScholardexPublicationFact();
        view.setId(id);
        view.setEid(eid);
        view.setTitle(title);
        return view;
    }
}
