package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationLinkConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

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
        ScholardexPublicationFact target = publicationFact("p-id", "2-s2.0-other", "old-title");
        when(publicationFactRepository.findById("p-id")).thenReturn(Optional.of(target));
        when(publicationFactRepository.findByWosId("WOS:1")).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult result =
                service.linkWosEnrichment("p-id", "2-s2.0-eid", "10.1000/abc", "WOS:1", "WOSEXTRACTOR", "h17.10", "run-1");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.LINKED, result.state());
        verify(publicationFactRepository, never()).findByEid(anyString());
        verify(publicationFactRepository, never()).findAllByDoiNormalized(anyString());

        ArgumentCaptor<ScholardexPublicationFact> saved = ArgumentCaptor.forClass(ScholardexPublicationFact.class);
        verify(publicationFactRepository).save(saved.capture());
        assertEquals("WOS:1", saved.getValue().getWosId());
        assertEquals("WOSEXTRACTOR", saved.getValue().getSource());
        assertEquals("WOS:1", saved.getValue().getSourceRecordId());
        assertEquals("run-1", saved.getValue().getSourceBatchId());
        assertEquals("h17.10", saved.getValue().getSourceCorrelationId());
        assertNotNull(saved.getValue().getUpdatedAt());
        assertEquals("old-title", saved.getValue().getTitle());
        verify(sourceLinkService).link(any(), eq("WOSEXTRACTOR"), eq("WOS:1"), eq("p-id"), eq("wos-link"), any(), eq("run-1"), eq("h17.10"), eq(false));
    }

    @Test
    void linkWosEnrichmentFallsBackToEidAndThenNormalizedDoi() {
        ScholardexPublicationFact byEidTarget = publicationFact("p-eid", "2-s2.0-eid", null);
        when(publicationFactRepository.findByEid("2-s2.0-eid")).thenReturn(Optional.of(byEidTarget));
        when(publicationFactRepository.findByWosId("WOS:EID")).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult eidResult =
                service.linkWosEnrichment(null, "2-s2.0-eid", null, "WOS:EID", "WOSEXTRACTOR", "h17.10", "run-1");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.LINKED, eidResult.state());

        ScholardexPublicationFact byDoiTarget = publicationFact("p-doi", "2-s2.0-other", null);
        when(publicationFactRepository.findAllByDoiNormalized("10.1000/abc")).thenReturn(List.of(byDoiTarget));
        when(publicationFactRepository.findByWosId("WOS:DOI")).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult doiResult =
                service.linkWosEnrichment(null, null, "https://doi.org/10.1000/AbC", "WOS:DOI", "WOSEXTRACTOR", "h17.10", "run-2");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.LINKED, doiResult.state());
    }

    @Test
    void linkWosEnrichmentSkipsNonWosSentinel() {
        PublicationEnrichmentLinkerService.LinkResult result =
                service.linkWosEnrichment("p1", null, null, CanonicalPublicationConstants.NON_WOS_ID, "WOSEXTRACTOR", "h17.10", "run-1");

        assertEquals(PublicationEnrichmentLinkerService.LinkState.SKIPPED, result.state());
        verify(publicationFactRepository, never()).save(any());
        verify(conflictRepository, never()).save(any());
    }

    @Test
    void linkWosEnrichmentQuarantinesConflictWhenKeyAlreadyAssigned() {
        ScholardexPublicationFact target = publicationFact("p-target", "2-s2.0-target", null);
        ScholardexPublicationFact other = publicationFact("p-other", "2-s2.0-other", null);
        when(publicationFactRepository.findById("p-target")).thenReturn(Optional.of(target));
        when(publicationFactRepository.findByWosId("WOS:1")).thenReturn(Optional.of(other));

        PublicationEnrichmentLinkerService.LinkResult result =
                service.linkWosEnrichment("p-target", null, null, "WOS:1", "WOSEXTRACTOR", "h17.10", "run-1");

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
        ScholardexPublicationFact p1 = publicationFact("p1", "2-s2.0-1", null);
        ScholardexPublicationFact p2 = publicationFact("p2", "2-s2.0-2", null);
        when(publicationFactRepository.findById("p-ignore")).thenReturn(Optional.empty());
        when(publicationFactRepository.findAllByDoiNormalized(anyString())).thenReturn(List.of(p1, p2));

        PublicationEnrichmentLinkerService.LinkResult result =
                service.linkWosEnrichment("p-ignore", null, "doi:10.1000/ABC", "WOS:2", "WOSEXTRACTOR", "h17.10", "run-1");

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

    @Test
    void linkScholarEnrichmentSkipsWhenScholarIdBlank() {
        PublicationEnrichmentLinkerService.LinkResult result = service.linkScholarEnrichment(
                "p1", "2-s2.0-1", "10.1000/abc", "   ", "SCHOLAR", "h17.10", "run-1"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.SKIPPED, result.state());
        verify(publicationFactRepository, never()).save(any());
        verify(conflictRepository, never()).save(any());
    }

    @Test
    void linkScholarEnrichmentQuarantinesAmbiguousDoiMatch() {
        ScholardexPublicationFact p1 = publicationFact("p1", "2-s2.0-1", null);
        ScholardexPublicationFact p2 = publicationFact("p2", "2-s2.0-2", null);
        when(publicationFactRepository.findById("p-ignore")).thenReturn(Optional.empty());
        when(publicationFactRepository.findAllByDoiNormalized("10.1000/abc")).thenReturn(List.of(p1, p2));

        PublicationEnrichmentLinkerService.LinkResult result = service.linkScholarEnrichment(
                "p-ignore", null, "doi:10.1000/AbC", "GS:1", "SCHOLAR", "h17.10", "run-1"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        verify(publicationFactRepository, never()).save(any());
        verify(conflictRepository).save(any(PublicationLinkConflict.class));
    }

    @Test
    void linkScholarEnrichmentQuarantinesWhenTargetHasDifferentScholarId() {
        ScholardexPublicationFact target = publicationFact("p1", "2-s2.0-1", null);
        target.setGoogleScholarId("GS:OLD");
        when(publicationFactRepository.findById("p1")).thenReturn(Optional.of(target));

        PublicationEnrichmentLinkerService.LinkResult result = service.linkScholarEnrichment(
                "p1", null, null, "GS:NEW", "SCHOLAR", "h17.10", "run-1"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        assertEquals("p1", result.targetPublicationId());
        verify(publicationFactRepository, never()).save(any());
        verify(conflictRepository).save(any(PublicationLinkConflict.class));
    }

    @Test
    void linkScholarEnrichmentQuarantinesWhenKeyAlreadyAssignedElsewhere() {
        ScholardexPublicationFact target = publicationFact("p1", "2-s2.0-1", null);
        ScholardexPublicationFact other = publicationFact("p2", "2-s2.0-2", null);
        when(publicationFactRepository.findById("p1")).thenReturn(Optional.of(target));
        when(publicationFactRepository.findByGoogleScholarId("GS:1")).thenReturn(Optional.of(other));

        PublicationEnrichmentLinkerService.LinkResult result = service.linkScholarEnrichment(
                "p1", null, null, "GS:1", "SCHOLAR", "h17.10", "run-1"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        verify(publicationFactRepository, never()).save(any());
        verify(conflictRepository).save(any(PublicationLinkConflict.class));
    }

    @Test
    void linkWosEnrichmentQuarantinesWhenTargetHasDifferentWosId() {
        ScholardexPublicationFact target = publicationFact("p1", "2-s2.0-1", null);
        target.setWosId("WOS:OLD");
        when(publicationFactRepository.findById("p1")).thenReturn(Optional.of(target));

        PublicationEnrichmentLinkerService.LinkResult result = service.linkWosEnrichment(
                "p1", null, null, "WOS:NEW", "WOSEXTRACTOR", "h17.10", "run-1"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        assertEquals("p1", result.targetPublicationId());
        verify(publicationFactRepository, never()).save(any());
        verify(conflictRepository).save(any(PublicationLinkConflict.class));
    }

    @Test
    void saveGenericConflictUsesFallbackRecordIdAndPersistsCandidateIds() {
        ScholardexPublicationFact p1 = publicationFact("p1", "2-s2.0-1", null);
        ScholardexPublicationFact p2 = publicationFact("p2", "2-s2.0-2", null);
        when(publicationFactRepository.findAllByDoiNormalized("10.2000/x")).thenReturn(List.of(p1, p2));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), eq("SCHOLAR"), eq("10.2000/x"), anyString(), anyString()
        )).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult result = service.linkScholarEnrichment(
                " ", " ", "https://doi.org/10.2000/X", "GS:2", "SCHOLAR", "v1", "run-22"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        ArgumentCaptor<ScholardexIdentityConflict> identityCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(identityCaptor.capture());
        assertEquals(ScholardexEntityType.PUBLICATION, identityCaptor.getValue().getEntityType());
        assertEquals("SCHOLAR", identityCaptor.getValue().getIncomingSource());
        assertEquals("10.2000/x", identityCaptor.getValue().getIncomingSourceRecordId());
        assertEquals("AMBIGUOUS_DOI_MATCH", identityCaptor.getValue().getReasonCode());
        assertEquals("OPEN", identityCaptor.getValue().getStatus());
        assertNotNull(identityCaptor.getValue().getDetectedAt());
        assertEquals(2, identityCaptor.getValue().getCandidateCanonicalIds().size());
    }

    @Test
    void saveConflictPersistsIdentityConflictBeforePublicationLinkConflict() {
        ScholardexPublicationFact target = publicationFact("p-target", "2-s2.0-target", null);
        ScholardexPublicationFact other = publicationFact("p-other", "2-s2.0-other", null);
        when(publicationFactRepository.findById("p-target")).thenReturn(Optional.of(target));
        when(publicationFactRepository.findByWosId("WOS:LOCKED")).thenReturn(Optional.of(other));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOSEXTRACTOR"), eq("p-target"), anyString(), eq("OPEN")
        )).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult result = service.linkWosEnrichment(
                "p-target", null, null, "WOS:LOCKED", "WOSEXTRACTOR", "v9", "run-9"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        InOrder order = inOrder(identityConflictRepository, conflictRepository);
        ArgumentCaptor<ScholardexIdentityConflict> identityCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        ArgumentCaptor<PublicationLinkConflict> conflictCaptor = ArgumentCaptor.forClass(PublicationLinkConflict.class);
        order.verify(identityConflictRepository).save(identityCaptor.capture());
        order.verify(conflictRepository).save(conflictCaptor.capture());
        assertEquals(ScholardexEntityType.PUBLICATION, identityCaptor.getValue().getEntityType());
        assertEquals("WOSEXTRACTOR", identityCaptor.getValue().getIncomingSource());
        assertEquals("p-target", identityCaptor.getValue().getIncomingSourceRecordId());
        assertEquals("ENRICHMENT_KEY_ALREADY_ASSIGNED", identityCaptor.getValue().getReasonCode());
        assertEquals("OPEN", identityCaptor.getValue().getStatus());
        assertNotNull(identityCaptor.getValue().getDetectedAt());
        assertEquals("ENRICHMENT_LINK_CONFLICT", conflictCaptor.getValue().getConflictType());
        assertEquals("ENRICHMENT_KEY_ALREADY_ASSIGNED", conflictCaptor.getValue().getConflictReason());
        assertEquals("WOSEXTRACTOR", conflictCaptor.getValue().getEnrichmentSource());
        assertEquals("p-target", conflictCaptor.getValue().getRequestedPublicationId());
        assertEquals("v9", conflictCaptor.getValue().getLinkerVersion());
        assertEquals("run-9", conflictCaptor.getValue().getLinkerRunId());
        assertNotNull(conflictCaptor.getValue().getDetectedAt());
    }

    @Test
    void saveGenericConflictReusesOpenConflictAndRefreshesCandidateIds() {
        ScholardexIdentityConflict existing = new ScholardexIdentityConflict();
        existing.setId("c1");
        existing.setDetectedAt(java.time.Instant.parse("2026-04-01T00:00:00Z"));
        existing.setCandidateCanonicalIds(List.of("old"));

        ScholardexPublicationFact p1 = publicationFact("p1", "2-s2.0-1", null);
        ScholardexPublicationFact p2 = publicationFact("p2", "2-s2.0-2", null);
        when(publicationFactRepository.findById("p-ignore")).thenReturn(Optional.empty());
        when(publicationFactRepository.findAllByDoiNormalized("10.3000/x")).thenReturn(List.of(p1, p2));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("SCHOLAR"), eq("p-ignore"), eq("AMBIGUOUS_DOI_MATCH"), eq("OPEN")
        )).thenReturn(Optional.of(existing));

        PublicationEnrichmentLinkerService.LinkResult result = service.linkScholarEnrichment(
                "p-ignore", null, "doi:10.3000/X", "GS:77", "SCHOLAR", "v3", "run-3"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.CONFLICT, result.state());
        ArgumentCaptor<ScholardexIdentityConflict> captor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(captor.capture());
        assertEquals("c1", captor.getValue().getId());
        assertEquals(java.time.Instant.parse("2026-04-01T00:00:00Z"), captor.getValue().getDetectedAt());
        assertEquals(List.of("p1", "p2"), captor.getValue().getCandidateCanonicalIds());
    }

    @Test
    void linkScholarEnrichmentWritesSourceOwnershipFieldsAndUpsertsSourceLink() {
        ScholardexPublicationFact target = publicationFact("p1", "2-s2.0-1", null);
        when(publicationFactRepository.findById("p1")).thenReturn(Optional.of(target));
        when(publicationFactRepository.findByGoogleScholarId("GS:1")).thenReturn(Optional.empty());

        PublicationEnrichmentLinkerService.LinkResult result = service.linkScholarEnrichment(
                "p1", null, null, "GS:1", "SCHOLAR", "v2", "run-44"
        );

        assertEquals(PublicationEnrichmentLinkerService.LinkState.LINKED, result.state());
        ArgumentCaptor<ScholardexPublicationFact> saved = ArgumentCaptor.forClass(ScholardexPublicationFact.class);
        verify(publicationFactRepository).save(saved.capture());
        assertEquals("SCHOLAR", saved.getValue().getSource());
        assertEquals("GS:1", saved.getValue().getSourceRecordId());
        assertEquals("run-44", saved.getValue().getSourceBatchId());
        assertEquals("v2", saved.getValue().getSourceCorrelationId());
        assertNotNull(saved.getValue().getUpdatedAt());

        verify(sourceLinkService).link(any(), eq("SCHOLAR"), eq("GS:1"), eq("p1"), eq("scholar-link"), any(), eq("run-44"), eq("v2"), eq(false));
    }

    @Test
    void linkWosEnrichmentReturnsInvalidForNullPublicationRef() {
        PublicationEnrichmentLinkerService.LinkResult result = service.linkWosEnrichment(
                null, null, null, "WOS:1", "WOSEXTRACTOR", "v", "run"
        );
        assertEquals(PublicationEnrichmentLinkerService.LinkState.INVALID, result.state());
        assertTrue(result.reason().contains("null-publication"));
        verify(publicationFactRepository, never()).save(any());
    }

    @Test
    void linkWosEnrichmentReturnsUnmatchedWhenNoIdEidOrDoiMatches() {
        when(publicationFactRepository.findById("p1")).thenReturn(Optional.empty());
        when(publicationFactRepository.findByEid("eid")).thenReturn(Optional.empty());
        when(publicationFactRepository.findAllByDoiNormalized("10.1000/x")).thenReturn(List.of());

        PublicationEnrichmentLinkerService.LinkResult result = service.linkWosEnrichment(
                "p1", "eid", "doi:10.1000/X", "WOS:1", "WOSEXTRACTOR", "v", "run"
        );
        assertEquals(PublicationEnrichmentLinkerService.LinkState.UNMATCHED, result.state());
        assertFalse(result.reason().isBlank());
    }

    private ScholardexPublicationFact publicationFact(String id, String eid, String title) {
        ScholardexPublicationFact view = new ScholardexPublicationFact();
        view.setId(id);
        view.setEid(eid);
        view.setTitle(title);
        return view;
    }
}
