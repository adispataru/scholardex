package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash;

@ExtendWith(MockitoExtension.class)
class ScholardexCitationCanonicalizationServiceTest {

    @Mock
    private ScopusCitationFactRepository scopusCitationFactRepository;
    @Mock
    private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock
    private ScholardexCitationFactRepository scholardexCitationFactRepository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    @Mock
    private ScholardexCanonicalBuildCheckpointService checkpointService;

    @Test
    void exposesCitationPipelineContract() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );
        ReflectionTestUtils.setField(service, "heartbeatSeconds", 31L);
        int defaultChunkSize = ReflectionTestUtils.invokeMethod(service, "getDefaultChunkSize");
        long heartbeatSeconds = ReflectionTestUtils.invokeMethod(service, "getHeartbeatSeconds");

        assertEquals(
                ScholardexCanonicalBuildCheckpointService.CITATION_PIPELINE_KEY,
                ReflectionTestUtils.invokeMethod(service, "getPipelineKey")
        );
        assertEquals("citation", ReflectionTestUtils.invokeMethod(service, "getEntityTypeLabel"));
        assertEquals(ScholardexEntityType.CITATION, ReflectionTestUtils.invokeMethod(service, "getEntityType"));
        assertEquals(1_000, defaultChunkSize);
        assertEquals("scopus-citation-facts-v1", ReflectionTestUtils.invokeMethod(service, "getDefaultSourceVersion"));
        assertEquals(31L, heartbeatSeconds);
    }

    @Test
    void rebuildCanonicalCitationFactsCreatesCanonicalEdgeAndSourceLink() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );

        ScholardexPublicationFact cited = new ScholardexPublicationFact();
        cited.setId("spub_1");
        cited.setEid("2-s2.0-cited");
        ScholardexPublicationFact citing = new ScholardexPublicationFact();
        citing.setId("spub_2");
        citing.setEid("2-s2.0-citing");
        when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(cited, citing));
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());

        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceRecordId("2-s2.0-cited->2-s2.0-citing");
        sourceFact.setCitedEid("2-s2.0-cited");
        sourceFact.setCitingEid("2-s2.0-citing");
        sourceFact.setSourceEventId("evt-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");
        when(scopusCitationFactRepository.count()).thenReturn(1L);
        when(scopusCitationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(sourceFact)));
        when(sourceLinkService.batchUpsertWithState(any(), any(), eq(false)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        var result = service.rebuildCanonicalCitationFactsFromScopusFacts(fullRescanOptions());

        assertEquals(1, result.getImportedCount());
        assertEquals(0, result.getSkippedCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ScholardexCitationFact>> edgeCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(scholardexCitationFactRepository).saveAll(edgeCaptor.capture());
        ScholardexCitationFact saved = edgeCaptor.getValue().iterator().next();
        assertEquals("spub_1", saved.getCitedPublicationId());
        assertEquals("spub_2", saved.getCitingPublicationId());
        assertEquals("SCOPUS_JSON_BOOTSTRAP", saved.getSource());
        assertEquals("2-s2.0-cited->2-s2.0-citing", saved.getSourceRecordId());
        assertEquals("evt-1", saved.getSourceEventId());
        assertEquals("batch-1", saved.getSourceBatchId());
        assertEquals("corr-1", saved.getSourceCorrelationId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        verify(sourceLinkService).batchUpsertWithState(any(), any(), eq(false));
    }

    @Test
    void rebuildCanonicalCitationFactsQuarantinesUnresolvedCitedPublication() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );

        when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of());
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceRecordId("2-s2.0-missing->2-s2.0-citing");
        sourceFact.setCitedEid("2-s2.0-missing");
        sourceFact.setCitingEid("2-s2.0-citing");
        when(scopusCitationFactRepository.count()).thenReturn(1L);
        when(scopusCitationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(sourceFact)));
        var result = service.rebuildCanonicalCitationFactsFromScopusFacts(fullRescanOptions());

        assertEquals(1, result.getSkippedCount());
        verify(scholardexCitationFactRepository, never()).save(any());
        verify(scholardexCitationFactRepository, never()).saveAll(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ScholardexIdentityConflict>> conflictCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(scholardexIdentityConflictRepository).saveAll(conflictCaptor.capture());
        ScholardexIdentityConflict conflict = conflictCaptor.getValue().iterator().next();
        assertEquals(ScholardexEntityType.CITATION, conflict.getEntityType());
        assertEquals("SCOPUS_JSON_BOOTSTRAP", conflict.getIncomingSource());
        assertEquals("2-s2.0-missing->2-s2.0-citing", conflict.getIncomingSourceRecordId());
        assertEquals("UNRESOLVED_CITED_PUBLICATION", conflict.getReasonCode());
        assertEquals("OPEN", conflict.getStatus());
    }

    @Test
    void replayReusesExistingOpenCitationConflict() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );

        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceRecordId("2-s2.0-85208291558->2-s2.0-85213561823");
        sourceFact.setCitedEid("2-s2.0-85208291558");
        sourceFact.setCitingEid("2-s2.0-85213561823");
        sourceFact.setSourceEventId("evt-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");

        ScholardexIdentityConflict existingConflict = new ScholardexIdentityConflict();
        existingConflict.setId("sic_existing");
        existingConflict.setEntityType(ScholardexEntityType.CITATION);
        existingConflict.setIncomingSource("SCOPUS_JSON_BOOTSTRAP");
        existingConflict.setIncomingSourceRecordId("2-s2.0-85208291558->2-s2.0-85213561823");
        existingConflict.setReasonCode("UNRESOLVED_CITED_PUBLICATION");
        existingConflict.setStatus("OPEN");

        lenient().when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of());
        lenient().when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        lenient().when(scopusCitationFactRepository.count()).thenReturn(1L);
        lenient().when(scopusCitationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(sourceFact)));
        lenient().when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.CITATION),
                eq("SCOPUS_JSON_BOOTSTRAP"),
                eq("2-s2.0-85208291558->2-s2.0-85213561823"),
                eq("UNRESOLVED_CITED_PUBLICATION"),
                eq("OPEN")
        )).thenReturn(Optional.of(existingConflict));

        service.rebuildCanonicalCitationFactsFromScopusFacts(fullRescanOptions());

        verify(scholardexIdentityConflictRepository).saveAll(argThat(conflicts -> {
            for (Object candidate : conflicts) {
                if (candidate == existingConflict) {
                    return true;
                }
            }
            return false;
        }));
    }

    @Test
    void secondSourceForKnownCitationPairReusesCanonicalEdgeAndLinksNewSourceRecord() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );

        ScholardexPublicationFact cited = new ScholardexPublicationFact();
        cited.setId("spub_1");
        cited.setEid("2-s2.0-cited");
        ScholardexPublicationFact citing = new ScholardexPublicationFact();
        citing.setId("spub_2");
        citing.setEid("2-s2.0-citing");
        when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(cited, citing));
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());

        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS_PYTHON_CITATIONS_EDGE");
        sourceFact.setSourceRecordId("2-s2.0-cited->2-s2.0-citing");
        sourceFact.setCitedEid("2-s2.0-cited");
        sourceFact.setCitingEid("2-s2.0-citing");
        sourceFact.setSourceEventId("evt-2");
        sourceFact.setSourceBatchId("batch-2");
        sourceFact.setSourceCorrelationId("corr-2");

        ScholardexCitationFact existingCanonicalFact = new ScholardexCitationFact();
        String canonicalCitationId = "scit_" + shortHash("spub_1|spub_2");
        existingCanonicalFact.setId(canonicalCitationId);
        existingCanonicalFact.setCitedPublicationId("spub_1");
        existingCanonicalFact.setCitingPublicationId("spub_2");
        existingCanonicalFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        existingCanonicalFact.setSourceRecordId("2-s2.0-cited->2-s2.0-citing");

        when(scopusCitationFactRepository.count()).thenReturn(1L);
        when(scopusCitationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(sourceFact)));
        when(scholardexCitationFactRepository.findAllById(argThat(ids ->
                ids.iterator().hasNext() && canonicalCitationId.equals(ids.iterator().next())
        ))).thenReturn(List.of(existingCanonicalFact));
        when(sourceLinkService.batchUpsertWithState(any(), any(), eq(false)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        var result = service.rebuildCanonicalCitationFactsFromScopusFacts(fullRescanOptions());

        assertEquals(0, result.getImportedCount());
        assertEquals(1, result.getSkippedCount());
        verify(scholardexCitationFactRepository, never()).saveAll(any());
        verify(sourceLinkService, atLeastOnce()).batchUpsertWithState(argThat(commands ->
                commands.stream().anyMatch(command ->
                                "SCOPUS_PYTHON_CITATIONS_EDGE".equals(command.source())
                                        && "2-s2.0-cited->2-s2.0-citing".equals(command.sourceRecordId())
                                && canonicalCitationId.equals(command.canonicalEntityId())
                )
        ), any(), eq(false));
    }

    @Test
    void sortSourceFactsOrdersByCitedEidThenCitingEid() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );

        ScholardexPublicationFact pubAaa = new ScholardexPublicationFact();
        pubAaa.setId("spub_aaa");
        pubAaa.setEid("2-s2.0-aaa");
        ScholardexPublicationFact pubZzz = new ScholardexPublicationFact();
        pubZzz.setId("spub_zzz");
        pubZzz.setEid("2-s2.0-zzz");
        ScholardexPublicationFact pubCiting = new ScholardexPublicationFact();
        pubCiting.setId("spub_citing");
        pubCiting.setEid("2-s2.0-citing");
        when(scholardexPublicationFactRepository.findAllByEidIn(any()))
                .thenReturn(List.of(pubAaa, pubZzz, pubCiting));
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        when(sourceLinkService.batchUpsertWithState(any(), any(), eq(false)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        // zzz comes first in the returned page — sort should reorder to aaa first
        ScopusCitationFact factZzz = new ScopusCitationFact();
        factZzz.setSource("SCOPUS_JSON_BOOTSTRAP");
        factZzz.setSourceRecordId("2-s2.0-zzz->2-s2.0-citing");
        factZzz.setCitedEid("2-s2.0-zzz");
        factZzz.setCitingEid("2-s2.0-citing");
        ScopusCitationFact factAaa = new ScopusCitationFact();
        factAaa.setSource("SCOPUS_JSON_BOOTSTRAP");
        factAaa.setSourceRecordId("2-s2.0-aaa->2-s2.0-citing");
        factAaa.setCitedEid("2-s2.0-aaa");
        factAaa.setCitingEid("2-s2.0-citing");
        when(scopusCitationFactRepository.count()).thenReturn(2L);
        when(scopusCitationFactRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(factZzz, factAaa)));

        service.rebuildCanonicalCitationFactsFromScopusFacts(fullRescanOptions());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ScholardexCitationFact>> saveCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(scholardexCitationFactRepository).saveAll(saveCaptor.capture());
        java.util.Iterator<ScholardexCitationFact> it = saveCaptor.getValue().iterator();
        assertEquals("spub_aaa", it.next().getCitedPublicationId());
        assertEquals("spub_zzz", it.next().getCitedPublicationId());
    }

    @Test
    void rebuildCanonicalCitationFactsFromScopusFactsDefaultWrapperReturnsEmptyResult() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );

        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        when(scopusCitationFactRepository.count()).thenReturn(0L);
        when(scopusCitationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        var result = service.rebuildCanonicalCitationFactsFromScopusFacts();

        assertEquals(0, result.getProcessedCount());
        assertEquals(0, result.getImportedCount());
    }

    @Test
    void loadSourceFactsUsesBatchFilterWhenSourceBatchIdProvided() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );
        ScopusCitationFact batchFact = new ScopusCitationFact();
        batchFact.setSourceBatchId("batch-1");
        when(scopusCitationFactRepository.findBySourceBatchId("batch-1")).thenReturn(List.of(batchFact));

        List<ScopusCitationFact> loaded = ReflectionTestUtils.invokeMethod(
                service,
                "loadSourceFacts",
                new CanonicalBuildOptions(null, null, true, "batch-1", null, false, false)
        );

        assertEquals(List.of(batchFact), loaded);
        verify(scopusCitationFactRepository, never()).count();
        verify(scopusCitationFactRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void loadSourceFactsReturnsEmptyWhenFirstFullRescanPageIsEmpty() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );
        when(scopusCitationFactRepository.count()).thenReturn(0L);
        when(scopusCitationFactRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1_000), 0));

        List<ScopusCitationFact> loaded =
                ReflectionTestUtils.invokeMethod(service, "loadSourceFacts", fullRescanOptions());

        assertTrue(loaded.isEmpty());
    }

    @Test
    void preloadChunkContextIndexesFallbackSourceIdsExistingEdgeAndSourceLink() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );

        ScopusCitationFact explicit = new ScopusCitationFact();
        explicit.setSource("SCOPUS_JSON_UPLOAD");
        explicit.setSourceRecordId("explicit");
        explicit.setCitedEid("2-s2.0-cited");
        explicit.setCitingEid("2-s2.0-citing");
        ScopusCitationFact fallback = new ScopusCitationFact();
        fallback.setSource("SCOPUS_JSON_UPLOAD");
        fallback.setCitedEid("2-s2.0-cited");
        fallback.setCitingEid("2-s2.0-citing");

        ScholardexPublicationFact cited = new ScholardexPublicationFact();
        cited.setId("spub_cited");
        cited.setEid("2-s2.0-cited");
        ScholardexPublicationFact citing = new ScholardexPublicationFact();
        citing.setId("spub_citing");
        citing.setEid("2-s2.0-citing");
        ScholardexCitationFact existing = new ScholardexCitationFact();
        existing.setId("scit_existing");
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setEntityType(ScholardexEntityType.CITATION);
        link.setSource("SCOPUS_JSON_UPLOAD");
        link.setSourceRecordId("2-s2.0-cited->2-s2.0-citing");
        link.setCanonicalEntityId("scit_existing");

        when(scholardexPublicationFactRepository.findAllByEidIn(any()))
                .thenReturn(List.of(cited, citing));
        when(scholardexCitationFactRepository.findAllById(any()))
                .thenReturn(List.of(existing));
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.CITATION), any()))
                .thenReturn(List.of(link));

        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ReflectionTestUtils.invokeMethod(service, "preloadChunkContext", List.of(explicit, fallback), context);

        @SuppressWarnings("unchecked")
        Map<String, String> publicationIdByEid =
                (Map<String, String>) ReflectionTestUtils.getField(context, "publicationIdByEid");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexCitationFact> citationById =
                (Map<String, ScholardexCitationFact>) ReflectionTestUtils.getField(context, "citationById");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "sourceLinkCache");

        assertEquals("spub_cited", publicationIdByEid.get("2-s2.0-cited"));
        assertEquals("spub_citing", publicationIdByEid.get("2-s2.0-citing"));
        assertSame(existing, citationById.get("scit_existing"));
        assertEquals("scit_existing", sourceLinkCache.get(
                ScholardexSourceLinkService.SourceLinkKey.of(
                        ScholardexEntityType.CITATION,
                        "SCOPUS_JSON_UPLOAD",
                        "2-s2.0-cited->2-s2.0-citing"
                )
        ).getCanonicalEntityId());
    }

    @Test
    void processSourceFactMarksSkippedWhenCitingPublicationUnresolved() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<String, String> publicationIdByEid =
                (Map<String, String>) ReflectionTestUtils.getField(context, "publicationIdByEid");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexIdentityConflict> pendingConflicts =
                (Map<String, ScholardexIdentityConflict>) ReflectionTestUtils.getField(context, "pendingConflicts");
        publicationIdByEid.put("2-s2.0-cited", "spub_cited");

        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setCitedEid("2-s2.0-cited");
        sourceFact.setCitingEid("2-s2.0-missing");
        sourceFact.setSourceEventId("evt-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");

        ImportProcessingResult result = new ImportProcessingResult(10);
        ReflectionTestUtils.invokeMethod(service, "processSourceFact", sourceFact, result, context);

        assertEquals(1, result.getSkippedCount());
        assertEquals(1, pendingConflicts.size());
        ScholardexIdentityConflict conflict = pendingConflicts.values().iterator().next();
        assertEquals("UNRESOLVED_CITING_PUBLICATION", conflict.getReasonCode());
        assertEquals(List.of("spub_cited"), conflict.getCandidateCanonicalIds());
        assertEquals("2-s2.0-cited->2-s2.0-missing", conflict.getIncomingSourceRecordId());
    }

    @Test
    void processSourceFactMarksSkippedWhenSourceRecordCollidesWithDifferentExistingEdge() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<String, String> publicationIdByEid =
                (Map<String, String>) ReflectionTestUtils.getField(context, "publicationIdByEid");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexCitationFact> citationById =
                (Map<String, ScholardexCitationFact>) ReflectionTestUtils.getField(context, "citationById");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "sourceLinkCache");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexIdentityConflict> pendingConflicts =
                (Map<String, ScholardexIdentityConflict>) ReflectionTestUtils.getField(context, "pendingConflicts");

        publicationIdByEid.put("2-s2.0-cited", "spub_cited");
        publicationIdByEid.put("2-s2.0-citing", "spub_citing");
        ScholardexCitationFact existingEdge = new ScholardexCitationFact();
        existingEdge.setId("scit_edge");
        String edgeId = ReflectionTestUtils.invokeMethod(service, "buildCanonicalCitationId", "spub_cited", "spub_citing");
        citationById.put(edgeId, existingEdge);
        ScholardexSourceLink sourceLink = new ScholardexSourceLink();
        sourceLink.setEntityType(ScholardexEntityType.CITATION);
        sourceLink.setSource("SCOPUS");
        sourceLink.setSourceRecordId("edge-src");
        sourceLink.setCanonicalEntityId("scit_other");
        sourceLinkCache.put(
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.CITATION, "SCOPUS", "edge-src"),
                sourceLink
        );
        when(sourceLinkService.normalizeSource("SCOPUS")).thenReturn("SCOPUS");

        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS");
        sourceFact.setSourceRecordId("edge-src");
        sourceFact.setCitedEid("2-s2.0-cited");
        sourceFact.setCitingEid("2-s2.0-citing");

        ImportProcessingResult result = new ImportProcessingResult(10);
        ReflectionTestUtils.invokeMethod(service, "processSourceFact", sourceFact, result, context);

        assertEquals(1, result.getSkippedCount());
        assertEquals(1, pendingConflicts.size());
        ScholardexIdentityConflict conflict = pendingConflicts.values().iterator().next();
        assertEquals("CITATION_SOURCE_RECORD_COLLISION", conflict.getReasonCode());
        assertEquals(List.of("scit_edge"), conflict.getCandidateCanonicalIds());
    }

    @Test
    void processSourceFactUsesFallbackSourceRecordIdAndSkipsAlreadyKnownEdge() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<String, String> publicationIdByEid =
                (Map<String, String>) ReflectionTestUtils.getField(context, "publicationIdByEid");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexCitationFact> citationById =
                (Map<String, ScholardexCitationFact>) ReflectionTestUtils.getField(context, "citationById");
        publicationIdByEid.put("2-s2.0-cited", "spub_cited");
        publicationIdByEid.put("2-s2.0-citing", "spub_citing");
        ScholardexCitationFact existingEdge = new ScholardexCitationFact();
        existingEdge.setId("scit_edge");
        String edgeId = ReflectionTestUtils.invokeMethod(service, "buildCanonicalCitationId", "spub_cited", "spub_citing");
        citationById.put(edgeId, existingEdge);
        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setCitedEid("2-s2.0-cited");
        sourceFact.setCitingEid("2-s2.0-citing");
        sourceFact.setSourceEventId("evt-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");

        ImportProcessingResult result = new ImportProcessingResult(10);
        ReflectionTestUtils.invokeMethod(service, "processSourceFact", sourceFact, result, context);

        assertEquals(1, result.getSkippedCount());
        @SuppressWarnings("unchecked")
        Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingSourceLinkCommands =
                (Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand>) ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");
        ScholardexSourceLinkService.SourceLinkUpsertCommand command = pendingSourceLinkCommands.values().iterator().next();
        assertEquals("2-s2.0-cited->2-s2.0-citing", command.sourceRecordId());
        assertEquals("scit_edge", command.canonicalEntityId());
    }

    @Test
    void lastRecordKeyUsesSourceRecordIdThenFallsBackToCitationPair() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );
        ScopusCitationFact explicit = new ScopusCitationFact();
        explicit.setSourceRecordId("explicit");
        explicit.setCitedEid("2-s2.0-cited");
        explicit.setCitingEid("2-s2.0-citing");
        ScopusCitationFact fallback = new ScopusCitationFact();
        fallback.setCitedEid("2-s2.0-cited");
        fallback.setCitingEid("2-s2.0-citing");

        assertEquals("explicit", ReflectionTestUtils.invokeMethod(service, "lastRecordKey", List.of(explicit)));
        assertEquals("2-s2.0-cited->2-s2.0-citing", ReflectionTestUtils.invokeMethod(service, "lastRecordKey", List.of(fallback)));
        assertNull(ReflectionTestUtils.invokeMethod(service, "lastRecordKey", List.of()));
    }

    @Test
    void loadSourceFactsStopsAfterLastPageInFullRescan() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService
        );

        ScopusCitationFact first = new ScopusCitationFact();
        first.setSourceRecordId("first");
        ScopusCitationFact second = new ScopusCitationFact();
        second.setSourceRecordId("second");
        List<ScopusCitationFact> firstPageFacts = java.util.Collections.nCopies(1_000, first);

        when(scopusCitationFactRepository.count()).thenReturn(1_001L);
        org.mockito.Mockito.doAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            if (pageable.getPageNumber() == 0 && pageable.getPageSize() == 1_000) {
                return new PageImpl<>(firstPageFacts, PageRequest.of(0, 1_000), 1_001);
            }
            if (pageable.getPageNumber() == 1 && pageable.getPageSize() == 1_000) {
                return new PageImpl<>(List.of(second), PageRequest.of(1, 1_000), 1_001);
            }
            throw new AssertionError("Unexpected page request: " + pageable);
        }).when(scopusCitationFactRepository).findAll(any(Pageable.class));

        List<ScopusCitationFact> loaded =
                ReflectionTestUtils.invokeMethod(service, "loadSourceFacts", fullRescanOptions());

        assertEquals(1_001, loaded.size());
        assertEquals("first", loaded.getFirst().getSourceRecordId());
        assertEquals("second", loaded.getLast().getSourceRecordId());
        verify(scopusCitationFactRepository, never()).findAll(argThat((Pageable pageable) -> pageable.getPageNumber() >= 2));
    }

    private CanonicalBuildOptions fullRescanOptions() {
        return new CanonicalBuildOptions(null, null, true, null, null, false, false);
    }
}
