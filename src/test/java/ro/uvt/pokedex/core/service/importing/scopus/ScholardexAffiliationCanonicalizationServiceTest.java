package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexAffiliationCanonicalizationServiceTest {

    @Mock private ScopusAffiliationFactRepository scopusAffiliationFactRepository;
    @Mock private ScholardexAffiliationFactRepository scholardexAffiliationFactRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock private ScholardexCanonicalBuildCheckpointService checkpointService;

    @Test
    void exposesAffiliationPipelineContractAndNormalizationHelpers() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );
        ReflectionTestUtils.setField(service, "heartbeatSeconds", 17L);
        int defaultChunkSize = ReflectionTestUtils.invokeMethod(service, "getDefaultChunkSize");
        long heartbeatSeconds = ReflectionTestUtils.invokeMethod(service, "getHeartbeatSeconds");
        String alias = ReflectionTestUtils.invokeMethod(service, "normalizeAlias", "Universitatea de Vest", "Timisoara", "RO");
        String blankAlias = ReflectionTestUtils.invokeMethod(service, "normalizeAlias", null, null, null);

        assertEquals(
                ScholardexCanonicalBuildCheckpointService.AFFILIATION_PIPELINE_KEY,
                ReflectionTestUtils.invokeMethod(service, "getPipelineKey")
        );
        assertEquals("affiliation", ReflectionTestUtils.invokeMethod(service, "getEntityTypeLabel"));
        assertEquals(ScholardexEntityType.AFFILIATION, ReflectionTestUtils.invokeMethod(service, "getEntityType"));
        assertEquals(5_000, defaultChunkSize);
        assertEquals("scopus-affiliation-facts-v1", ReflectionTestUtils.invokeMethod(service, "getDefaultSourceVersion"));
        assertEquals(17L, heartbeatSeconds);

        assertEquals(
                service.buildCanonicalAffiliationId(null, "Universitatea de Vest", "Timisoara", "RO"),
                service.buildCanonicalAffiliationId(null, " universitatea de vest ", " timisoara ", " ro ")
        );
        assertEquals("universitatea de vest|timisoara|ro", alias);
        assertNull(blankAlias);
    }

    @Test
    void upsertFromScopusFactRecoversDuplicateScopusAffiliationIdByReusingExistingCanonicalRecord() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );

        ScopusAffiliationFact sourceFact = new ScopusAffiliationFact();
        sourceFact.setAfid("112945959");
        sourceFact.setName("Recovered Affiliation");
        sourceFact.setCity("Timisoara");
        sourceFact.setCountry("RO");
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceEventId("ev-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");

        ScholardexAffiliationFact existing = new ScholardexAffiliationFact();
        existing.setId("saff_existing");
        existing.getScopusAffiliationIds().add("112945959");

        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_UPLOAD")).thenReturn("SCOPUS_JSON_UPLOAD");
        when(scholardexAffiliationFactRepository.findByScopusAffiliationIdsIn(anyCollection())).thenReturn(List.of());
        when(scholardexAffiliationFactRepository.saveAll(anyCollection()))
                .thenThrow(new DuplicateKeyException("dup affiliation source id"));
        when(scholardexAffiliationFactRepository.findByScopusAffiliationIdsContains("112945959"))
                .thenReturn(Optional.of(existing));
        when(scholardexAffiliationFactRepository.save(any(ScholardexAffiliationFact.class)))
                .thenThrow(new DuplicateKeyException("dup affiliation source id"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(sourceFact, result);

        ArgumentCaptor<ScholardexAffiliationFact> affiliationCaptor = ArgumentCaptor.forClass(ScholardexAffiliationFact.class);
        verify(scholardexAffiliationFactRepository, atLeastOnce()).save(affiliationCaptor.capture());
        ScholardexAffiliationFact recovered = affiliationCaptor.getAllValues().getLast();
        assertEquals("saff_existing", recovered.getId());
        assertEquals("batch-1", recovered.getSourceBatchId());
        assertEquals("ev-1", recovered.getSourceEventId());
        assertEquals("corr-1", recovered.getSourceCorrelationId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ScholardexSourceLinkService.SourceLinkUpsertCommand>> commandCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sourceLinkService).batchUpsertWithState(commandCaptor.capture(), any(), eq(false));
        ScholardexSourceLinkService.SourceLinkUpsertCommand command = commandCaptor.getValue().iterator().next();
        assertEquals("saff_existing", command.canonicalEntityId());
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void upsertFromScopusFactCreatesNewAffiliationWithRequiredFields() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );

        ScopusAffiliationFact sourceFact = new ScopusAffiliationFact();
        sourceFact.setAfid("777");
        sourceFact.setName("New University");
        sourceFact.setCity("Timisoara");
        sourceFact.setCountry("RO");
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceEventId("ev-new");
        sourceFact.setSourceBatchId("batch-new");
        sourceFact.setSourceCorrelationId("corr-new");

        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS_JSON_BOOTSTRAP");
        when(scholardexAffiliationFactRepository.findByScopusAffiliationIdsIn(anyCollection())).thenReturn(List.of());
        when(scholardexAffiliationFactRepository.saveAll(anyCollection()))
                .thenAnswer(invocation -> List.copyOf((java.util.Collection<?>) invocation.getArgument(0)));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(sourceFact, result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ScholardexAffiliationFact>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(scholardexAffiliationFactRepository, atLeastOnce()).saveAll(captor.capture());
        ScholardexAffiliationFact saved = captor.getValue().iterator().next();
        assertNotNull(saved.getId());
        assertEquals("New University", saved.getName());
        assertEquals("SCOPUS_JSON_BOOTSTRAP", saved.getSource());
        assertEquals("batch-new", saved.getSourceBatchId());
        assertEquals("corr-new", saved.getSourceCorrelationId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void upsertFromScopusFactUpdatesExistingAffiliationAndMarksUpdated() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );

        ScopusAffiliationFact sourceFact = new ScopusAffiliationFact();
        sourceFact.setAfid("777");
        sourceFact.setName("Updated University");
        sourceFact.setCity("Timisoara");
        sourceFact.setCountry("RO");
        sourceFact.setSource("SCOPUS");
        sourceFact.setSourceEventId("ev-upd");
        sourceFact.setSourceBatchId("batch-upd");
        sourceFact.setSourceCorrelationId("corr-upd");

        ScholardexAffiliationFact existing = new ScholardexAffiliationFact();
        existing.setId("saff_existing");
        existing.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        existing.getScopusAffiliationIds().add("777");
        existing.setName("Old University");

        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(sourceLinkService.normalizeSource("SCOPUS")).thenReturn("SCOPUS");
        when(scholardexAffiliationFactRepository.findByScopusAffiliationIdsIn(anyCollection())).thenReturn(List.of(existing));
        when(scholardexAffiliationFactRepository.saveAll(anyCollection()))
                .thenAnswer(invocation -> List.copyOf((java.util.Collection<?>) invocation.getArgument(0)));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(sourceFact, result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ScholardexAffiliationFact>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(scholardexAffiliationFactRepository).saveAll(captor.capture());
        ScholardexAffiliationFact saved = captor.getValue().iterator().next();
        assertSame(existing, saved);
        assertEquals("saff_existing", saved.getId());
        assertEquals("Updated University", saved.getName());
        assertEquals("updated university", saved.getNameNormalized());
        assertEquals("batch-upd", saved.getSourceBatchId());
        assertEquals("corr-upd", saved.getSourceCorrelationId());
        assertEquals(1, result.getUpdatedCount());
        assertEquals(0, result.getImportedCount());
    }

    @Test
    void rewritePendingSourceLinkCommandCanonicalIdOnlyTouchesMatchingAffiliationCommands() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );

        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingCommands =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLinkService.SourceLinkUpsertCommand>)
                        ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");

        ScholardexSourceLinkService.SourceLinkKey matchingKey =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS", "777");
        ScholardexSourceLinkService.SourceLinkKey sameRecordDifferentSourceKey =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "WOS", "777");
        ScholardexSourceLinkService.SourceLinkKey otherKey =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AUTHOR, "SCOPUS", "777");
        ScholardexSourceLinkService.SourceLinkUpsertCommand matchingCommand =
                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AFFILIATION, "SCOPUS", "777", "saff_old",
                        ScholardexSourceLinkService.STATE_LINKED, "reason", "evt", "batch", "corr", false
                );
        ScholardexSourceLinkService.SourceLinkUpsertCommand sameRecordDifferentSourceCommand =
                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AFFILIATION, "WOS", "777", "wos_old",
                        ScholardexSourceLinkService.STATE_LINKED, "reason", "evt", "batch", "corr", false
                );
        ScholardexSourceLinkService.SourceLinkUpsertCommand otherCommand =
                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR, "SCOPUS", "777", "sauth_old",
                        ScholardexSourceLinkService.STATE_LINKED, "reason", "evt", "batch", "corr", false
                );
        pendingCommands.put(matchingKey, matchingCommand);
        pendingCommands.put(sameRecordDifferentSourceKey, sameRecordDifferentSourceCommand);
        pendingCommands.put(otherKey, otherCommand);

        ReflectionTestUtils.invokeMethod(service, "rewritePendingSourceLinkCommandCanonicalId", "SCOPUS", "777", "saff_new", context);
        ReflectionTestUtils.invokeMethod(service, "rewritePendingSourceLinkCommandCanonicalId", null, "777", "ignored", context);

        assertEquals("saff_new", pendingCommands.get(matchingKey).canonicalEntityId());
        assertEquals("wos_old", pendingCommands.get(sameRecordDifferentSourceKey).canonicalEntityId());
        assertEquals("sauth_old", pendingCommands.get(otherKey).canonicalEntityId());
    }

    @Test
    void preloadChunkContextCachesSourceLinksAndExistingAffiliations() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );

        ScopusAffiliationFact sourceFact = new ScopusAffiliationFact();
        sourceFact.setAfid("777");

        ScholardexSourceLink sourceLink = new ScholardexSourceLink();
        sourceLink.setEntityType(ScholardexEntityType.AFFILIATION);
        sourceLink.setSource("SCOPUS_JSON_UPLOAD");
        sourceLink.setSourceRecordId("777");
        sourceLink.setCanonicalEntityId("saff_linked");

        ScholardexAffiliationFact linked = new ScholardexAffiliationFact();
        linked.setId("saff_linked");
        ScholardexAffiliationFact existingBySource = new ScholardexAffiliationFact();
        existingBySource.setId("saff_existing");
        existingBySource.getScopusAffiliationIds().add("777");

        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of(sourceLink));
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_UPLOAD")).thenReturn("SCOPUS_JSON_UPLOAD");
        when(scholardexAffiliationFactRepository.findByIdIn(anyCollection())).thenReturn(List.of(linked));
        when(scholardexAffiliationFactRepository.findByScopusAffiliationIdsIn(anyCollection())).thenReturn(List.of(existingBySource));

        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ReflectionTestUtils.invokeMethod(service, "preloadChunkContext", List.of(sourceFact), context);

        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> preloadedSourceLinks =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "preloadedSourceLinks");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexAffiliationFact> affiliationByCanonicalId =
                (Map<String, ScholardexAffiliationFact>) ReflectionTestUtils.getField(context, "affiliationByCanonicalId");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexAffiliationFact> affiliationBySourceId =
                (Map<String, ScholardexAffiliationFact>) ReflectionTestUtils.getField(context, "affiliationBySourceId");

        assertEquals("saff_linked", preloadedSourceLinks.get(
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS_JSON_UPLOAD", "777")
        ).getCanonicalEntityId());
        assertSame(linked, affiliationByCanonicalId.get("saff_linked"));
        assertSame(existingBySource, affiliationBySourceId.get("777"));
    }

    @Test
    void recoverAffiliationWriteMergesRecoveredFieldsAndRewritesMatchingPendingCommand() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");

        ScholardexAffiliationFact incoming = new ScholardexAffiliationFact();
        incoming.setId("saff_incoming");
        incoming.setName("Recovered University");
        incoming.setNameNormalized("recovered university");
        incoming.setCity("Timisoara");
        incoming.setCountry("RO");
        incoming.getAliases().add("alias-1");
        incoming.setSource("SCOPUS_JSON_UPLOAD");
        incoming.setSourceRecordId("777");
        incoming.setSourceEventId("evt-1");
        incoming.setSourceBatchId("batch-1");
        incoming.setSourceCorrelationId("corr-1");

        ScholardexAffiliationFact recovered = new ScholardexAffiliationFact();
        recovered.setId("saff_existing");
        recovered.getScopusAffiliationIds().add("111");
        recovered.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));

        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingCommands =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLinkService.SourceLinkUpsertCommand>)
                        ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");
        ScholardexSourceLinkService.SourceLinkKey matchingKey =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS_JSON_UPLOAD", "777");
        ScholardexSourceLinkService.SourceLinkKey otherKey =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS", "777");
        pendingCommands.put(matchingKey, new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                ScholardexEntityType.AFFILIATION, "SCOPUS_JSON_UPLOAD", "777", "old_id",
                ScholardexSourceLinkService.STATE_LINKED, "reason", "evt", "batch", "corr", false
        ));
        pendingCommands.put(otherKey, new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "777", "other_id",
                ScholardexSourceLinkService.STATE_LINKED, "reason", "evt", "batch", "corr", false
        ));

        when(scholardexAffiliationFactRepository.save(incoming)).thenThrow(new DuplicateKeyException("dup"));
        when(scholardexAffiliationFactRepository.findByScopusAffiliationIdsContains("777")).thenReturn(Optional.of(recovered));
        when(scholardexAffiliationFactRepository.save(recovered)).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "recoverAffiliationWrite", incoming, context);

        assertEquals(List.of("111", "777"), recovered.getScopusAffiliationIds());
        assertEquals("Recovered University", recovered.getName());
        assertEquals("recovered university", recovered.getNameNormalized());
        assertEquals("Timisoara", recovered.getCity());
        assertEquals("RO", recovered.getCountry());
        assertEquals(List.of("alias-1"), recovered.getAliases());
        assertEquals("SCOPUS_JSON_UPLOAD", recovered.getSource());
        assertEquals("777", recovered.getSourceRecordId());
        assertEquals("batch-1", recovered.getSourceBatchId());
        assertEquals("corr-1", recovered.getSourceCorrelationId());
        assertNotNull(recovered.getUpdatedAt());
        assertEquals("saff_existing", pendingCommands.get(matchingKey).canonicalEntityId());
        assertEquals("other_id", pendingCommands.get(otherKey).canonicalEntityId());

        @SuppressWarnings("unchecked")
        Map<String, ScholardexAffiliationFact> affiliationByCanonicalId =
                (Map<String, ScholardexAffiliationFact>) ReflectionTestUtils.getField(context, "affiliationByCanonicalId");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexAffiliationFact> affiliationBySourceId =
                (Map<String, ScholardexAffiliationFact>) ReflectionTestUtils.getField(context, "affiliationBySourceId");
        assertSame(recovered, affiliationByCanonicalId.get("saff_existing"));
        assertSame(recovered, affiliationBySourceId.get("777"));
    }

    @Test
    void processSourceFactUsesPreloadedSourceLinkAndMarksSkippedOnCanonicalCollision() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> preloadedSourceLinks =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "preloadedSourceLinks");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexAffiliationFact> affiliationBySourceId =
                (Map<String, ScholardexAffiliationFact>) ReflectionTestUtils.getField(context, "affiliationBySourceId");
        @SuppressWarnings("unchecked")
        List<Object> pendingConflicts = (List<Object>) ReflectionTestUtils.getField(context, "pendingConflicts");

        ScholardexSourceLink sourceLink = new ScholardexSourceLink();
        sourceLink.setCanonicalEntityId("saff_linked");
        preloadedSourceLinks.put(
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS", "777"),
                sourceLink
        );
        ScholardexAffiliationFact existingBySource = new ScholardexAffiliationFact();
        existingBySource.setId("saff_existing");
        existingBySource.getScopusAffiliationIds().add("777");
        affiliationBySourceId.put("777", existingBySource);

        ScopusAffiliationFact sourceFact = new ScopusAffiliationFact();
        sourceFact.setAfid("777");
        sourceFact.setSource("SCOPUS");
        sourceFact.setSourceEventId("evt-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");
        when(sourceLinkService.normalizeSource("SCOPUS")).thenReturn("SCOPUS");

        ImportProcessingResult result = new ImportProcessingResult(10);
        ReflectionTestUtils.invokeMethod(service, "processSourceFact", sourceFact, result, context);

        assertEquals(1, result.getSkippedCount());
        assertEquals(1, pendingConflicts.size());
        @SuppressWarnings("unchecked")
        Map<String, ScholardexAffiliationFact> pendingAffiliationSaves =
                (Map<String, ScholardexAffiliationFact>) ReflectionTestUtils.getField(context, "pendingAffiliationSaves");
        assertTrue(pendingAffiliationSaves.isEmpty());
    }

    @Test
    void rebuildCanonicalAffiliationFactsFromScopusFactsUsesDefaultWrapperAndSortsByAfid() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );

        ScopusAffiliationFact factZzz = new ScopusAffiliationFact();
        factZzz.setAfid("zzz");
        factZzz.setName("Zed");
        factZzz.setSource("SCOPUS");
        ScopusAffiliationFact factAaa = new ScopusAffiliationFact();
        factAaa.setAfid("aaa");
        factAaa.setName("Aye");
        factAaa.setSource("SCOPUS");

        when(scopusAffiliationFactRepository.findAll()).thenReturn(List.of(factZzz, factAaa));
        when(checkpointService.readCheckpoint(any())).thenReturn(Optional.empty());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(sourceLinkService.normalizeSource("SCOPUS")).thenReturn("SCOPUS");
        when(scholardexAffiliationFactRepository.findByScopusAffiliationIdsIn(anyCollection())).thenReturn(List.of());
        when(scholardexAffiliationFactRepository.saveAll(anyCollection()))
                .thenAnswer(invocation -> List.copyOf((java.util.Collection<?>) invocation.getArgument(0)));

        ImportProcessingResult result = service.rebuildCanonicalAffiliationFactsFromScopusFacts();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ScholardexAffiliationFact>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(scholardexAffiliationFactRepository).saveAll(captor.capture());
        List<ScholardexAffiliationFact> saved = List.copyOf(captor.getValue());
        assertEquals("aaa", saved.get(0).getSourceRecordId());
        assertEquals("zzz", saved.get(1).getSourceRecordId());
        assertEquals(2, result.getProcessedCount());
    }

    @Test
    void upsertFromScopusFactSkipsBlankAffiliationIds() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );
        ImportProcessingResult result = new ImportProcessingResult(10);

        ScopusAffiliationFact blankId = new ScopusAffiliationFact();
        blankId.setAfid("   ");
        service.upsertFromScopusFact(blankId, result);

        assertEquals(1, result.getSkippedCount());
    }

    @Test
    void upsertSourceLinkCommandSkipsNullSourceLinkKey() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingCommands =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLinkService.SourceLinkUpsertCommand>)
                        ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");
        ScopusAffiliationFact sourceFact = new ScopusAffiliationFact();
        sourceFact.setSourceEventId("evt");
        sourceFact.setSourceBatchId("batch");
        sourceFact.setSourceCorrelationId("corr");

        ReflectionTestUtils.invokeMethod(service, "upsertSourceLinkCommand", sourceFact, "777", "saff_1", null, context);

        assertEquals(0, pendingCommands.size());
    }

    @Test
    void rebuildCanonicalAffiliationFactsFromScopusFactsOptionsWrapperUsesBatchFilter() {
        ScholardexAffiliationCanonicalizationService service = new ScholardexAffiliationCanonicalizationService(
                scopusAffiliationFactRepository,
                scholardexAffiliationFactRepository,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
        );

        when(scopusAffiliationFactRepository.findBySourceBatchId("batch-1")).thenReturn(List.of());
        when(checkpointService.readCheckpoint(any())).thenReturn(Optional.empty());

        ImportProcessingResult result = service.rebuildCanonicalAffiliationFactsFromScopusFacts(
                new CanonicalBuildOptions(null, null, true, "batch-1", null, false, false)
        );

        assertEquals(0, result.getProcessedCount());
    }
}
