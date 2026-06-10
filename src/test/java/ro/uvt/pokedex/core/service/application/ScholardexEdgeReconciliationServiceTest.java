package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexEdgeReconciliationServiceTest {

    @Mock
    private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock
    private ScholardexAuthorFactRepository authorFactRepository;
    @Mock
    private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Mock
    private ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock
    private ScholardexEdgeWriterService edgeWriterService;

    private ScholardexEdgeReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexEdgeReconciliationService(
                publicationFactRepository,
                authorFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                identityConflictRepository,
                edgeWriterService
        );
    }

    // -------------------------------------------------------------------------
    // Publication-author (authorship) reconcile
    // -------------------------------------------------------------------------

    @Test
    void reconcileEdgesCreatesMissingAuthorshipEdgeFromPublicationArray() {

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-1");
        publication.setAuthorIds(List.of("auth-1"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId("rec-1");

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-1", "auth-1", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(authorshipFactRepository.findByPublicationId("pub-1")).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-1", true));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getUpdatedCount());
        verify(edgeWriterService).upsertAuthorshipEdge(any());
    }

    @Test
    void reconcileEdgesPrunesStaleAuthorshipEdgeWhenSourceMatches() {
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-1");
        publication.setAuthorIds(List.of("auth-1"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId("rec-1");

        ScholardexAuthorshipFact staleEdge = new ScholardexAuthorshipFact();
        staleEdge.setId("edge-stale");
        staleEdge.setPublicationId("pub-1");
        staleEdge.setAuthorId("auth-2");
        staleEdge.setSource("SCOPUS");

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-1", "auth-1", "SCOPUS"))
                .thenReturn(Optional.of(new ScholardexAuthorshipFact()));
        when(authorshipFactRepository.findByPublicationId("pub-1")).thenReturn(List.of(staleEdge));
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-1", false));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(2, result.getUpdatedCount());
        verify(edgeWriterService).removeAuthorshipEdge(staleEdge);
        verify(edgeWriterService).upsertAuthorshipEdge(any());
    }

    @Test
    void reconcileEdgesForBatchUsesBatchScopedCanonicalSources() {
        when(publicationFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());
        when(authorFactRepository.findBySourceBatchId("upload-batch-7")).thenReturn(List.of());

        ImportProcessingResult result = service.reconcileEdges("upload-batch-7");

        assertEquals(0, result.getErrorCount());
        verify(publicationFactRepository).findBySourceBatchId("upload-batch-7");
        verify(authorFactRepository).findBySourceBatchId("upload-batch-7");
        verify(publicationFactRepository, never()).findAll();
        verify(authorFactRepository, never()).findAll();
    }

    @Test
    void reconcileEdgesSkipsPublicationWithNullId() {
        // L75: if (publication.getId() == null) continue — removal causes NPE/spurious lookups
        ScholardexPublicationFact nullIdPublication = new ScholardexPublicationFact();
        nullIdPublication.setId(null);
        nullIdPublication.setAuthorIds(List.of("auth-1"));
        nullIdPublication.setSource("SCOPUS");

        when(publicationFactRepository.findAll()).thenReturn(List.of(nullIdPublication));
        when(authorFactRepository.findAll()).thenReturn(List.of());

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(0, result.getUpdatedCount());
        verify(authorshipFactRepository, never()).findByPublicationIdAndAuthorIdAndSource(any(), any(), any());
    }

    @Test
    void reconcileEdgesFallsBackToUnknownWhenPublicationSourceIsNull() {
        // L327: fallbackSource RemoveConditionalMutator — without null check, null.toUpperCase() throws NPE
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-null-src");
        publication.setAuthorIds(List.of("auth-x"));
        publication.setSource(null);
        publication.setSourceRecordId("rec-x");

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-null-src", "auth-x", "UNKNOWN"))
                .thenReturn(Optional.empty());
        when(authorshipFactRepository.findByPublicationId("pub-null-src")).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-x", true));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getUpdatedCount());
        // key assertion: lookup used "UNKNOWN" not null
        verify(authorshipFactRepository).findByPublicationIdAndAuthorIdAndSource("pub-null-src", "auth-x", "UNKNOWN");
    }

    @Test
    void reconcileEdgesFallsBackToPublicationIdWhenSourceRecordIdIsNull() {
        // L332-333: fallbackSourceRecordId — without null guard, normalized null is returned as ""
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-fb");
        publication.setAuthorIds(List.of("auth-y"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId(null);  // null → fallback to id

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-fb", "auth-y", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(authorshipFactRepository.findByPublicationId("pub-fb")).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());

        ArgumentCaptor<ScholardexEdgeWriterService.EdgeWriteCommand> captor =
                ArgumentCaptor.forClass(ScholardexEdgeWriterService.EdgeWriteCommand.class);
        when(edgeWriterService.upsertAuthorshipEdge(captor.capture()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-fb", true));

        service.reconcileEdges();

        // sourceRecordId in command must use publication id ("pub-fb") as fallback, not ""
        ScholardexEdgeWriterService.EdgeWriteCommand cmd = captor.getValue();
        assertEquals("pub-fb::author::auth-y", cmd.sourceRecordId());
    }

    @Test
    void reconcileEdgesPreservesLinkStateAndReasonFromExistingEdgeDuringRepair() {
        // L303: fallbackLinkState EmptyObjectReturnValsMutator — returns "" instead of uppercased state
        // L308: fallbackLinkReason survivors — returns "" instead of existing reason
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-repair");
        publication.setAuthorIds(List.of("auth-r"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId("rec-repair");

        // healthy enough to not trigger early conditions but sourceRecordId is wrong → needs repair
        ScholardexAuthorshipFact damagedEdge = new ScholardexAuthorshipFact();
        damagedEdge.setId("edge-damaged");
        damagedEdge.setSourceRecordId("WRONG-sourcerecordid");  // mismatch triggers repair
        damagedEdge.setLinkState("CONFLICT");
        damagedEdge.setLinkReason("prior-conflict-reason");
        damagedEdge.setCreatedAt(Instant.now());
        damagedEdge.setUpdatedAt(Instant.now());

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-repair", "auth-r", "SCOPUS"))
                .thenReturn(Optional.of(damagedEdge));
        when(authorshipFactRepository.findByPublicationId("pub-repair")).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());

        ArgumentCaptor<ScholardexEdgeWriterService.EdgeWriteCommand> captor =
                ArgumentCaptor.forClass(ScholardexEdgeWriterService.EdgeWriteCommand.class);
        when(edgeWriterService.upsertAuthorshipEdge(captor.capture()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-damaged", false));

        service.reconcileEdges();

        ScholardexEdgeWriterService.EdgeWriteCommand cmd = captor.getValue();
        assertEquals("CONFLICT", cmd.linkState());
        assertEquals("prior-conflict-reason", cmd.linkReason());
    }

    @Test
    void reconcileEdgesMarksErrorWhenAuthorshipRepairFails() {
        // L110: result.markError in repair-failed path — not covered by any existing test
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-err");
        publication.setAuthorIds(List.of("auth-e"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId("rec-err");

        ScholardexAuthorshipFact damagedEdge = new ScholardexAuthorshipFact();
        // null id → needsEdgeRepair returns true
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-err", "auth-e", "SCOPUS"))
                .thenReturn(Optional.of(damagedEdge));
        when(authorshipFactRepository.findByPublicationId("pub-err")).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.invalid("edge-write-failed"));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getUpdatedCount());
    }

    @Test
    void reconcileEdgesOpensAmbiguousConflictWhenAuthorshipEdgeSourceDiffers() {
        // L139-140: markSkipped + continue path in prune loop when edge source differs from publication source
        // Also covers openAmbiguousConflict (L251-267)
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-ambig");
        publication.setAuthorIds(List.of("auth-1"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId("rec-ambig");

        ScholardexAuthorshipFact crossSourceEdge = new ScholardexAuthorshipFact();
        crossSourceEdge.setId("edge-cross");
        crossSourceEdge.setAuthorId("auth-1");
        crossSourceEdge.setSource("WOS");  // different from publication source "SCOPUS"

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-ambig", "auth-1", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(authorshipFactRepository.findByPublicationId("pub-ambig")).thenReturn(List.of(crossSourceEdge));
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-new", true));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(identityConflictRepository.save(any())).thenReturn(new ScholardexIdentityConflict());

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getSkippedCount());  // ambiguous edge skipped
        assertEquals(1, result.getUpdatedCount());  // new edge created for auth-1
        verify(identityConflictRepository).save(any());
    }

    // -------------------------------------------------------------------------
    // Author-affiliation reconcile
    // -------------------------------------------------------------------------

    @Test
    void reconcileEdgesCreatesMissingAuthorAffiliationEdge() {
        // Covers reconcileAuthorAffiliationEdges create path — previously zero-coverage
        ScholardexAuthorFact author = new ScholardexAuthorFact();
        author.setId("author-1");
        author.setAffiliationIds(List.of("affil-1"));
        author.setSource("SCOPUS");
        author.setSourceRecordId("rec-a1");

        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(author));
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("author-1", "affil-1", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(authorAffiliationFactRepository.findByAuthorId("author-1")).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorAffiliationEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-aa-1", true));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getUpdatedCount());
        verify(edgeWriterService).upsertAuthorAffiliationEdge(any());
    }

    @Test
    void reconcileEdgesPrunesStaleAuthorAffiliationEdgeAndSkipsHealthyEdge() {
        // Healthy existing edge for affil-1 (needsEdgeRepair=false → no repair call)
        // Stale edge for affil-2 with same source → deleted
        // Kills needsEdgeRepair BooleanTrueReturnValsMutator at L281
        ScholardexAuthorFact author = new ScholardexAuthorFact();
        author.setId("author-2");
        author.setAffiliationIds(List.of("affil-1"));
        author.setSource("SCOPUS");
        author.setSourceRecordId("rec-a2");

        ScholardexAuthorAffiliationFact healthyEdge = new ScholardexAuthorAffiliationFact();
        healthyEdge.setId("edge-aa-ok");
        healthyEdge.setAuthorId("author-2");
        healthyEdge.setAffiliationId("affil-1");
        healthyEdge.setSource("SCOPUS");
        healthyEdge.setSourceRecordId("rec-a2::affiliation::affil-1");
        healthyEdge.setLinkState("LINKED");
        healthyEdge.setLinkReason("edge-reconcile");
        healthyEdge.setCreatedAt(Instant.now());
        healthyEdge.setUpdatedAt(Instant.now());

        ScholardexAuthorAffiliationFact staleEdge = new ScholardexAuthorAffiliationFact();
        staleEdge.setId("edge-aa-stale");
        staleEdge.setAuthorId("author-2");
        staleEdge.setAffiliationId("affil-2");  // not in expected set
        staleEdge.setSource("SCOPUS");

        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(author));
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("author-2", "affil-1", "SCOPUS"))
                .thenReturn(Optional.of(healthyEdge));
        when(authorAffiliationFactRepository.findByAuthorId("author-2"))
                .thenReturn(List.of(healthyEdge, staleEdge));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getUpdatedCount());
        verify(edgeWriterService).removeAuthorAffiliationEdge(staleEdge);
        verify(edgeWriterService, never()).upsertAuthorAffiliationEdge(any());
    }

    @Test
    void reconcileEdgesRepairsAuthorAffiliationEdgeWithDamagedSourceRecordId() {
        // Existing AA edge has mismatched sourceRecordId → needsEdgeRepair=true → repair called
        ScholardexAuthorFact author = new ScholardexAuthorFact();
        author.setId("author-3");
        author.setAffiliationIds(List.of("affil-3"));
        author.setSource("SCOPUS");
        author.setSourceRecordId("rec-a3");

        ScholardexAuthorAffiliationFact damagedEdge = new ScholardexAuthorAffiliationFact();
        damagedEdge.setId("edge-aa-damaged");
        damagedEdge.setSourceRecordId("STALE-RECORD-ID");  // mismatch triggers repair
        damagedEdge.setLinkState("LINKED");
        damagedEdge.setLinkReason("edge-reconcile");
        damagedEdge.setCreatedAt(Instant.now());
        damagedEdge.setUpdatedAt(Instant.now());

        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(author));
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("author-3", "affil-3", "SCOPUS"))
                .thenReturn(Optional.of(damagedEdge));
        when(authorAffiliationFactRepository.findByAuthorId("author-3")).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorAffiliationEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-aa-damaged", false));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getUpdatedCount());
        verify(edgeWriterService).upsertAuthorAffiliationEdge(any());
    }

    @Test
    void reconcileEdgesMarksErrorWhenAuthorAffiliationRepairFails() {
        // Covers AA repair-failed branch (mirror of L110 in authorship path)
        ScholardexAuthorFact author = new ScholardexAuthorFact();
        author.setId("author-err");
        author.setAffiliationIds(List.of("affil-err"));
        author.setSource("SCOPUS");
        author.setSourceRecordId("rec-err");

        ScholardexAuthorAffiliationFact damagedEdge = new ScholardexAuthorAffiliationFact();
        // null id → needsEdgeRepair returns true

        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(author));
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("author-err", "affil-err", "SCOPUS"))
                .thenReturn(Optional.of(damagedEdge));
        when(authorAffiliationFactRepository.findByAuthorId("author-err")).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorAffiliationEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.invalid("write-failed"));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getUpdatedCount());
    }

    @Test
    void reconcileEdgesSkipsAuthorWithNullId() {
        // Author with null id must be skipped — parallel to L75 for publications
        ScholardexAuthorFact author = new ScholardexAuthorFact();
        author.setId(null);
        author.setAffiliationIds(List.of("affil-1"));
        author.setSource("SCOPUS");

        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(author));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(0, result.getUpdatedCount());
        verify(authorAffiliationFactRepository, never()).findByAuthorIdAndAffiliationIdAndSource(any(), any(), any());
    }

    @Test
    void reconcileEdgesMarksErrorWhenAuthorAffiliationCreateFails() {
        ScholardexAuthorFact author = new ScholardexAuthorFact();
        author.setId("author-create-fail");
        author.setAffiliationIds(List.of("aff-create-fail"));
        author.setSource("SCOPUS");
        author.setSourceRecordId("rec-create-fail");

        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(author));
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource(
                "author-create-fail", "aff-create-fail", "SCOPUS"
        )).thenReturn(Optional.empty());
        when(authorAffiliationFactRepository.findByAuthorId("author-create-fail")).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorAffiliationEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.invalid("write-failed"));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getUpdatedCount());
    }

    @Test
    void reconcileEdgesMarksErrorWhenAuthorshipCreateFails() {
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-create-fail");
        publication.setAuthorIds(List.of("auth-create-fail"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId("rec-create-fail");

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource(
                "pub-create-fail", "auth-create-fail", "SCOPUS"
        )).thenReturn(Optional.empty());
        when(authorshipFactRepository.findByPublicationId("pub-create-fail")).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.invalid("write-failed"));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getUpdatedCount());
    }

    @Test
    void reconcileEdgesSkipsAuthorAffiliationEdgeWhenExistingEdgeSourceDiffers() {
        ScholardexAuthorFact author = new ScholardexAuthorFact();
        author.setId("author-ambig-aa");
        author.setAffiliationIds(List.of("aff-1"));
        author.setSource("SCOPUS");
        author.setSourceRecordId("rec-aa");

        ScholardexAuthorAffiliationFact crossSourceEdge = new ScholardexAuthorAffiliationFact();
        crossSourceEdge.setId("edge-aa-cross");
        crossSourceEdge.setAffiliationId("aff-1");
        crossSourceEdge.setSource("WOS");

        when(publicationFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of(author));
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("author-ambig-aa", "aff-1", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(authorAffiliationFactRepository.findByAuthorId("author-ambig-aa"))
                .thenReturn(List.of(crossSourceEdge));
        when(edgeWriterService.upsertAuthorAffiliationEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-aa-new", true));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(identityConflictRepository.save(any())).thenReturn(new ScholardexIdentityConflict());

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getSkippedCount());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }
}
