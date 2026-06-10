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
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexEdgeWriterServiceTest {

    @Mock
    private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Mock
    private ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    @Mock
    private ScholardexPublicationAuthorAffiliationFactRepository publicationAuthorAffiliationFactRepository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private BulkOperations bulkOps;

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

    @Test
    void upsertAuthorshipEdgeCreatesDeterministicEdgeAndSourceLink() {
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

    @Test
    void batchUpsertAuthorAffiliationEdgesPropagatesFallbackLookupToSourceLinks() {
        when(sourceLinkService.batchUpsertWithState(any(), any(), eq(true)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a1", "f1", "SCOPUS_JSON_UPLOAD", "rec-2", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED,
                        "bridge",
                        false
                )),
                Map.of(),
                Map.of(),
                true
        );

        assertEquals(1, result.accepted());
        verify(authorAffiliationFactRepository).findByAuthorIdAndAffiliationIdAndSource("a1", "f1", "SCOPUS_JSON_UPLOAD");
        verify(sourceLinkService).batchUpsertWithState(any(), any(), eq(true));
    }

    @Test
    void batchUpsertAuthorshipEdgesPropagatesFallbackLookupToSourceLinks() {
        when(sourceLinkService.batchUpsertWithState(any(), any(), eq(true)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p1", "a1", "SCOPUS_JSON_UPLOAD", "rec-1", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED,
                        "bridge",
                        false
                )),
                Map.of(),
                Map.of(),
                true
        );

        assertEquals(1, result.accepted());
        verify(authorshipFactRepository).findByPublicationIdAndAuthorIdAndSource("p1", "a1", "SCOPUS_JSON_UPLOAD");
        verify(sourceLinkService).batchUpsertWithState(any(), any(), eq(true));
    }

    @Test
    void upsertPublicationAuthorAffiliationEdgeCreatesDeterministicEdgeAndSourceLink() {
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdAndAuthorIdAndAffiliationIdAndSource("p1", "a1", "f1", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(eq(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION), eq("SCOPUS"), eq("rec-3"), anyString(), eq("bridge"), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertPublicationAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p1",
                        "a1",
                        "f1",
                        "SCOPUS",
                        "rec-3",
                        "evt",
                        "b1",
                        "c1",
                        ScholardexSourceLinkService.STATE_LINKED,
                        "bridge",
                        false
                )
        );

        assertTrue(result.accepted());
        assertTrue(result.canonicalEdgeId().startsWith("spaaf_"));
        verify(publicationAuthorAffiliationFactRepository).save(any(ScholardexPublicationAuthorAffiliationFact.class));
    }

    @Test
    void batchUpsertPublicationAuthorAffiliationEdgesPropagatesFallbackLookupToSourceLinks() {
        when(sourceLinkService.batchUpsertWithState(any(), any(), eq(true)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertPublicationAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p1",
                        "a1",
                        "f1",
                        "SCOPUS_JSON_UPLOAD",
                        "rec-4",
                        "evt",
                        "b1",
                        "c1",
                        ScholardexSourceLinkService.STATE_LINKED,
                        "bridge",
                        false
                )),
                Map.of(),
                Map.of(),
                true
        );

        assertEquals(1, result.accepted());
        verify(publicationAuthorAffiliationFactRepository)
                .findByPublicationIdAndAuthorIdAndAffiliationIdAndSource("p1", "a1", "f1", "SCOPUS_JSON_UPLOAD");
        verify(sourceLinkService).batchUpsertWithState(any(), any(), eq(true));
    }

    // -------------------------------------------------------------------------
    // applyLineage — 8 VoidMethodCallMutator survivors (L728-735)
    // -------------------------------------------------------------------------

    @Test
    void applyLineageSetsAllLineageFieldsOnCreatedAuthorshipEdge() {
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p2", "a2", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorshipEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "p2", "a2", "SCOPUS", "rec-lineage", "evt-lineage", "batch-lineage", "corr-lineage",
                ScholardexSourceLinkService.STATE_LINKED, "reason-lineage", false
        ));

        ArgumentCaptor<ScholardexAuthorshipFact> captor = ArgumentCaptor.forClass(ScholardexAuthorshipFact.class);
        verify(authorshipFactRepository).save(captor.capture());
        ScholardexAuthorshipFact saved = captor.getValue();
        // All 7 fields set by applyLineage must survive void-call mutations
        assertEquals("SCOPUS", saved.getSource());
        assertEquals("rec-lineage", saved.getSourceRecordId());
        assertEquals("evt-lineage", saved.getSourceEventId());
        assertEquals("batch-lineage", saved.getSourceBatchId());
        assertEquals("corr-lineage", saved.getSourceCorrelationId());
        assertEquals(ScholardexSourceLinkService.STATE_LINKED, saved.getLinkState());
        assertEquals("reason-lineage", saved.getLinkReason());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void applyLineageSetsAllLineageFieldsOnCreatedAuthorAffiliationEdge() {
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("a3", "f3", "WOS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorAffiliationEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "a3", "f3", "WOS", "rec-aa-lineage", "evt-aa", "batch-aa", "corr-aa",
                ScholardexSourceLinkService.STATE_LINKED, "reason-aa", false
        ));

        ArgumentCaptor<ScholardexAuthorAffiliationFact> captor =
                ArgumentCaptor.forClass(ScholardexAuthorAffiliationFact.class);
        verify(authorAffiliationFactRepository).save(captor.capture());
        ScholardexAuthorAffiliationFact saved = captor.getValue();
        assertEquals("WOS", saved.getSource());
        assertEquals("rec-aa-lineage", saved.getSourceRecordId());
        assertEquals("evt-aa", saved.getSourceEventId());
        assertEquals("batch-aa", saved.getSourceBatchId());
        assertEquals("corr-aa", saved.getSourceCorrelationId());
        assertEquals(ScholardexSourceLinkService.STATE_LINKED, saved.getLinkState());
        assertEquals("reason-aa", saved.getLinkReason());
        assertNotNull(saved.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // openEdgeConflict — 12 VoidMethodCallMutator survivors (L767-784)
    // -------------------------------------------------------------------------

    @Test
    void openEdgeConflictSetsAllFieldsOnSavedConflict() {
        // ID mismatch → openEdgeConflict called; capture and assert every setter field
        ScholardexAuthorAffiliationFact existing = new ScholardexAuthorAffiliationFact();
        existing.setId("legacy-id");
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("a4", "f4", "SCOPUS"))
                .thenReturn(Optional.of(existing));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorAffiliationEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "a4", "f4", "SCOPUS", "rec-conflict", "evt-conflict", "batch-conflict", "corr-conflict",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false
        ));

        ArgumentCaptor<ScholardexIdentityConflict> captor =
                ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(captor.capture());
        ScholardexIdentityConflict conflict = captor.getValue();
        assertEquals(ScholardexEntityType.AUTHOR_AFFILIATION, conflict.getEntityType());
        assertEquals("SCOPUS", conflict.getIncomingSource());
        assertEquals("rec-conflict", conflict.getIncomingSourceRecordId());
        assertEquals(ScholardexEdgeWriterService.REASON_EDGE_CANONICAL_ID_MISMATCH, conflict.getReasonCode());
        assertEquals("OPEN", conflict.getStatus());
        assertFalse(conflict.getCandidateCanonicalIds().isEmpty());
        assertTrue(conflict.getCandidateCanonicalIds().contains("legacy-id"));
        assertEquals("evt-conflict", conflict.getSourceEventId());
        assertEquals("batch-conflict", conflict.getSourceBatchId());
        assertEquals("corr-conflict", conflict.getSourceCorrelationId());
        assertNotNull(conflict.getDetectedAt());
    }

    // -------------------------------------------------------------------------
    // Source-link rejected → second openEdgeConflict (L86-95, L287-296)
    // -------------------------------------------------------------------------

    @Test
    void upsertAuthorshipEdgeOpensRelinkConflictWhenSourceLinkRejected() {
        // Source link returns rejected → openEdgeConflict called a second time
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p3", "a3", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.rejected("relink-rejected"));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorshipEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p3", "a3", "SCOPUS", "rec-relink", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );

        // result is still "accepted" (edge was saved), but a relink conflict was opened
        assertTrue(result.accepted());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void upsertAuthorshipEdgeDoesNotOpenConflictWhenSourceLinkKeptByPrecedence() {
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p3", "a3", "SCOPUS_JSON_BOOTSTRAP"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.rejected("linked-canonical-id-kept-by-precedence"));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorshipEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p3", "a3", "SCOPUS_JSON_BOOTSTRAP", "rec-relink", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );

        assertTrue(result.accepted());
        verify(identityConflictRepository, never()).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void upsertAuthorAffiliationEdgeOpensRelinkConflictWhenSourceLinkRejected() {
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("a5", "f5", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.rejected("relink-rejected"));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a5", "f5", "SCOPUS", "rec-aa-relink", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );

        assertTrue(result.accepted());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void batchUpsertAuthorAffiliationEdgesDoesNotCountPrecedenceKeptSourceLinkAsConflict() {
        ScholardexEdgeWriterService.EdgeWriteCommand command = new ScholardexEdgeWriterService.EdgeWriteCommand(
                "a5", "f5", "SCOPUS_JSON_BOOTSTRAP", "rec-aa-relink", "evt", "b1", "c1",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false
        );
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of(
                        new ScholardexSourceLinkService.SourceLinkBatchItemResult(
                                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                                        ScholardexEntityType.AUTHOR_AFFILIATION,
                                        "SCOPUS_JSON_BOOTSTRAP",
                                        "rec-aa-relink",
                                        "saae_old",
                                        ScholardexSourceLinkService.STATE_LINKED,
                                        "bridge",
                                        "evt",
                                        "b1",
                                        "c1",
                                        false
                                ),
                                false,
                                "linked-canonical-id-kept-by-precedence",
                                null
                        )
                )));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(command),
                Map.of(),
                Map.of(),
                false
        );

        assertEquals(1, result.accepted());
        assertEquals(0, result.rejected());
        assertEquals(0, result.conflicts());
        verify(identityConflictRepository, never()).save(any(ScholardexIdentityConflict.class));
    }

    // -------------------------------------------------------------------------
    // Deterministic id length — kills shortHash MathMutator (L791) and
    // EmptyObjectReturnValsMutator (L795)
    // -------------------------------------------------------------------------

    @Test
    void upsertAuthorshipEdgeProducesDeterministicIdWithExpectedLength() {
        // shortHash returns 24 hex chars; prefix "sae_" = 4 chars → total 28
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("px", "ax", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorshipEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "px", "ax", "SCOPUS", "rec-x", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );

        assertTrue(result.canonicalEdgeId().startsWith("sae_"));
        assertEquals(28, result.canonicalEdgeId().length());
    }

    @Test
    void upsertAuthorAffiliationEdgeProducesDeterministicIdWithExpectedLength() {
        // buildAuthorAffiliationId: prefix "saae_" = 5 chars + 24 hash chars → total 29
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("ay", "fy", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "ay", "fy", "SCOPUS", "rec-y", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );

        assertTrue(result.canonicalEdgeId().startsWith("saae_"));
        assertEquals(29, result.canonicalEdgeId().length());
    }

    // -------------------------------------------------------------------------
    // Input guard — kills L48 / L249 RemoveConditionalMutator survivors
    // -------------------------------------------------------------------------

    @Test
    void upsertAuthorshipEdgeReturnsInvalidWhenLeftIdIsBlank() {
        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorshipEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "", "a1", "SCOPUS", "rec", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );
        assertFalse(result.accepted());
    }

    @Test
    void upsertAuthorAffiliationEdgeReturnsInvalidWhenSourceIsBlank() {
        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a1", "f1", "  ", "rec", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );
        assertFalse(result.accepted());
    }

    // -------------------------------------------------------------------------
    // createdAt preservation — kills L72/L273/L336 RemoveConditionalMutator
    // and L73/L274/L337 VoidMethodCallMutator
    // -------------------------------------------------------------------------

    @Test
    void upsertAuthorshipEdgeSetsCreatedAtOnNewEdge() {
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p2", "a2", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorshipEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "p2", "a2", "SCOPUS", "rec", "evt", "b1", "c1",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false
        ));

        ArgumentCaptor<ScholardexAuthorshipFact> captor = ArgumentCaptor.forClass(ScholardexAuthorshipFact.class);
        verify(authorshipFactRepository).save(captor.capture());
        assertNotNull(captor.getValue().getCreatedAt());
    }

    @Test
    void upsertAuthorshipEdgeDoesNotOverwriteCreatedAtOnExistingEdge() {
        // L72 RemoveConditionalMutator: removal causes setCreatedAt to always run, overwriting originalCreatedAt
        Instant originalCreatedAt = Instant.parse("2020-01-01T00:00:00Z");
        String deterministicId = service.buildAuthorshipId("p3", "a3", "SCOPUS");

        ScholardexAuthorshipFact existing = new ScholardexAuthorshipFact();
        existing.setId(deterministicId);
        existing.setCreatedAt(originalCreatedAt);
        existing.setSourceRecordId("old-rec");

        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p3", "a3", "SCOPUS"))
                .thenReturn(Optional.of(existing));
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorshipEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "p3", "a3", "SCOPUS", "new-rec", "evt", "b1", "c1",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false
        ));

        ArgumentCaptor<ScholardexAuthorshipFact> captor = ArgumentCaptor.forClass(ScholardexAuthorshipFact.class);
        verify(authorshipFactRepository).save(captor.capture());
        assertEquals(originalCreatedAt, captor.getValue().getCreatedAt());
    }

    @Test
    void upsertAuthorAffiliationEdgeSetsCreatedAtAuthorIdAndAffiliationIdOnNewEdge() {
        // L273 RemoveConditionalMutator, L274/276/277 VoidMethodCallMutator
        when(authorAffiliationFactRepository.findByAuthorIdAndAffiliationIdAndSource("a6", "f6", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorAffiliationEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "a6", "f6", "SCOPUS", "rec-aa6", "evt", "b1", "c1",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false
        ));

        ArgumentCaptor<ScholardexAuthorAffiliationFact> captor =
                ArgumentCaptor.forClass(ScholardexAuthorAffiliationFact.class);
        verify(authorAffiliationFactRepository).save(captor.capture());
        ScholardexAuthorAffiliationFact saved = captor.getValue();
        assertNotNull(saved.getCreatedAt());
        assertEquals("a6", saved.getAuthorId());
        assertEquals("f6", saved.getAffiliationId());
    }

    // -------------------------------------------------------------------------
    // upsertPublicationAuthorAffiliationEdge — L302-342 survivors/no-coverage
    // -------------------------------------------------------------------------

    @Test
    void upsertPublicationAuthorAffiliationEdgeReturnsInvalidWhenPublicationIdIsBlank() {
        // L302-303 no-coverage — blank publicationId guard
        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertPublicationAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "", "a1", "f1", "SCOPUS", "rec", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );
        assertFalse(result.accepted());
    }

    @Test
    void upsertPublicationAuthorAffiliationEdgeSetsAllFieldsOnCreatedEdge() {
        // L336-342: VoidMethodCallMutators for setPublicationId, setAuthorId, setAffiliationId, applyLineage
        when(publicationAuthorAffiliationFactRepository
                .findByPublicationIdAndAuthorIdAndAffiliationIdAndSource("p4", "a4", "f4", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertPublicationAuthorAffiliationEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "p4", "a4", "f4", "SCOPUS", "rec-paa", "evt-paa", "b-paa", "c-paa",
                ScholardexSourceLinkService.STATE_LINKED, "bridge-paa", false
        ));

        ArgumentCaptor<ScholardexPublicationAuthorAffiliationFact> captor =
                ArgumentCaptor.forClass(ScholardexPublicationAuthorAffiliationFact.class);
        verify(publicationAuthorAffiliationFactRepository).save(captor.capture());
        ScholardexPublicationAuthorAffiliationFact saved = captor.getValue();
        assertNotNull(saved.getCreatedAt());
        assertEquals("p4", saved.getPublicationId());
        assertEquals("a4", saved.getAuthorId());
        assertEquals("f4", saved.getAffiliationId());
        assertEquals("rec-paa", saved.getSourceRecordId());
        assertEquals(ScholardexSourceLinkService.STATE_LINKED, saved.getLinkState());
        assertEquals("bridge-paa", saved.getLinkReason());
    }

    @Test
    void upsertPublicationAuthorAffiliationEdgeOpensRelinkConflictWhenSourceLinkRejected() {
        // L350-361 no-coverage — source-link rejected path for PAA
        when(publicationAuthorAffiliationFactRepository
                .findByPublicationIdAndAuthorIdAndAffiliationIdAndSource("p5", "a5", "f5", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.rejected("relink-rejected"));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertPublicationAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p5", "a5", "f5", "SCOPUS", "rec-paa5", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );

        assertTrue(result.accepted());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void upsertPublicationAuthorAffiliationEdgeOpensConflictWhenExistingIdDiffersFromDeterministic() {
        ScholardexPublicationAuthorAffiliationFact existing = new ScholardexPublicationAuthorAffiliationFact();
        existing.setId("legacy_paa_id");
        when(publicationAuthorAffiliationFactRepository
                .findByPublicationIdAndAuthorIdAndAffiliationIdAndSource("p6", "a6", "f6", "SCOPUS"))
                .thenReturn(Optional.of(existing));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertPublicationAuthorAffiliationEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p6", "a6", "f6", "SCOPUS", "rec-paa6", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )
        );

        assertTrue(result.accepted());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
        verify(publicationAuthorAffiliationFactRepository).save(any(ScholardexPublicationAuthorAffiliationFact.class));
    }

    // -------------------------------------------------------------------------
    // upsertAuthorshipEdge ID mismatch — L58-59 no-coverage (authorship)
    // -------------------------------------------------------------------------

    @Test
    void upsertAuthorshipEdgeOpensConflictWhenExistingIdDiffersFromDeterministic() {
        // L58-59 no-coverage — existing authorship edge with legacy id
        ScholardexAuthorshipFact existing = new ScholardexAuthorshipFact();
        existing.setId("legacy_authorship_id");

        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p6", "a6", "SCOPUS"))
                .thenReturn(Optional.of(existing));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorshipEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "p6", "a6", "SCOPUS", "rec-6", "evt", "b1", "c1",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false
        ));

        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
        verify(authorshipFactRepository).save(any(ScholardexAuthorshipFact.class));
    }

    // -------------------------------------------------------------------------
    // writeSourceLink CONFLICT / UNMATCHED / SKIPPED branches — L690-724 no-coverage
    // -------------------------------------------------------------------------

    @Test
    void upsertAuthorshipEdgeCallsMarkConflictWhenLinkStateIsConflict() {
        // L703-713 no-coverage — STATE_CONFLICT branch in writeSourceLink
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p7", "a7", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.markConflict(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorshipEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p7", "a7", "SCOPUS", "rec-7", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_CONFLICT, "conflict-reason", false
                )
        );

        assertTrue(result.accepted());
        verify(sourceLinkService).markConflict(eq(ScholardexEntityType.AUTHORSHIP), eq("SCOPUS"), eq("rec-7"),
                eq("conflict-reason"), any(), any(), any(), anyBoolean());
    }

    @Test
    void upsertAuthorshipEdgeCallsMarkSkippedWhenLinkStateIsSkipped() {
        // L715-724 no-coverage — fallthrough SKIPPED branch in writeSourceLink
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p8", "a8", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.markSkipped(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorshipEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p8", "a8", "SCOPUS", "rec-8", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_SKIPPED, "skip-reason", false
                )
        );

        assertTrue(result.accepted());
        verify(sourceLinkService).markSkipped(eq(ScholardexEntityType.AUTHORSHIP), eq("SCOPUS"), eq("rec-8"),
                eq("skip-reason"), any(), any(), any(), anyBoolean());
    }

    // -------------------------------------------------------------------------
    // isLineageChanged + batch update path — L165-174, L739-744 no-coverage
    // -------------------------------------------------------------------------

    @Test
    void batchUpsertAuthorshipEdgesUpdatesExistingEdgeWhenLineageChanged() {
        // Preload an existing edge with a different sourceRecordId → isLineageChanged=true → update path
        String deterministicId = service.buildAuthorshipId("p9", "a9", "SCOPUS");
        ScholardexAuthorshipFact existingEdge = new ScholardexAuthorshipFact();
        existingEdge.setId(deterministicId);
        existingEdge.setSourceRecordId("old-record-id");
        existingEdge.setCreatedAt(Instant.parse("2021-01-01T00:00:00Z"));

        String naturalKey = "p9|a9|SCOPUS";  // edgeNaturalKey("p9","a9","SCOPUS")
        when(mongoTemplate.bulkOps(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(bulkOps);
        when(bulkOps.updateOne(any(), any())).thenReturn(bulkOps);
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p9", "a9", "SCOPUS", "new-record-id", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(naturalKey, existingEdge),
                Map.of(),
                false  // no fallback lookup — use preloaded map only
        );

        assertEquals(1, result.accepted());
        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        verify(mongoTemplate).bulkOps(any(), eq(ScholardexAuthorshipFact.class));
        verify(bulkOps).execute();
    }

    @Test
    void batchUpsertAuthorAffiliationEdgesUpdatesExistingEdgeWhenLineageChanged() {
        // Parallel to authorship batch update — covers AA isLineageChanged path
        String deterministicId = service.buildAuthorAffiliationId("a10", "f10", "SCOPUS");
        ScholardexAuthorAffiliationFact existingEdge = new ScholardexAuthorAffiliationFact();
        existingEdge.setId(deterministicId);
        existingEdge.setSourceRecordId("old-rec-aa");
        existingEdge.setCreatedAt(Instant.parse("2021-01-01T00:00:00Z"));

        String naturalKey = "a10|f10|SCOPUS";
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a10", "f10", "SCOPUS", "new-rec-aa", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(naturalKey, existingEdge),
                Map.of(),
                false
        );

        assertEquals(1, result.accepted());
        // AA batch uses saveAll (not mongo bulk ops), updated path increments updatedCount
        assertEquals(1, result.updated());
        verify(authorAffiliationFactRepository).saveAll(any());
    }

    // -------------------------------------------------------------------------
    // batchUpsertAuthorshipEdges — null guard and blank command rejection (L106, L124)
    // -------------------------------------------------------------------------

    @Test
    void batchUpsertAuthorshipEdgesReturnsZeroWhenCommandsIsNull() {
        // L106: removed conditional on `commands == null` — without guard, NPE on isEmpty()
        ScholardexEdgeWriterService.BatchEdgeWriteResult result =
                service.batchUpsertAuthorshipEdges(null, Map.of(), Map.of(), false);
        assertEquals(0, result.accepted());
        assertEquals(0, result.created());
    }

    @Test
    void batchUpsertAuthorshipEdgesRejectsBlankCommandAndAcceptsNothing() {
        // L124: removed conditional on isBlank check — blank command would be processed
        // L125: rejected++ → rejected-- would give rejected=-1, not 1
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "  ", "a-blank", "SCOPUS", "rec", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(), Map.of(), false
        );

        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        verify(authorshipFactRepository, times(0)).insert(any(Iterable.class));
    }

    // -------------------------------------------------------------------------
    // batchUpsertAuthorshipEdges — create path field assertions (L143-167)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void batchUpsertAuthorshipEdgesCreatesNewEdgeWithAllFieldsSet() {
        // Kills L144,161,163,164,167 VoidMethodCallMutator survivors in batch create path
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "pbatch", "abatch", "SCOPUS", "rec-batch", "evt-batch", "b-batch", "c-batch",
                        ScholardexSourceLinkService.STATE_LINKED, "reason-batch", false
                )),
                Map.of(), Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(1, result.created());

        ArgumentCaptor<Iterable> insertCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(authorshipFactRepository).insert(insertCaptor.capture());
        ScholardexAuthorshipFact created = (ScholardexAuthorshipFact) insertCaptor.getValue().iterator().next();
        assertTrue(created.getId().startsWith("sae_"));
        assertEquals("pbatch", created.getPublicationId());
        assertEquals("abatch", created.getAuthorId());
        assertNotNull(created.getCreatedAt());
        assertEquals("SCOPUS", created.getSource());
        assertEquals("rec-batch", created.getSourceRecordId());
        assertEquals("evt-batch", created.getSourceEventId());
        assertEquals("b-batch", created.getSourceBatchId());
        assertEquals("c-batch", created.getSourceCorrelationId());
        assertEquals(ScholardexSourceLinkService.STATE_LINKED, created.getLinkState());
        assertEquals("reason-batch", created.getLinkReason());
        assertNotNull(created.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // isLineageChanged — isolated field tests (L739 removed conditional, L739 bool return)
    // -------------------------------------------------------------------------

    @Test
    void batchUpsertAuthorshipEdgesDoesNotUpdateWhenLineageUnchanged() {
        // Kills L739 "replaced boolean return with true": always-true makes updated=1 instead of 0
        String deterministicId = service.buildAuthorshipId("p-stable", "a-stable", "SCOPUS");
        ScholardexAuthorshipFact existingEdge = new ScholardexAuthorshipFact();
        existingEdge.setId(deterministicId);
        existingEdge.setCreatedAt(Instant.parse("2022-01-01T00:00:00Z"));
        existingEdge.setSourceRecordId("rec-stable");
        existingEdge.setSourceEventId("evt-stable");
        existingEdge.setSourceBatchId("b-stable");
        existingEdge.setSourceCorrelationId("c-stable");
        existingEdge.setLinkState(ScholardexSourceLinkService.STATE_LINKED);
        existingEdge.setLinkReason("bridge-stable");

        String naturalKey = "p-stable|a-stable|SCOPUS";
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p-stable", "a-stable", "SCOPUS", "rec-stable", "evt-stable", "b-stable", "c-stable",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge-stable", false
                )),
                Map.of(naturalKey, existingEdge),
                Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(0, result.created());
        assertEquals(0, result.updated());
        verify(mongoTemplate, times(0)).bulkOps(any(), eq(ScholardexAuthorshipFact.class));
    }

    @Test
    void batchUpsertAuthorshipEdgesUpdatesWhenOnlySourceRecordIdDiffers() {
        // Kills L739 "removed conditional": when removed, other clauses are false → isLineageChanged=false → updated=0
        String deterministicId = service.buildAuthorshipId("p-srconly", "a-srconly", "SCOPUS");
        ScholardexAuthorshipFact existingEdge = new ScholardexAuthorshipFact();
        existingEdge.setId(deterministicId);
        existingEdge.setCreatedAt(Instant.parse("2022-02-01T00:00:00Z"));
        existingEdge.setSourceRecordId("old-src-only");  // only this differs
        existingEdge.setSourceEventId("evt-same");
        existingEdge.setSourceBatchId("b-same");
        existingEdge.setSourceCorrelationId("c-same");
        existingEdge.setLinkState(ScholardexSourceLinkService.STATE_LINKED);
        existingEdge.setLinkReason("bridge-same");

        String naturalKey = "p-srconly|a-srconly|SCOPUS";
        when(mongoTemplate.bulkOps(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(bulkOps);
        when(bulkOps.updateOne(any(), any())).thenReturn(bulkOps);
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p-srconly", "a-srconly", "SCOPUS", "new-src-only", "evt-same", "b-same", "c-same",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge-same", false
                )),
                Map.of(naturalKey, existingEdge),
                Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        verify(bulkOps).execute();
    }

    // -------------------------------------------------------------------------
    // batchUpsertAuthorAffiliationEdges — create path field assertions (L415-437)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void batchUpsertAuthorAffiliationEdgesCreatesNewEdgeWithAllFieldsSet() {
        // Kills L416,433,435,436,437 VoidMethodCallMutator survivors in AA batch create
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a-aa-new", "f-aa-new", "SCOPUS", "rec-aa-new", "evt-aa-n", "b-aa-n", "c-aa-n",
                        ScholardexSourceLinkService.STATE_LINKED, "reason-aa-n", false
                )),
                Map.of(), Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(1, result.created());

        ArgumentCaptor<Iterable> saveCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(authorAffiliationFactRepository).saveAll(saveCaptor.capture());
        ScholardexAuthorAffiliationFact created =
                (ScholardexAuthorAffiliationFact) saveCaptor.getValue().iterator().next();
        assertTrue(created.getId().startsWith("saae_"));
        assertEquals("a-aa-new", created.getAuthorId());
        assertEquals("f-aa-new", created.getAffiliationId());
        assertNotNull(created.getCreatedAt());
        assertEquals("SCOPUS", created.getSource());
        assertEquals("rec-aa-new", created.getSourceRecordId());
        assertEquals(ScholardexSourceLinkService.STATE_LINKED, created.getLinkState());
        assertEquals("reason-aa-n", created.getLinkReason());
        assertNotNull(created.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // batchUpsertPublicationAuthorAffiliationEdges — create + update paths (L553-607)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void batchUpsertPublicationAuthorAffiliationEdgesCreatesNewEdgeWithAllFieldsSet() {
        // Kills L554,571,573,574,575,578 VoidMethodCallMutator survivors in PAA batch create
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertPublicationAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "ppaa-new", "apaa-new", "fpaa-new", "SCOPUS", "rec-paa-n",
                        "evt-paa-n", "b-paa-n", "c-paa-n",
                        ScholardexSourceLinkService.STATE_LINKED, "reason-paa-n", false
                )),
                Map.of(), Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(1, result.created());

        ArgumentCaptor<Iterable> insertCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(publicationAuthorAffiliationFactRepository).insert(insertCaptor.capture());
        ScholardexPublicationAuthorAffiliationFact created =
                (ScholardexPublicationAuthorAffiliationFact) insertCaptor.getValue().iterator().next();
        assertTrue(created.getId().startsWith("spaaf_"));
        assertEquals("ppaa-new", created.getPublicationId());
        assertEquals("apaa-new", created.getAuthorId());
        assertEquals("fpaa-new", created.getAffiliationId());
        assertNotNull(created.getCreatedAt());
        assertEquals("rec-paa-n", created.getSourceRecordId());
        assertEquals(ScholardexSourceLinkService.STATE_LINKED, created.getLinkState());
        assertEquals("reason-paa-n", created.getLinkReason());
        assertNotNull(created.getUpdatedAt());
    }

    @Test
    void batchUpsertPublicationAuthorAffiliationEdgesUpdatesExistingEdgeWhenLineageChanged() {
        // Covers PAA update path — kills L601,602 counter survivors and L576-578 setters
        String deterministicId = service.buildPublicationAuthorAffiliationId("ppaa2", "apaa2", "fpaa2", "SCOPUS");
        ScholardexPublicationAuthorAffiliationFact existingEdge = new ScholardexPublicationAuthorAffiliationFact();
        existingEdge.setId(deterministicId);
        existingEdge.setSourceRecordId("old-paa-rec");
        existingEdge.setCreatedAt(Instant.parse("2021-06-01T00:00:00Z"));

        String naturalKey = "ppaa2|apaa2|fpaa2|SCOPUS";
        when(mongoTemplate.bulkOps(any(), eq(ScholardexPublicationAuthorAffiliationFact.class))).thenReturn(bulkOps);
        when(bulkOps.updateOne(any(), any())).thenReturn(bulkOps);
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertPublicationAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "ppaa2", "apaa2", "fpaa2", "SCOPUS", "new-paa-rec", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(naturalKey, existingEdge),
                Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        verify(bulkOps).execute();
    }

    // -------------------------------------------------------------------------
    // Source-link batch rejection path (L220, L241, L468, L489, L633, L653)
    // -------------------------------------------------------------------------

    @Test
    void batchUpsertAuthorshipEdgesOpensConflictAndCountsRejectedWhenSourceLinkRejected() {
        // L220: removed comparison check → conflict block skipped → no save
        // L241: subtraction mutation → rejected count wrong
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        // First: create one valid edge so accepted=1
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of(
                        new ScholardexSourceLinkService.SourceLinkBatchItemResult(
                                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                                        ScholardexEntityType.AUTHORSHIP, "SCOPUS", "rec-sl", "edge-sl",
                                        ScholardexSourceLinkService.STATE_LINKED, "bridge",
                                        "evt", "b1", "c1", false
                                ),
                                false, "relink-rejected", null
                        )
                )));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p-sl", "a-sl", "SCOPUS", "rec-sl", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(), Map.of(), false
        );

        assertEquals(1, result.accepted());
        // rejected comes from sourceLinkResults.rejectedCount() = 1
        assertEquals(1, result.rejected());
        // conflicts++ incremented once — L235 Changed-increment mutation killed
        assertEquals(1, result.conflicts());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void batchUpsertAuthorAffiliationEdgesOpensConflictWhenSourceLinkRejected() {
        // L468: removed comparison check → conflict block skipped
        // L489: subtraction mutation → rejected count wrong
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of(
                        new ScholardexSourceLinkService.SourceLinkBatchItemResult(
                                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                                        ScholardexEntityType.AUTHOR_AFFILIATION, "SCOPUS", "rec-sl-aa", "edge-sl-aa",
                                        ScholardexSourceLinkService.STATE_LINKED, "bridge",
                                        "evt", "b1", "c1", false
                                ),
                                false, "relink-rejected", null
                        )
                )));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a-sl", "f-sl", "SCOPUS", "rec-sl-aa", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(), Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(1, result.conflicts());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void batchUpsertPublicationAuthorAffiliationEdgesOpensConflictWhenSourceLinkRejected() {
        // L633: removed comparison check → conflict block skipped
        // L653: subtraction mutation → rejected count wrong
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of(
                        new ScholardexSourceLinkService.SourceLinkBatchItemResult(
                                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                                        ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION, "SCOPUS", "rec-sl-paa", "edge-sl-paa",
                                        ScholardexSourceLinkService.STATE_LINKED, "bridge",
                                        "evt", "b1", "c1", false
                                ),
                                false, "relink-rejected", null
                        )
                )));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertPublicationAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p-sl-paa", "a-sl-paa", "f-sl-paa", "SCOPUS", "rec-sl-paa", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(), Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(1, result.conflicts());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void batchUpsertAuthorshipEdgesOpensConflictWhenExistingIdDiffersFromDeterministic() {
        ScholardexAuthorshipFact existing = new ScholardexAuthorshipFact();
        existing.setId("legacy-auth-batch");
        existing.setSourceRecordId("rec-mm");
        existing.setSourceEventId("evt");
        existing.setSourceBatchId("b1");
        existing.setSourceCorrelationId("c1");
        existing.setLinkState(ScholardexSourceLinkService.STATE_LINKED);
        existing.setLinkReason("bridge");
        String naturalKey = "p-mm|a-mm|SCOPUS";
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p-mm", "a-mm", "SCOPUS", "rec-mm", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(naturalKey, existing), Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(1, result.conflicts());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void batchUpsertAuthorAffiliationEdgesOpensConflictWhenExistingIdDiffersFromDeterministic() {
        ScholardexAuthorAffiliationFact existing = new ScholardexAuthorAffiliationFact();
        existing.setId("legacy-aa-batch");
        String naturalKey = "a-mm|f-mm|SCOPUS";
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "a-mm", "f-mm", "SCOPUS", "rec-mm-aa", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(naturalKey, existing), Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(1, result.conflicts());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void batchUpsertPublicationAuthorAffiliationEdgesOpensConflictWhenExistingIdDiffersFromDeterministic() {
        ScholardexPublicationAuthorAffiliationFact existing = new ScholardexPublicationAuthorAffiliationFact();
        existing.setId("legacy-paa-batch");
        existing.setSourceRecordId("rec-mm-paa");
        existing.setSourceEventId("evt");
        existing.setSourceBatchId("b1");
        existing.setSourceCorrelationId("c1");
        existing.setLinkState(ScholardexSourceLinkService.STATE_LINKED);
        existing.setLinkReason("bridge");
        String naturalKey = "p-mm|a-mm|f-mm|SCOPUS";
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertPublicationAuthorAffiliationEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p-mm", "a-mm", "f-mm", "SCOPUS", "rec-mm-paa", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_LINKED, "bridge", false
                )),
                Map.of(naturalKey, existing), Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(1, result.conflicts());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    // -------------------------------------------------------------------------
    // openEdgeConflict null/blank sourceRecordId guard (L759) and normalize (L802/L806)
    // -------------------------------------------------------------------------

    @Test
    void openEdgeConflictSkipsConflictSaveWhenSourceRecordIdIsNull() {
        // L759: removed guard → save IS called instead of returning early → kills mutation
        // L802: null check removed in normalize → NPE on null.trim() → kills mutation
        ScholardexAuthorshipFact existing = new ScholardexAuthorshipFact();
        existing.setId("legacy-null-src-rec");
        existing.setCreatedAt(Instant.now());
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p-nr", "a-nr", "SCOPUS"))
                .thenReturn(Optional.of(existing));
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorshipEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "p-nr", "a-nr", "SCOPUS", null, "evt", "b1", "c1",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false
        ));

        // normalize(null) = null → early return in openEdgeConflict → no conflict saved
        verify(identityConflictRepository, times(0)).save(any());
    }

    // -------------------------------------------------------------------------
    // writeSourceLink STATE_UNMATCHED branch (L690) — kills removed-conditional survivor
    // -------------------------------------------------------------------------

    @Test
    void upsertAuthorshipEdgeCallsMarkUnmatchedWhenLinkStateIsUnmatched() {
        // L690: removed conditional replaces STATE_UNMATCHED check with false → falls to markSkipped
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p-um", "a-um", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.markUnmatched(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        ScholardexEdgeWriterService.EdgeWriteResult result = service.upsertAuthorshipEdge(
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p-um", "a-um", "SCOPUS", "rec-um", "evt", "b1", "c1",
                        ScholardexSourceLinkService.STATE_UNMATCHED, "unmatched-reason", false
                )
        );

        assertTrue(result.accepted());
        verify(sourceLinkService).markUnmatched(eq(ScholardexEntityType.AUTHORSHIP), eq("SCOPUS"), eq("rec-um"),
                anyString(), eq("unmatched-reason"), any(), any(), any(), anyBoolean());
    }

    // -------------------------------------------------------------------------
    // Empty-list guards (L106, L379, L510) — kills replaced-isEmpty-check survivors
    // -------------------------------------------------------------------------

    @Test
    void batchUpsertAuthorshipEdgesReturnsZeroWhenCommandsIsEmpty() {
        // L106 isEmpty() removed: empty list passes guard, reaches batchUpsertWithState (unmocked → NPE → killed)
        ScholardexEdgeWriterService.BatchEdgeWriteResult result =
                service.batchUpsertAuthorshipEdges(List.of(), Map.of(), Map.of(), false);
        assertEquals(0, result.accepted());
        assertEquals(0, result.created());
        verify(sourceLinkService, times(0)).batchUpsertWithState(any(), any(), anyBoolean());
    }

    @Test
    void batchUpsertAuthorAffiliationEdgesReturnsZeroWhenCommandsIsEmpty() {
        // L379 isEmpty() removed: empty list reaches batchUpsertWithState (unmocked → NPE → killed)
        ScholardexEdgeWriterService.BatchEdgeWriteResult result =
                service.batchUpsertAuthorAffiliationEdges(List.of(), Map.of(), Map.of(), false);
        assertEquals(0, result.accepted());
        verify(sourceLinkService, times(0)).batchUpsertWithState(any(), any(), anyBoolean());
    }

    @Test
    void batchUpsertPublicationAuthorAffiliationEdgesReturnsZeroWhenCommandsIsEmpty() {
        // L510 isEmpty() removed: empty list reaches batchUpsertWithState (unmocked → NPE → killed)
        ScholardexEdgeWriterService.BatchEdgeWriteResult result =
                service.batchUpsertPublicationAuthorAffiliationEdges(List.of(), Map.of(), Map.of(), false);
        assertEquals(0, result.accepted());
        verify(sourceLinkService, times(0)).batchUpsertWithState(any(), any(), anyBoolean());
    }

    // -------------------------------------------------------------------------
    // isLineageChanged L744 — only linkReason differs (kills removed-conditional on last clause)
    // -------------------------------------------------------------------------

    @Test
    void batchUpsertAuthorshipEdgesUpdatesWhenOnlyLinkReasonDiffers() {
        // L744: removed conditional replaces !Objects.equals(linkReason,...) with false;
        // when all other 5 clauses are also false → isLineageChanged=false → updated=0 (kills mutation)
        String deterministicId = service.buildAuthorshipId("p-lr", "a-lr", "SCOPUS");
        ScholardexAuthorshipFact existingEdge = new ScholardexAuthorshipFact();
        existingEdge.setId(deterministicId);
        existingEdge.setCreatedAt(Instant.parse("2022-03-01T00:00:00Z"));
        existingEdge.setSourceRecordId("rec-lr");
        existingEdge.setSourceEventId("evt-lr");
        existingEdge.setSourceBatchId("b-lr");
        existingEdge.setSourceCorrelationId("c-lr");
        existingEdge.setLinkState(ScholardexSourceLinkService.STATE_LINKED);
        existingEdge.setLinkReason("old-reason");  // only this differs

        String naturalKey = "p-lr|a-lr|SCOPUS";
        when(mongoTemplate.bulkOps(any(), eq(ScholardexAuthorshipFact.class))).thenReturn(bulkOps);
        when(bulkOps.updateOne(any(), any())).thenReturn(bulkOps);
        when(sourceLinkService.batchUpsertWithState(any(), any(), anyBoolean()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ScholardexEdgeWriterService.BatchEdgeWriteResult result = service.batchUpsertAuthorshipEdges(
                List.of(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "p-lr", "a-lr", "SCOPUS", "rec-lr", "evt-lr", "b-lr", "c-lr",
                        ScholardexSourceLinkService.STATE_LINKED, "new-reason", false  // linkReason changed
                )),
                Map.of(naturalKey, existingEdge),
                Map.of(), false
        );

        assertEquals(1, result.accepted());
        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        verify(bulkOps).execute();
    }

    @Test
    void openEdgeConflictSkipsConflictSaveWhenSourceRecordIdIsWhitespace() {
        // L806: trimmed.isEmpty() check removed → normalize("  ") returns "" instead of null
        // → guard at L759 passes → save IS called → verify(times(0)) fails → mutation killed
        ScholardexAuthorshipFact existing = new ScholardexAuthorshipFact();
        existing.setId("legacy-ws-src-rec");
        existing.setCreatedAt(Instant.now());
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("p-ws", "a-ws", "SCOPUS"))
                .thenReturn(Optional.of(existing));
        when(sourceLinkService.link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ScholardexSourceLinkService.SourceLinkWriteResult.accepted(new ScholardexSourceLink()));

        service.upsertAuthorshipEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                "p-ws", "a-ws", "SCOPUS", "   ", "evt", "b1", "c1",
                ScholardexSourceLinkService.STATE_LINKED, "bridge", false
        ));

        // normalize("   ") = null → early return in openEdgeConflict → no conflict saved
        verify(identityConflictRepository, times(0)).save(any());
    }

    // -------------------------------------------------------------------------
    // removeAuthorshipEdge / removeAuthorAffiliationEdge — sanctioned deletion surface (H54.5b)
    // -------------------------------------------------------------------------

    @Test
    void removeAuthorshipEdgeDeletesViaRepositoryAndIsNullSafe() {
        ScholardexAuthorshipFact edge = new ScholardexAuthorshipFact();
        edge.setId("sae_1");

        service.removeAuthorshipEdge(edge);
        verify(authorshipFactRepository).delete(edge);

        service.removeAuthorshipEdge(null);
        verify(authorshipFactRepository, times(1)).delete(any());
    }

    @Test
    void removeAuthorAffiliationEdgeDeletesViaRepositoryAndIsNullSafe() {
        ScholardexAuthorAffiliationFact edge = new ScholardexAuthorAffiliationFact();
        edge.setId("saae_1");

        service.removeAuthorAffiliationEdge(edge);
        verify(authorAffiliationFactRepository).delete(edge);

        service.removeAuthorAffiliationEdge(null);
        verify(authorAffiliationFactRepository, times(1)).delete(any());
    }
}
