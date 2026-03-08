package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexEdgeWriterServiceTest {

    @Mock
    private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Mock
    private ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;

    @Test
    void upsertAuthorshipEdgeCreatesDeterministicEdgeAndSourceLink() {
        ScholardexEdgeWriterService service = new ScholardexEdgeWriterService(
                authorshipFactRepository,
                authorAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository
        );
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p1", "a1", "SCOPUS"))
                .thenReturn(Optional.empty());
        ScholardexSourceLink linked = new ScholardexSourceLink();
        when(sourceLinkService.link(eq(ScholardexEntityType.AUTHORSHIP), eq("SCOPUS"), eq("rec-1"), anyString(), eq("bridge"), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(linked));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorshipEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p1", "a1", "SCOPUS", "rec-1", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED,
                        "bridge",
                        false
                )
        );

        assertTrue(result.accepted());
        assertTrue(result.canonicalEdgeId().startsWith("sae_"));

        ArgumentCaptor<ScholardexAuthorshipFact> captor = ArgumentCaptor.forClass(ScholardexAuthorshipFact.class);
        verify(authorshipFactRepository).save(captor.capture());
        assertEquals(result.canonicalEdgeId(), captor.getValue().getId());
        assertEquals("p1", captor.getValue().getPublicationId());
        assertEquals("a1", captor.getValue().getAuthorId());
    }

    @Test
    void upsertAuthorAffiliationEdgeOpensConflictWhenExistingIdDiffersFromDeterministic() {
        ScholardexEdgeWriterService service = new ScholardexEdgeWriterService(
                authorshipFactRepository,
                authorAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository
        );
        ScholardexAuthorAffiliationFact existing = new ScholardexAuthorAffiliationFact();
        existing.setId("legacy_edge_id");
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("a1", "f1", "SCOPUS"))
                .thenReturn(Optional.of(existing));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.AUTHOR_AFFILIATION), eq("SCOPUS"), eq("rec-2"), eq(ScholardexEdgeWriterService.REASON_EDGE_CANONICAL_ID_MISMATCH), eq("OPEN")))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(eq(ScholardexEntityType.AUTHOR_AFFILIATION), eq("SCOPUS"), eq("rec-2"), eq("legacy_edge_id"), eq("bridge"), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a1", "f1", "SCOPUS", "rec-2", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED,
                        "bridge",
                        false
                )
        );

        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
        verify(authorAffiliationFactRepository).save(any(ScholardexAuthorAffiliationFact.class));
    }

    @Test
    void batchUpsertAuthorAffiliationEdgesPersistsInBulk() {
        ScholardexEdgeWriterService service = new ScholardexEdgeWriterService(
                authorshipFactRepository,
                authorAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository
        );
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("a1", "f1", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a1", "f1", "SCOPUS", "rec-1", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED,
                        "bridge",
                        false
                )),
                Map.of(),
                Map.of()
        );

        assertEquals(1, result.accepted());
        verify(authorAffiliationFactRepository).saveAll(any());
        verify(sourceLinkService).batchUpsertWithState(any(), any(), anyBoolean());
    }
}
