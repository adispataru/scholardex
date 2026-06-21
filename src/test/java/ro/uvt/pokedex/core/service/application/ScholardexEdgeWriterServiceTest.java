package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H58: edges are persisted as facts only — the edge writer no longer writes a separate edge source link.
 * The edge fact (HasEdgeLineageFields) carries lineage + linkState and drives the no-op skip; the edge id
 * is deterministic so a relink to a different canonical id is impossible. These tests assert the edge-fact
 * behaviour and that {@code sourceLinkService} is never invoked for edges.
 */
@ExtendWith(MockitoExtension.class)
class ScholardexEdgeWriterServiceTest {

    @Mock private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Mock private ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    @Mock private ScholardexPublicationAuthorAffiliationFactRepository publicationAuthorAffiliationFactRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private BulkOperations bulkOps;

    private ScholardexEdgeWriterService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexEdgeWriterService(
                authorshipFactRepository,
                authorAffiliationFactRepository,
                publicationAuthorAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                mongoTemplate
        );
    }

    private ScholardexEdgeWriterService.EdgeWriteCommand authorshipCommand(
            String pub, String author, String source, String recordId) {
        return new ScholardexEdgeWriterService.EdgeWriteCommand(
                pub, author, source, recordId, "evt", "b1", "c1",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false);
    }

    // ── single-edge upserts ────────────────────────────────────────────────

    @Test
    void upsertAuthorshipEdgeCreatesDeterministicEdgeFactAndNoSourceLink() {
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p1", "a1", "SCOPUS"))
                .thenReturn(Optional.empty());

        ScholardexEdgeWriterService.EdgeWriteResult result =
                service.upsertAuthorshipEdge(authorshipCommand("p1", "a1", "SCOPUS", "rec-1"));

        assertTrue(result.accepted());
        assertTrue(result.canonicalEdgeId().startsWith("sae_"));
        ArgumentCaptor<ScholardexAuthorshipFact> captor = ArgumentCaptor.forClass(ScholardexAuthorshipFact.class);
        verify(authorshipFactRepository).save(captor.capture());
        assertEquals(result.canonicalEdgeId(), captor.getValue().getId());
        assertEquals("p1", captor.getValue().getPublicationId());
        assertEquals("a1", captor.getValue().getAuthorId());
        assertEquals("rec-1", captor.getValue().getSourceRecordId());
        assertEquals(ScholardexSourceLinkService.STATE_LINKED, captor.getValue().getLinkState());
        verify(sourceLinkService, never()).link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void upsertAuthorshipEdgeOpensConflictWhenExistingIdDiffersFromDeterministic() {
        ScholardexAuthorshipFact existing = new ScholardexAuthorshipFact();
        existing.setId("legacy_edge_id");
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p1", "a1", "SCOPUS"))
                .thenReturn(Optional.of(existing));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.AUTHORSHIP), eq("SCOPUS"), eq("rec-2"),
                eq(ScholardexEdgeWriterService.REASON_EDGE_CANONICAL_ID_MISMATCH), eq("OPEN")))
                .thenReturn(Optional.empty());

        service.upsertAuthorshipEdge(authorshipCommand("p1", "a1", "SCOPUS", "rec-2"));

        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
        verify(authorshipFactRepository).save(any(ScholardexAuthorshipFact.class));
    }

    @Test
    void upsertAuthorshipEdgeSetsCreatedAtAndLineageOnNewEdge() {
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p1", "a1", "SCOPUS"))
                .thenReturn(Optional.empty());

        service.upsertAuthorshipEdge(authorshipCommand("p1", "a1", "SCOPUS", "rec-1"));

        ArgumentCaptor<ScholardexAuthorshipFact> captor = ArgumentCaptor.forClass(ScholardexAuthorshipFact.class);
        verify(authorshipFactRepository).save(captor.capture());
        assertNotNull(captor.getValue().getCreatedAt());
        assertNotNull(captor.getValue().getUpdatedAt());
        assertEquals("evt", captor.getValue().getSourceEventId());
        assertEquals("b1", captor.getValue().getSourceBatchId());
        assertEquals("c1", captor.getValue().getSourceCorrelationId());
    }

    @Test
    void upsertAuthorshipEdgeReturnsInvalidWhenLeftIdIsBlank() {
        ScholardexEdgeWriterService.EdgeWriteResult result =
                service.upsertAuthorshipEdge(authorshipCommand("  ", "a1", "SCOPUS", "rec-1"));
        assertFalse(result.accepted());
        verify(authorshipFactRepository, never()).save(any());
    }

    @Test
    void batchUpsertAuthorshipEdgesStampsCorrespondingFromKeys() {
        // H73 S3.5: the batch sets corresponding=true only for edges whose "pub|author" key is in the set.
        List<ScholardexEdgeWriterService.EdgeWriteCommand> cmds = List.of(
                authorshipCommand("p1", "a1", "OPENALEX", "rec-1"),
                authorshipCommand("p1", "a2", "OPENALEX", "rec-2"));

        service.batchUpsertAuthorshipEdges(cmds, java.util.Set.of("p1|a1"), null, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScholardexAuthorshipFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(authorshipFactRepository).insert(captor.capture());
        ScholardexAuthorshipFact a1 = captor.getValue().stream().filter(e -> "a1".equals(e.getAuthorId())).findFirst().orElseThrow();
        ScholardexAuthorshipFact a2 = captor.getValue().stream().filter(e -> "a2".equals(e.getAuthorId())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, a1.getCorresponding());            // in the keys set
        assertFalse(Boolean.TRUE.equals(a2.getCorresponding()));      // not in the set → untouched (null)
    }

    @Test
    void upsertAuthorAffiliationEdgeCreatesDeterministicEdgeFactAndNoSourceLink() {
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("a1", "f1", "SCOPUS"))
                .thenReturn(Optional.empty());

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorAffiliationEdge(
                authorshipCommand("a1", "f1", "SCOPUS", "rec-2"));

        assertTrue(result.accepted());
        assertTrue(result.canonicalEdgeId().startsWith("saae_"));
        verify(authorAffiliationFactRepository).save(any(ScholardexAuthorAffiliationFact.class));
        verify(sourceLinkService, never()).link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void upsertPublicationAuthorAffiliationEdgeCreatesDeterministicEdgeFactAndNoSourceLink() {
        when(publicationAuthorAffiliationFactRepository
                .findByPublicationIdAndAuthorIdAndAffiliationIdAndSource("p1", "a1", "f1", "SCOPUS"))
                .thenReturn(Optional.empty());

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertPublicationAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p1", "a1", "f1", "SCOPUS", "rec-3", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false));

        assertTrue(result.accepted());
        assertTrue(result.canonicalEdgeId().startsWith("spaaf_"));
        verify(publicationAuthorAffiliationFactRepository).save(any(ScholardexPublicationAuthorAffiliationFact.class));
        verify(sourceLinkService, never()).link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void upsertPublicationAuthorAffiliationEdgeReturnsInvalidWhenPublicationIdIsBlank() {
        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertPublicationAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        " ", "a1", "f1", "SCOPUS", "rec-3", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false));
        assertFalse(result.accepted());
        verify(publicationAuthorAffiliationFactRepository, never()).save(any());
    }

    // ── batch authorship ───────────────────────────────────────────────────

    @Test
    void batchUpsertAuthorshipEdgesInsertsNewInBulkAndWritesNoSourceLink() {
        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(authorshipCommand("pbatch", "abatch", "SCOPUS", "rec-batch")),
                Map.of(),
                false);

        assertEquals(1, result.accepted());
        assertEquals(1, result.created());
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Iterable> insertCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(authorshipFactRepository).insert(insertCaptor.capture());
        ScholardexAuthorshipFact created = (ScholardexAuthorshipFact) insertCaptor.getValue().iterator().next();
        assertTrue(created.getId().startsWith("sae_"));
        assertEquals("pbatch", created.getPublicationId());
        assertEquals("abatch", created.getAuthorId());
        assertEquals("rec-batch", created.getSourceRecordId());
        assertEquals(ScholardexSourceLinkService.STATE_LINKED, created.getLinkState());
        verify(sourceLinkService, never()).batchUpsertWithState(any(), any(), anyBoolean());
    }

    @Test
    void batchUpsertAuthorshipEdgesUpdatesViaBulkOpsWhenLineageChanged() {
        String deterministicId = service.buildAuthorshipId("p9", "a9", "SCOPUS");
        ScholardexAuthorshipFact existingEdge = new ScholardexAuthorshipFact();
        existingEdge.setId(deterministicId);
        existingEdge.setSourceRecordId("old-record-id");
        existingEdge.setCreatedAt(Instant.parse("2021-01-01T00:00:00Z"));
        when(mongoTemplate.bulkOps(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(bulkOps);
        when(bulkOps.updateOne(any(), any())).thenReturn(bulkOps);

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(authorshipCommand("p9", "a9", "SCOPUS", "new-record-id")),
                Map.of("p9|a9|SCOPUS", existingEdge),
                false);

        assertEquals(1, result.accepted());
        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        verify(mongoTemplate).bulkOps(any(), eq(ScholardexAuthorshipFact.class));
        verify(bulkOps).execute();
        verify(sourceLinkService, never()).batchUpsertWithState(any(), any(), anyBoolean());
    }

    @Test
    void batchUpsertAuthorshipEdgesSkipsWhenLineageUnchanged() {
        String deterministicId = service.buildAuthorshipId("p-stable", "a-stable", "SCOPUS");
        ScholardexAuthorshipFact existingEdge = new ScholardexAuthorshipFact();
        existingEdge.setId(deterministicId);
        existingEdge.setCreatedAt(Instant.parse("2022-01-01T00:00:00Z"));
        existingEdge.setSourceRecordId("rec-stable");
        existingEdge.setSourceEventId("evt");
        existingEdge.setSourceBatchId("b1");
        existingEdge.setSourceCorrelationId("c1");
        existingEdge.setLinkState(ScholardexSourceLinkService.STATE_LINKED);
        existingEdge.setLinkReason("bridge");

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(authorshipCommand("p-stable", "a-stable", "SCOPUS", "rec-stable")),
                Map.of("p-stable|a-stable|SCOPUS", existingEdge),
                false);

        assertEquals(1, result.accepted());
        assertEquals(0, result.created());
        assertEquals(0, result.updated());
        verify(mongoTemplate, never()).bulkOps(any(), eq(ScholardexAuthorshipFact.class));
        verify(authorshipFactRepository, never()).insert(any(Iterable.class));
    }

    @Test
    void batchUpsertAuthorshipEdgesUsesFallbackLookupForEdgeFactsWhenEnabled() {
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p1", "a1", "SCOPUS"))
                .thenReturn(Optional.empty());

        service.batchUpsertAuthorshipEdges(
                List.of(authorshipCommand("p1", "a1", "SCOPUS", "rec-1")),
                Map.of(),
                true);

        verify(authorshipFactRepository).findByPublicationIdAndAuthorIdAndSource("p1", "a1", "SCOPUS");
    }

    @Test
    void batchUpsertAuthorshipEdgesSkipsFallbackLookupOnCleanBuild() {
        service.batchUpsertAuthorshipEdges(
                List.of(authorshipCommand("p1", "a1", "SCOPUS", "rec-1")),
                Map.of(),
                false);

        verify(authorshipFactRepository, never()).findByPublicationIdAndAuthorIdAndSource(any(), any(), any());
    }

    @Test
    void batchUpsertAuthorshipEdgesOpensConflictWhenExistingIdDiffersFromDeterministic() {
        ScholardexAuthorshipFact existing = new ScholardexAuthorshipFact();
        existing.setId("legacy_id");
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.AUTHORSHIP), eq("SCOPUS"), eq("rec-x"),
                eq(ScholardexEdgeWriterService.REASON_EDGE_CANONICAL_ID_MISMATCH), eq("OPEN")))
                .thenReturn(Optional.empty());
        when(mongoTemplate.bulkOps(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(bulkOps);
        when(bulkOps.updateOne(any(), any())).thenReturn(bulkOps);

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(authorshipCommand("px", "ax", "SCOPUS", "rec-x")),
                Map.of("px|ax|SCOPUS", existing),
                false);

        assertEquals(1, result.conflicts());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void batchUpsertAuthorshipEdgesRejectsBlankCommand() {
        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(authorshipCommand("  ", "a-blank", "SCOPUS", "rec")),
                Map.of(),
                false);
        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        verify(authorshipFactRepository, never()).insert(any(Iterable.class));
    }

    @Test
    void batchUpsertAuthorshipEdgesReturnsZeroWhenCommandsIsNull() {
        ScholardexEdgeWriterService.BatchEdgeWriteResult result =
                service.batchUpsertAuthorshipEdges(null, Map.of(), false);
        assertEquals(0, result.accepted());
        assertEquals(0, result.created());
    }

    @Test
    void batchUpsertAuthorshipEdgesReturnsZeroWhenCommandsIsEmpty() {
        ScholardexEdgeWriterService.BatchEdgeWriteResult result =
                service.batchUpsertAuthorshipEdges(List.of(), Map.of(), false);
        assertEquals(0, result.accepted());
    }

    // ── batch author-affiliation + publication-author-affiliation ──────────

    @Test
    void batchUpsertAuthorAffiliationEdgesPersistsInBulkAndWritesNoSourceLink() {
        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(authorshipCommand("a1", "f1", "SCOPUS", "rec-1")),
                Map.of());

        assertEquals(1, result.accepted());
        verify(authorAffiliationFactRepository).saveAll(any());
        verify(sourceLinkService, never()).batchUpsertWithState(any(), any(), anyBoolean());
    }

    @Test
    void batchUpsertAuthorAffiliationEdgesUpdatesWhenLineageChanged() {
        String deterministicId = service.buildAuthorAffiliationId("a10", "f10", "SCOPUS");
        ScholardexAuthorAffiliationFact existingEdge = new ScholardexAuthorAffiliationFact();
        existingEdge.setId(deterministicId);
        existingEdge.setSourceRecordId("old-rec-aa");
        existingEdge.setCreatedAt(Instant.parse("2021-01-01T00:00:00Z"));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(authorshipCommand("a10", "f10", "SCOPUS", "new-rec-aa")),
                Map.of("a10|f10|SCOPUS", existingEdge),
                false);

        assertEquals(1, result.accepted());
        assertEquals(1, result.updated());
        verify(authorAffiliationFactRepository).saveAll(any());
    }

    @Test
    void batchUpsertPublicationAuthorAffiliationEdgesInsertsNewAndWritesNoSourceLink() {
        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertPublicationAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p1", "a1", "f1", "SCOPUS", "rec-4", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false)),
                Map.of());

        assertEquals(1, result.accepted());
        assertEquals(1, result.created());
        verify(publicationAuthorAffiliationFactRepository).insert(any(java.util.List.class));
        verify(sourceLinkService, never()).batchUpsertWithState(any(), any(), anyBoolean());
    }

    // ── deletion ────────────────────────────────────────────────────────────

    @Test
    void removeAuthorshipEdgeDeletesViaRepositoryAndIsNullSafe() {
        ScholardexAuthorshipFact edge = new ScholardexAuthorshipFact();
        service.removeAuthorshipEdge(edge);
        verify(authorshipFactRepository).delete(edge);
        service.removeAuthorshipEdge(null);
        verify(authorshipFactRepository, times(1)).delete(any());
    }

    @Test
    void removeAuthorAffiliationEdgeDeletesViaRepositoryAndIsNullSafe() {
        ScholardexAuthorAffiliationFact edge = new ScholardexAuthorAffiliationFact();
        service.removeAuthorAffiliationEdge(edge);
        verify(authorAffiliationFactRepository).delete(edge);
        service.removeAuthorAffiliationEdge(null);
        verify(authorAffiliationFactRepository, times(1)).delete(any());
    }
}
