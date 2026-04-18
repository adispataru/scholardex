package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexPublicationCanonicalizationServiceTest {

    @Mock
    private ScopusPublicationFactRepository scopusPublicationFactRepository;
    @Mock
    private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock
    private ScholardexAuthorshipFactRepository scholardexAuthorshipFactRepository;
    @Mock
    private ScholardexPublicationAuthorAffiliationFactRepository scholardexPublicationAuthorAffiliationFactRepository;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
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
                scholardexAuthorshipFactRepository,
                scholardexPublicationAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
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

        when(scopusPublicationFactRepository.count()).thenReturn(1L);
        when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));

        ImportProcessingResult result = service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        verify(scholardexPublicationFactRepository).insert(anyList());
        verify(sourceLinkService, atLeastOnce()).batchUpsertWithState(any(), any(), eq(false));
        verify(edgeWriterService, atLeastOnce()).batchUpsertAuthorshipEdges(any(), any(), any(), eq(true));
        verify(sourceLinkService, atLeastOnce()).findByKey(
                eq(ScholardexEntityType.AUTHOR), eq("SCOPUS_JSON_BOOTSTRAP"), eq("au-1"));
    }

    private CanonicalBuildOptions fullRescanOptions() {
        return new CanonicalBuildOptions(null, null, true, null, null, false, false);
    }

    @Test
    void bridgeAuthorIdsReturnsDeterministicFallbackAndPendingMarkerWhenNoCanonicalLinkExists() {
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");

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
    void bridgeAuthorIdsUsesStableFallbackIdsAcrossScopusVariants() {
        when(sourceLinkService.findByKey(any(), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.normalizeSource("SCOPUS_PYTHON_AUTHOR_WORKS")).thenReturn("SCOPUS");

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bootstrap = service.bridgeAuthorIds(
                List.of("au-1"),
                "SCOPUS_JSON_BOOTSTRAP"
        );
        ScholardexPublicationCanonicalizationService.AuthorBridgeResult python = service.bridgeAuthorIds(
                List.of("au-1"),
                "SCOPUS_PYTHON_AUTHOR_WORKS"
        );

        assertEquals(bootstrap.canonicalAuthorIds(), python.canonicalAuthorIds());
        assertEquals(bootstrap.pendingSourceIds(), python.pendingSourceIds());
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
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(scopusFact, result);

        assertEquals(1, result.getUpdatedCount());
        verify(scholardexPublicationFactRepository).saveAll(any());
    }

    @Test
    void upsertFromScopusFactEmitsPublicationAuthorAffiliationEdgesWhenMappingsResolve() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-edge");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-edge");
        scopusFact.setAuthors(List.of("au-1"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("af1"));

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");
        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setCanonicalEntityId("saff_1");

        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AUTHOR), eq("SCOPUS"), eq("au-1")))
                .thenReturn(Optional.of(authorLink));
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("af1")))
                .thenReturn(Optional.of(affiliationLink));
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));
        when(edgeWriterService.batchUpsertPublicationAuthorAffiliationEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));

        service.upsertFromScopusFact(scopusFact, new ImportProcessingResult(10));

        verify(edgeWriterService).batchUpsertPublicationAuthorAffiliationEdges(any(), any(), any(), eq(true));
    }

    @Test
    void upsertFromScopusFactCanonicalizesPublicationAffiliationIdsAndDropsUnresolvedSourceIds() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-aff");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-aff");
        scopusFact.setAuthors(List.of("au-1"));
        scopusFact.setAffiliations(List.of("60000434", "missing", "60000434"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("af2-60000434"));

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");
        ScholardexSourceLink uvtAffiliationLink = new ScholardexSourceLink();
        uvtAffiliationLink.setCanonicalEntityId("saff_uvt");
        ScholardexSourceLink partnerAffiliationLink = new ScholardexSourceLink();
        partnerAffiliationLink.setCanonicalEntityId("saff_partner");

        when(scholardexPublicationFactRepository.findByEid("2-s2.0-aff")).thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AUTHOR), eq("SCOPUS"), eq("au-1")))
                .thenReturn(Optional.of(authorLink));
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("60000434")))
                .thenReturn(Optional.of(uvtAffiliationLink));
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("af2")))
                .thenReturn(Optional.of(partnerAffiliationLink));
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("missing")))
                .thenReturn(Optional.empty());
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));
        when(edgeWriterService.batchUpsertPublicationAuthorAffiliationEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(2, 0, 2, 0, 0));

        service.upsertFromScopusFact(scopusFact, new ImportProcessingResult(10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ScholardexPublicationFact>> insertedFactsCaptor =
                ArgumentCaptor.forClass((Class<Iterable<ScholardexPublicationFact>>) (Class<?>) Iterable.class);

        verify(scholardexPublicationFactRepository).insert(insertedFactsCaptor.capture());

        List<ScholardexPublicationFact> insertedFacts = new java.util.ArrayList<>();
        insertedFactsCaptor.getValue().forEach(insertedFacts::add);
        assertEquals(List.of("saff_uvt", "saff_partner"), insertedFacts.getFirst().getAffiliationIds());
    }

    @Test
    void rebuildCanonicalPublicationFactsPreloadsPersistedAuthorshipEdgesForReplay() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-replay");
        scopusFact.setTitle("Replay Title");
        scopusFact.setSource("SCOPUS_JSON_UPLOAD");
        scopusFact.setSourceRecordId("2-s2.0-replay");
        scopusFact.setAuthors(List.of("au-1"));

        ScholardexPublicationFact existingPublication = new ScholardexPublicationFact();
        existingPublication.setId("spub_replay");
        existingPublication.setEid("2-s2.0-replay");

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");

        ScholardexAuthorshipFact persistedEdge = new ScholardexAuthorshipFact();
        persistedEdge.setId("sae_existing");
        persistedEdge.setPublicationId("spub_replay");
        persistedEdge.setAuthorId("sauth_1");
        persistedEdge.setSource("SCOPUS_JSON_UPLOAD");

        lenient().when(scopusPublicationFactRepository.count()).thenReturn(1L);
        lenient().when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        lenient().when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(existingPublication));
        lenient().when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        lenient().when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of(persistedEdge));
        lenient().when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        lenient().when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        authorLink.setEntityType(ScholardexEntityType.AUTHOR);
        authorLink.setSource("SCOPUS_JSON_UPLOAD");
        authorLink.setSourceRecordId("au-1");
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), any()))
                .thenReturn(List.of(authorLink));
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), any()))
                .thenReturn(List.of());
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of());
        lenient().when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 0, 1, 0));

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        verify(edgeWriterService).batchUpsertAuthorshipEdges(
                any(),
                argThat(map -> map instanceof Map<?, ?> typedMap
                        && typedMap.containsKey("spub_replay|sauth_1|SCOPUS_JSON_UPLOAD")
                        && typedMap.get("spub_replay|sauth_1|SCOPUS_JSON_UPLOAD") == persistedEdge),
                any(),
                eq(true)
        );
    }

    @Test
    void rebuildCanonicalPublicationFactsPreloadsPersistedPublicationAuthorAffiliationEdgesForReplay() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-replay-paf");
        scopusFact.setTitle("Replay Title");
        scopusFact.setSource("SCOPUS_JSON_UPLOAD");
        scopusFact.setSourceRecordId("2-s2.0-replay-paf");
        scopusFact.setAuthors(List.of("au-1"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("af1"));

        ScholardexPublicationFact existingPublication = new ScholardexPublicationFact();
        existingPublication.setId("spub_replay");
        existingPublication.setEid("2-s2.0-replay-paf");

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setEntityType(ScholardexEntityType.AUTHOR);
        authorLink.setSource("SCOPUS_JSON_UPLOAD");
        authorLink.setSourceRecordId("au-1");
        authorLink.setCanonicalEntityId("sauth_1");

        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setEntityType(ScholardexEntityType.AFFILIATION);
        affiliationLink.setSource("SCOPUS_JSON_UPLOAD");
        affiliationLink.setSourceRecordId("af1");
        affiliationLink.setCanonicalEntityId("saff_1");

        ScholardexPublicationAuthorAffiliationFact persistedEdge = new ScholardexPublicationAuthorAffiliationFact();
        persistedEdge.setId("spaaf_existing");
        persistedEdge.setPublicationId("spub_replay");
        persistedEdge.setAuthorId("sauth_1");
        persistedEdge.setAffiliationId("saff_1");
        persistedEdge.setSource("SCOPUS_JSON_UPLOAD");

        lenient().when(scopusPublicationFactRepository.count()).thenReturn(1L);
        lenient().when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        lenient().when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(existingPublication));
        lenient().when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        lenient().when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        lenient().when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of(persistedEdge));
        lenient().when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), any()))
                .thenReturn(List.of(authorLink));
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), any()))
                .thenReturn(List.of(affiliationLink));
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of());
        lenient().when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 0, 1, 0));
        lenient().when(edgeWriterService.batchUpsertPublicationAuthorAffiliationEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 0, 1, 0));

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        verify(edgeWriterService).batchUpsertPublicationAuthorAffiliationEdges(
                any(),
                argThat(map -> map instanceof Map<?, ?> typedMap
                        && typedMap.containsKey("spub_replay|sauth_1|saff_1|SCOPUS_JSON_UPLOAD")
                        && typedMap.get("spub_replay|sauth_1|saff_1|SCOPUS_JSON_UPLOAD") == persistedEdge),
                any(),
                eq(true)
        );
    }

    @Test
    void replayReusesExistingOpenPublicationAuthorAffiliationConflict() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-conflict");
        scopusFact.setTitle("Replay Title");
        scopusFact.setSource("SCOPUS_JSON_UPLOAD");
        scopusFact.setSourceRecordId("2-s2.0-105000527065");
        scopusFact.setAuthors(List.of("36057720300"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("60024417"));

        ScholardexPublicationFact existingPublication = new ScholardexPublicationFact();
        existingPublication.setId("spub_conflict");
        existingPublication.setEid("2-s2.0-conflict");

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setEntityType(ScholardexEntityType.AUTHOR);
        authorLink.setSource("SCOPUS_JSON_UPLOAD");
        authorLink.setSourceRecordId("36057720300");
        authorLink.setCanonicalEntityId("sauth_1");

        ScholardexIdentityConflict existingConflict = new ScholardexIdentityConflict();
        existingConflict.setId("sic_existing");
        existingConflict.setEntityType(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION);
        existingConflict.setIncomingSource("SCOPUS_JSON_UPLOAD");
        existingConflict.setIncomingSourceRecordId("2-s2.0-105000527065::author::36057720300::affiliation::60024417");
        existingConflict.setReasonCode("PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED");
        existingConflict.setStatus("OPEN");

        lenient().when(scopusPublicationFactRepository.count()).thenReturn(1L);
        lenient().when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        lenient().when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(existingPublication));
        lenient().when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        lenient().when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        lenient().when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        lenient().when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), any()))
                .thenReturn(List.of(authorLink));
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), any()))
                .thenReturn(List.of());
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of());
        lenient().when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 0, 1, 0));
        lenient().when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION),
                eq("SCOPUS_JSON_UPLOAD"),
                eq("2-s2.0-105000527065::author::36057720300::affiliation::60024417"),
                eq("PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED"),
                eq("OPEN")
        )).thenReturn(Optional.of(existingConflict));

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        verify(identityConflictRepository).saveAll(argThat(conflicts -> {
            for (Object candidate : conflicts) {
                if (candidate == existingConflict) {
                    return true;
                }
            }
            return false;
        }));
    }
}
