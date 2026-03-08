package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexPublicationCanonicalizationServiceTest {

    @Mock
    private ScopusPublicationFactRepository scopusPublicationFactRepository;
    @Mock
    private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexEdgeWriterService edgeWriterService;
    @Mock
    private ScholardexCanonicalBuildCheckpointService checkpointService;

    private ScholardexPublicationCanonicalizationService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexPublicationCanonicalizationService(
                scopusPublicationFactRepository,
                scholardexPublicationFactRepository,
                sourceLinkService,
                edgeWriterService,
                checkpointService
        );
    }

    @Test
    void canonicalIdIsDeterministicAndUsesEidPrecedence() {
        String withEid = service.buildCanonicalPublicationId(
                "2-s2.0-123",
                "WOS:1",
                "GS:1",
                "U:1",
                "10.1000/abc",
                "paper",
                "2024-01-01",
                "creator",
                "forum-1"
        );
        String sameEidDifferentOthers = service.buildCanonicalPublicationId(
                "2-s2.0-123",
                "WOS:2",
                "GS:2",
                "U:2",
                "10.1000/xyz",
                "other",
                "1999-01-01",
                "other",
                "forum-2"
        );
        String withoutEid = service.buildCanonicalPublicationId(
                null,
                "WOS:1",
                "GS:1",
                "U:1",
                "10.1000/abc",
                "paper",
                "2024-01-01",
                "creator",
                "forum-1"
        );

        assertEquals(withEid, sameEidDifferentOthers);
        assertNotEquals(withEid, withoutEid);
    }

    @Test
    void rebuildCanonicalPublicationFactsFromScopusFactsUpsertsDeterministically() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-abc");
        scopusFact.setDoi("https://doi.org/10.1000/AbC");
        scopusFact.setTitle("A Title");
        scopusFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        scopusFact.setSourceRecordId("2-s2.0-abc");
        scopusFact.setAuthors(List.of("au-1"));

        when(scopusPublicationFactRepository.findAll()).thenReturn(List.of(scopusFact));
        when(scholardexPublicationFactRepository.findByEid("2-s2.0-abc")).thenReturn(Optional.empty());
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(new ScholardexEdgeWriterService.EdgeWriteResult(true, "edge-1", true, null));

        ImportProcessingResult result = service.rebuildCanonicalPublicationFactsFromScopusFacts();

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        verify(scholardexPublicationFactRepository).save(any(ScholardexPublicationFact.class));
        verify(sourceLinkService, atLeastOnce()).link(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false));
        verify(edgeWriterService, atLeastOnce()).upsertAuthorshipEdge(any());
        verify(sourceLinkService, atLeastOnce()).findByKey(
                eq(ScholardexEntityType.AUTHOR), eq("SCOPUS_JSON_BOOTSTRAP"), eq("au-1"));
    }

    @Test
    void bridgeAuthorIdsReturnsDeterministicFallbackAndPendingMarkerWhenNoCanonicalLinkExists() {
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridged = service.bridgeAuthorIds(
                List.of("  au-1 ", "au-1"),
                "SCOPUS_JSON_BOOTSTRAP"
        );

        assertEquals(1, bridged.canonicalAuthorIds().size());
        assertEquals("au-1", bridged.pendingSourceIds().getFirst());
        assertEquals("au-1", bridged.entries().getFirst().sourceAuthorId());
        assertEquals(true, bridged.entries().getFirst().pendingResolution());
    }

    @Test
    void upsertFromScopusFactFallsBackToExistingCanonicalRecordByNormalizedDoi() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-new");
        scopusFact.setDoi("https://doi.org/10.1000/XYZ");
        scopusFact.setTitle("A Title");
        scopusFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        scopusFact.setSourceRecordId("2-s2.0-new");
        scopusFact.setAuthors(List.of("au-1"));

        ScholardexPublicationFact existingByDoi = new ScholardexPublicationFact();
        existingByDoi.setId("spub_existing");
        existingByDoi.setDoiNormalized("10.1000/xyz");

        when(scholardexPublicationFactRepository.findByEid("2-s2.0-new")).thenReturn(Optional.empty());
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1000/xyz")).thenReturn(List.of(existingByDoi));
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(new ScholardexEdgeWriterService.EdgeWriteResult(true, "edge-1", true, null));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(scopusFact, result);

        assertEquals(1, result.getUpdatedCount());
        verify(scholardexPublicationFactRepository).save(any(ScholardexPublicationFact.class));
    }
}
