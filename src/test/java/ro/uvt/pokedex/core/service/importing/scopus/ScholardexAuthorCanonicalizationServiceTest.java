package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholardexAuthorCanonicalizationServiceTest {

    @Mock private ScopusAuthorFactRepository scopusAuthorFactRepository;
    @Mock private ScholardexAuthorFactRepository scholardexAuthorFactRepository;
    @Mock private ScholardexAuthorAffiliationFactRepository scholardexAuthorAffiliationFactRepository;
    @Mock private ScholardexEdgeWriterService edgeWriterService;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock private ScholardexCanonicalBuildCheckpointService checkpointService;
    @Mock private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    private org.springframework.data.mongodb.core.BulkOperations bulkOps;

    @org.junit.jupiter.api.BeforeEach
    void stubBulkOps() {
        // H56 lever 3: author facts are now persisted via mongoTemplate.bulkOps(...).replaceOne(...);
        // provide a no-op bulk chain so unit tests that flush facts don't NPE (previously saveAll was
        // a void no-op on the mocked repository). Tests can capture replaceOne facts or make execute()
        // throw to exercise the duplicate-key recovery path.
        bulkOps = org.mockito.Mockito.mock(org.springframework.data.mongodb.core.BulkOperations.class);
        org.mockito.Mockito.lenient().when(mongoTemplate.bulkOps(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Class.class)))
                .thenReturn(bulkOps);
        org.mockito.Mockito.lenient().when(bulkOps.replaceOne(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(bulkOps);
    }

    @Test
    void exposesAuthorPipelineContract() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );
        ReflectionTestUtils.setField(service, "heartbeatSeconds", 23L);
        int defaultChunkSize = ReflectionTestUtils.invokeMethod(service, "getDefaultChunkSize");
        long heartbeatSeconds = ReflectionTestUtils.invokeMethod(service, "getHeartbeatSeconds");

        assertEquals(
                ScholardexCanonicalBuildCheckpointService.AUTHOR_PIPELINE_KEY,
                ReflectionTestUtils.invokeMethod(service, "getPipelineKey")
        );
        assertEquals("author", ReflectionTestUtils.invokeMethod(service, "getEntityTypeLabel"));
        assertEquals(ScholardexEntityType.AUTHOR, ReflectionTestUtils.invokeMethod(service, "getEntityType"));
        assertEquals(5_000, defaultChunkSize);
        assertEquals("scopus-author-facts-v1", ReflectionTestUtils.invokeMethod(service, "getDefaultSourceVersion"));
        assertEquals(23L, heartbeatSeconds);
    }

    @Test
    void upsertFromScopusFactRecoversDuplicateScopusAuthorIdByReusingExistingCanonicalRecord() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("11139804700");
        sourceFact.setName("Recovered Author");
        sourceFact.setAffiliationIds(List.of());
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceEventId("ev-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");

        ScholardexAuthorFact existing = new ScholardexAuthorFact();
        existing.setId("sauth_existing");
        existing.getScopusAuthorIds().add("11139804700");

        when(sourceLinkService.normalizeSource("SCOPUS_JSON_UPLOAD")).thenReturn("SCOPUS_JSON_UPLOAD");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), anyCollection()))
                .thenReturn(List.of());
        when(scopusAuthorFactRepository.findBySourceBatchId("batch-1")).thenReturn(List.of(sourceFact));
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorFactRepository.findByIdIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorAffiliationFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(bulkOps.execute())
                .thenThrow(new DuplicateKeyException("dup author source id"));
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsContains("11139804700"))
                .thenReturn(Optional.of(existing));
        when(scholardexAuthorFactRepository.save(any(ScholardexAuthorFact.class)))
                .thenThrow(new DuplicateKeyException("dup author source id"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sourceLinkService.batchUpsertWithState(anyCollection(), any(), eq(true)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));
        when(edgeWriterService.batchUpsertAuthorAffiliationEdges(anyList(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(0, 0, 0, 0, 0));

        ImportProcessingResult result = service.rebuildCanonicalAuthorFactsFromScopusFacts(
                new CanonicalBuildOptions(1000, null, false, "batch-1", null, false, false)
        );

        ArgumentCaptor<ScholardexAuthorFact> authorCaptor = ArgumentCaptor.forClass(ScholardexAuthorFact.class);
        verify(scholardexAuthorFactRepository, atLeastOnce()).save(authorCaptor.capture());
        ScholardexAuthorFact recovered = authorCaptor.getAllValues().getLast();
        assertEquals("sauth_existing", recovered.getId());
        assertEquals("batch-1", recovered.getSourceBatchId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ScholardexSourceLinkService.SourceLinkUpsertCommand>> commandCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sourceLinkService).batchUpsertWithState(commandCaptor.capture(), any(), eq(true));
        ScholardexSourceLinkService.SourceLinkUpsertCommand command = commandCaptor.getValue().iterator().next();
        assertEquals("sauth_existing", command.canonicalEntityId());
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void upsertFromScopusFactUsesStableAffiliationFallbackIdsAcrossScopusVariants() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact bootstrap = new ScopusAuthorFact();
        bootstrap.setAuthorId("111");
        bootstrap.setName("Ada Lovelace");
        bootstrap.setAffiliationIds(List.of("60000434"));
        bootstrap.setSource("SCOPUS_JSON_BOOTSTRAP");

        ScopusAuthorFact python = new ScopusAuthorFact();
        python.setAuthorId("222");
        python.setName("Grace Hopper");
        python.setAffiliationIds(List.of("60000434"));
        python.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");

        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AUTHOR), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsContains(any())).thenReturn(Optional.empty());
        when(scholardexAuthorFactRepository.findById(any())).thenReturn(Optional.empty());
        when(scholardexAuthorFactRepository.save(any(ScholardexAuthorFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.normalizeSource("SCOPUS_PYTHON_AUTHOR_WORKS")).thenReturn("SCOPUS");
        when(sourceLinkService.batchUpsertWithState(anyCollection(), any()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        service.upsertFromScopusFact(bootstrap, new ImportProcessingResult(10));
        service.upsertFromScopusFact(python, new ImportProcessingResult(10));

        ArgumentCaptor<ScholardexAuthorFact> authorCaptor = ArgumentCaptor.forClass(ScholardexAuthorFact.class);
        verify(scholardexAuthorFactRepository, atLeastOnce()).save(authorCaptor.capture());

        List<ScholardexAuthorFact> savedFacts = authorCaptor.getAllValues();
        String bootstrapFallback = savedFacts.get(savedFacts.size() - 2).getAffiliationIds().getFirst();
        String pythonFallback = savedFacts.getLast().getAffiliationIds().getFirst();
        assertEquals(bootstrapFallback, pythonFallback);
    }

    @Test
    void upsertFromScopusFactMergesAlternativeNamesIntoCanonicalAuthor() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("111");
        sourceFact.setName("Spataru A.");
        sourceFact.setAlternativeNames(List.of("Spataru, Adrian", "Adrian Spataru"));
        sourceFact.setAffiliationIds(List.of());
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");

        ScholardexAuthorFact existing = new ScholardexAuthorFact();
        existing.setId("sauth_existing");
        existing.getScopusAuthorIds().add("111");
        existing.setDisplayName("Spataru, Adrian");
        existing.setAlternativeNames(List.of("A. Spataru"));

        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AUTHOR), any(), eq("111"))).thenReturn(Optional.empty());
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsContains("111")).thenReturn(Optional.of(existing));
        when(scholardexAuthorFactRepository.findById("sauth_existing")).thenReturn(Optional.of(existing));
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(scholardexAuthorFactRepository.save(any(ScholardexAuthorFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.batchUpsertWithState(anyCollection(), any()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        service.upsertFromScopusFact(sourceFact, new ImportProcessingResult(10));

        ArgumentCaptor<ScholardexAuthorFact> authorCaptor = ArgumentCaptor.forClass(ScholardexAuthorFact.class);
        verify(scholardexAuthorFactRepository).save(authorCaptor.capture());
        ScholardexAuthorFact saved = authorCaptor.getValue();
        assertEquals("Spataru A.", saved.getDisplayName());
        assertEquals("spataru a", saved.getNameNormalized());
        assertIterableEquals(List.of("Spataru, Adrian", "A. Spataru", "Adrian Spataru"), saved.getAlternativeNames());
    }

    @Test
    void upsertFromScopusFactCreatesNewAuthorWithRequiredFields() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("999");
        sourceFact.setName("New Author");
        sourceFact.setAffiliationIds(List.of());
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceEventId("ev-new");
        sourceFact.setSourceBatchId("batch-new");
        sourceFact.setSourceCorrelationId("corr-new");

        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AUTHOR), any(), any())).thenReturn(Optional.empty());
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsContains("999")).thenReturn(Optional.empty());
        when(scholardexAuthorFactRepository.findById(any())).thenReturn(Optional.empty());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(scholardexAuthorFactRepository.save(any(ScholardexAuthorFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.batchUpsertWithState(anyCollection(), any()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(sourceFact, result);

        ArgumentCaptor<ScholardexAuthorFact> authorCaptor = ArgumentCaptor.forClass(ScholardexAuthorFact.class);
        verify(scholardexAuthorFactRepository).save(authorCaptor.capture());
        ScholardexAuthorFact saved = authorCaptor.getValue();
        assertNotNull(saved.getId());
        assertEquals("New Author", saved.getDisplayName());
        assertEquals("new author", saved.getNameNormalized());
        assertEquals("SCOPUS_JSON_BOOTSTRAP", saved.getSource());
        assertEquals("999", saved.getSourceRecordId());
        assertEquals("batch-new", saved.getSourceBatchId());
        assertEquals("corr-new", saved.getSourceCorrelationId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void upsertFromScopusFactSkipsMissingAuthorId() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(null, result);

        ScopusAuthorFact blank = new ScopusAuthorFact();
        blank.setAuthorId("   ");
        service.upsertFromScopusFact(blank, result);

        assertEquals(2, result.getSkippedCount());
        verify(scholardexAuthorFactRepository, never()).save(any());
    }

    @Test
    void upsertFromScopusFactSkipsWhenSourceLinkAndExistingAuthorDisagreeOnCanonicalId() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("111");
        sourceFact.setName("Collision Author");
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceEventId("evt-collision");
        sourceFact.setSourceBatchId("batch-collision");
        sourceFact.setSourceCorrelationId("corr-collision");

        ScholardexSourceLink sourceLink = new ScholardexSourceLink();
        sourceLink.setCanonicalEntityId("sauth_linked");
        ScholardexAuthorFact existingBySource = new ScholardexAuthorFact();
        existingBySource.setId("sauth_existing");
        existingBySource.getScopusAuthorIds().add("111");

        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "SCOPUS_JSON_UPLOAD", "111"))
                .thenReturn(Optional.of(sourceLink));
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsContains("111"))
                .thenReturn(Optional.of(existingBySource));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(sourceFact, result);

        ArgumentCaptor<ScholardexIdentityConflict> conflictCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(conflictCaptor.capture());
        ScholardexIdentityConflict conflict = conflictCaptor.getValue();
        assertEquals(List.of("sauth_linked", "sauth_existing"), conflict.getCandidateCanonicalIds());
        assertEquals(1, result.getSkippedCount());
        verify(scholardexAuthorFactRepository, never()).save(any());
    }

    @Test
    void saveConflictPersistsAuthorConflictMetadata() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("111");
        sourceFact.setName("Collision Author");
        sourceFact.setAffiliationIds(List.of());
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceEventId("evt-collision");
        sourceFact.setSourceBatchId("batch-collision");
        sourceFact.setSourceCorrelationId("corr-collision");

        ReflectionTestUtils.invokeMethod(
                service,
                "saveConflict",
                sourceFact,
                "111",
                "SOURCE_ID_COLLISION",
                List.of("sauth_link", "sauth_existing")
        );

        ArgumentCaptor<ScholardexIdentityConflict> conflictCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(conflictCaptor.capture());
        ScholardexIdentityConflict saved = conflictCaptor.getValue();
        assertEquals(ScholardexEntityType.AUTHOR, saved.getEntityType());
        assertEquals("SCOPUS_JSON_UPLOAD", saved.getIncomingSource());
        assertEquals("111", saved.getIncomingSourceRecordId());
        assertEquals("SOURCE_ID_COLLISION", saved.getReasonCode());
        assertEquals("OPEN", saved.getStatus());
        assertEquals(List.of("sauth_link", "sauth_existing"), saved.getCandidateCanonicalIds());
        assertEquals("evt-collision", saved.getSourceEventId());
        assertEquals("batch-collision", saved.getSourceBatchId());
        assertEquals("corr-collision", saved.getSourceCorrelationId());
        assertNotNull(saved.getDetectedAt());
    }

    @Test
    void saveConflictReusesExistingOpenConflictAndPreservesDetectedAtWhenCandidatesNull() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceEventId("evt-existing");
        sourceFact.setSourceBatchId("batch-existing");
        sourceFact.setSourceCorrelationId("corr-existing");

        ScholardexIdentityConflict existing = new ScholardexIdentityConflict();
        existing.setDetectedAt(Instant.parse("2024-01-01T00:00:00Z"));

        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                ScholardexEntityType.AUTHOR,
                "SCOPUS_JSON_UPLOAD",
                "222",
                "SOURCE_ID_COLLISION",
                "OPEN"
        )).thenReturn(Optional.of(existing));

        ReflectionTestUtils.invokeMethod(service, "saveConflict", sourceFact, "222", "SOURCE_ID_COLLISION", null);

        ArgumentCaptor<ScholardexIdentityConflict> conflictCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(conflictCaptor.capture());
        ScholardexIdentityConflict saved = conflictCaptor.getValue();
        assertSame(existing, saved);
        assertEquals(List.of(), saved.getCandidateCanonicalIds());
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), saved.getDetectedAt());
        assertEquals("evt-existing", saved.getSourceEventId());
        assertEquals("batch-existing", saved.getSourceBatchId());
        assertEquals("corr-existing", saved.getSourceCorrelationId());
    }

    @Test
    void upsertFromScopusFactQueuesFallbackAffiliationLinksAndEdges() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("999");
        sourceFact.setName("Fallback Author");
        sourceFact.setAffiliationIds(List.of("60000434", "60000434"));
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceEventId("evt-fallback");
        sourceFact.setSourceBatchId("batch-fallback");
        sourceFact.setSourceCorrelationId("corr-fallback");

        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AUTHOR), any(), any())).thenReturn(Optional.empty());
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsContains("999")).thenReturn(Optional.empty());
        when(scholardexAuthorFactRepository.findById(any())).thenReturn(Optional.empty());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(scholardexAuthorFactRepository.save(any(ScholardexAuthorFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.batchUpsertWithState(anyCollection(), any()))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(sourceFact, result);

        ArgumentCaptor<ScholardexAuthorFact> authorCaptor = ArgumentCaptor.forClass(ScholardexAuthorFact.class);
        verify(scholardexAuthorFactRepository).save(authorCaptor.capture());
        ScholardexAuthorFact saved = authorCaptor.getValue();
        assertEquals(1, saved.getAffiliationIds().size());
        assertEquals(List.of("60000434"), saved.getPendingAffiliationSourceIds());
        assertEquals("evt-fallback", saved.getSourceEventId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ScholardexSourceLinkService.SourceLinkUpsertCommand>> commandCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sourceLinkService).batchUpsertWithState(commandCaptor.capture(), any());
        ScholardexSourceLinkService.SourceLinkUpsertCommand command = commandCaptor.getValue().iterator().next();
        assertEquals(ScholardexEntityType.AFFILIATION, command.entityType());
        assertEquals(ScholardexSourceLinkService.STATE_UNMATCHED, command.targetState());

        ArgumentCaptor<ScholardexEdgeWriterService.EdgeWriteCommand> edgeCaptor =
                ArgumentCaptor.forClass(ScholardexEdgeWriterService.EdgeWriteCommand.class);
        verify(edgeWriterService).upsertAuthorAffiliationEdge(edgeCaptor.capture());
        assertEquals(ScholardexSourceLinkService.STATE_UNMATCHED, edgeCaptor.getValue().linkState());
        assertTrue(result.getImportedCount() == 1);
    }

    @Test
    void bridgeAffiliationIdsDedupesResolvedAndFallbackAffiliations() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink resolved = new ScholardexSourceLink();
        resolved.setCanonicalEntityId("saff_resolved");

        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> cache =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "sourceLinkCache");
        cache.put(ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS", "af1"), resolved);

        ScholardexAuthorCanonicalizationService.AffiliationBridgeResult bridged =
                ReflectionTestUtils.invokeMethod(
                        service,
                        "bridgeAffiliationIds",
                        List.of("af1", " af1 ", "af2", "af2", "   "),
                        "SCOPUS_JSON_BOOTSTRAP",
                        context
                );

        assertEquals(List.of("saff_resolved", bridged.entries().get(1).canonicalAffiliationId()), bridged.canonicalAffiliationIds());
        assertEquals(List.of("af2"), bridged.pendingSourceAffiliationIds());
        assertEquals(false, bridged.entries().getFirst().pendingResolution());
        assertEquals(true, bridged.entries().get(1).pendingResolution());
    }

    @Test
    void bridgeAffiliationIdsQueuesSyntheticFallbackSourceLinksWithFullMetadata() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");

        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.normalizeSource("SCOPUS")).thenReturn("SCOPUS");

        ScholardexAuthorCanonicalizationService.AffiliationBridgeResult bridged =
                ReflectionTestUtils.invokeMethod(
                        service,
                        "bridgeAffiliationIds",
                        List.of(" 60000434 ", "60000434"),
                        "SCOPUS_JSON_BOOTSTRAP",
                        context
                );

        @SuppressWarnings("unchecked")
        Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pending =
                (Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand>) ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> cache =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "sourceLinkCache");

        assertEquals(1, bridged.canonicalAffiliationIds().size());
        assertEquals(List.of("60000434"), bridged.pendingSourceAffiliationIds());
        assertEquals(1, pending.size());

        ScholardexSourceLinkService.SourceLinkUpsertCommand command = pending.values().iterator().next();
        assertEquals(ScholardexEntityType.AFFILIATION, command.entityType());
        assertEquals("SCOPUS", command.source());
        assertEquals("60000434", command.sourceRecordId());
        assertEquals(ScholardexSourceLinkService.STATE_UNMATCHED, command.targetState());
        assertEquals("canonical-affiliation-fallback", command.reason());
        assertEquals(bridged.canonicalAffiliationIds().getFirst(), command.canonicalEntityId());

        ScholardexSourceLink synthetic = cache.get(
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS", "60000434")
        );
        assertNotNull(synthetic);
        assertEquals(ScholardexEntityType.AFFILIATION, synthetic.getEntityType());
        assertEquals("SCOPUS", synthetic.getSource());
        assertEquals("60000434", synthetic.getSourceRecordId());
        assertEquals(command.canonicalEntityId(), synthetic.getCanonicalEntityId());
        assertEquals(ScholardexSourceLinkService.STATE_UNMATCHED, synthetic.getLinkState());
        assertEquals("canonical-affiliation-fallback", synthetic.getLinkReason());
        assertNotNull(synthetic.getUpdatedAt());
        assertNotNull(synthetic.getLinkedAt());
    }

    @Test
    void processSourceFactQueuesLinkedAndUnmatchedAffiliationEdges() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("111");
        sourceFact.setName("Author");
        sourceFact.setAffiliationIds(List.of("af1", "af2"));
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceEventId("evt-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");

        ScholardexSourceLink resolved = new ScholardexSourceLink();
        resolved.setCanonicalEntityId("saff_resolved");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> cache =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "sourceLinkCache");
        cache.put(ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS", "af1"), resolved);

        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.normalizeSource("SCOPUS")).thenReturn("SCOPUS");

        ImportProcessingResult result = new ImportProcessingResult(10);
        ReflectionTestUtils.invokeMethod(service, "processSourceFact", sourceFact, result, context);

        @SuppressWarnings("unchecked")
        Map<String, ScholardexAuthorFact> pendingAuthorFacts =
                (Map<String, ScholardexAuthorFact>) ReflectionTestUtils.getField(context, "pendingAuthorFacts");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> pendingEdges =
                (Map<String, ScholardexEdgeWriterService.EdgeWriteCommand>) ReflectionTestUtils.getField(context, "pendingEdgeCommands");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingLinks =
                (Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand>) ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");

        assertEquals(1, pendingAuthorFacts.size());
        ScholardexAuthorFact author = pendingAuthorFacts.values().iterator().next();
        assertEquals(List.of("saff_resolved", pendingEdges.values().stream()
                .filter(command -> ScholardexSourceLinkService.STATE_UNMATCHED.equals(command.linkState()))
                .map(ScholardexEdgeWriterService.EdgeWriteCommand::rightId)
                .findFirst()
                .orElseThrow()), author.getAffiliationIds());
        assertEquals(List.of("af2"), author.getPendingAffiliationSourceIds());
        assertEquals(2, pendingEdges.size());
        assertEquals(2, pendingLinks.size());

        List<ScholardexEdgeWriterService.EdgeWriteCommand> edgeCommands = new ArrayList<>(pendingEdges.values());
        ScholardexEdgeWriterService.EdgeWriteCommand linkedEdge = edgeCommands.stream()
                .filter(command -> ScholardexSourceLinkService.STATE_LINKED.equals(command.linkState()))
                .findFirst()
                .orElseThrow();
        ScholardexEdgeWriterService.EdgeWriteCommand unmatchedEdge = edgeCommands.stream()
                .filter(command -> ScholardexSourceLinkService.STATE_UNMATCHED.equals(command.linkState()))
                .findFirst()
                .orElseThrow();
        assertEquals("author-affiliation-bridge", linkedEdge.linkReason());
        assertEquals("saff_resolved", linkedEdge.rightId());
        assertEquals("canonical-affiliation-fallback", unmatchedEdge.linkReason());
        assertEquals("111::affiliation::af2", unmatchedEdge.sourceRecordId());
        assertEquals(1, result.getImportedCount());
    }

    @Test
    void recoverAuthorWriteReusesExistingBySourceAndRewritesPendingLink() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );
        Object context = ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexAuthorFact incoming = new ScholardexAuthorFact();
        incoming.setId("sauth_new");
        incoming.setDisplayName("Incoming");
        incoming.setAlternativeNames(List.of("Incoming Alt"));
        incoming.setAffiliationIds(List.of("saff_1"));
        incoming.setPendingAffiliationSourceIds(List.of("af1"));
        incoming.setSourceRecordId("111");
        incoming.setSource("SCOPUS");
        incoming.setSourceBatchId("batch-1");
        incoming.setSourceCorrelationId("corr-1");

        ScholardexAuthorFact recovered = new ScholardexAuthorFact();
        recovered.setId("sauth_existing");
        recovered.getScopusAuthorIds().add("111");

        @SuppressWarnings("unchecked")
        Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pending =
                (Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand>) ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");
        pending.put(
                "AUTHOR|SCOPUS|111",
                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR, "SCOPUS", "111", "sauth_old",
                        ScholardexSourceLinkService.STATE_LINKED, "reason", "evt", "batch", "corr", false
                )
        );

        when(scholardexAuthorFactRepository.save(incoming))
                .thenThrow(new DuplicateKeyException("dup"));
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsContains("111"))
                .thenReturn(Optional.of(recovered));
        when(scholardexAuthorFactRepository.save(recovered))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "recoverAuthorWrite", incoming, context);

        assertEquals("Incoming", recovered.getDisplayName());
        assertEquals(List.of("Incoming Alt"), recovered.getAlternativeNames());
        assertEquals(List.of("saff_1"), recovered.getAffiliationIds());
        assertEquals(List.of("af1"), recovered.getPendingAffiliationSourceIds());
        assertEquals("SCOPUS", recovered.getSource());
        assertEquals("111", recovered.getSourceRecordId());
        assertEquals("corr-1", recovered.getSourceCorrelationId());
        assertNotNull(recovered.getUpdatedAt());
        assertEquals("sauth_existing", pending.get("AUTHOR|SCOPUS|111").canonicalEntityId());
    }

    @Test
    void processSourceFactSkipsWhenCachedSourceLinkAndExistingAuthorDisagreeOnCanonicalId() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );
        ScholardexAuthorCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "sourceLinkCache");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexAuthorFact> authorBySourceId =
                (Map<String, ScholardexAuthorFact>) ReflectionTestUtils.getField(context, "authorBySourceId");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexIdentityConflict> pendingConflicts =
                (Map<String, ScholardexIdentityConflict>) ReflectionTestUtils.getField(context, "pendingConflicts");

        ScholardexSourceLink sourceLink = new ScholardexSourceLink();
        sourceLink.setCanonicalEntityId("sauth_linked");
        sourceLinkCache.put(
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AUTHOR, "SCOPUS", "111"),
                sourceLink
        );
        ScholardexAuthorFact existingBySource = new ScholardexAuthorFact();
        existingBySource.setId("sauth_existing");
        existingBySource.getScopusAuthorIds().add("111");
        authorBySourceId.put("111", existingBySource);

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("111");
        sourceFact.setName("Collision Author");
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceEventId("evt-collision");
        sourceFact.setSourceBatchId("batch-collision");
        sourceFact.setSourceCorrelationId("corr-collision");

        when(sourceLinkService.normalizeSource("SCOPUS_JSON_UPLOAD")).thenReturn("SCOPUS");
        when(sourceLinkService.normalizeSource("SCOPUS")).thenReturn("SCOPUS");

        ImportProcessingResult result = new ImportProcessingResult(10);
        ReflectionTestUtils.invokeMethod(service, "processSourceFact", sourceFact, result, context);

        assertEquals(1, result.getSkippedCount());
        ScholardexIdentityConflict conflict = pendingConflicts.get("AUTHOR|SCOPUS_JSON_UPLOAD|111|SOURCE_ID_COLLISION");
        assertNotNull(conflict);
        assertEquals(List.of("sauth_linked", "sauth_existing"), conflict.getCandidateCanonicalIds());
        @SuppressWarnings("unchecked")
        Map<String, ScholardexAuthorFact> pendingAuthorFacts =
                (Map<String, ScholardexAuthorFact>) ReflectionTestUtils.getField(context, "pendingAuthorFacts");
        assertTrue(pendingAuthorFacts.isEmpty());
    }

    @Test
    void upsertConflictInContextIgnoresBlankKeysAndReusesExistingConflict() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );
        ScholardexAuthorCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexIdentityConflict> pendingConflicts =
                (Map<String, ScholardexIdentityConflict>) ReflectionTestUtils.getField(context, "pendingConflicts");
        ScholardexIdentityConflict existing = new ScholardexIdentityConflict();
        existing.setDetectedAt(Instant.parse("2024-03-03T00:00:00Z"));

        ReflectionTestUtils.invokeMethod(
                service,
                "upsertConflictInContext",
                "   ",
                "111",
                "evt",
                "batch",
                "corr",
                "SOURCE_ID_COLLISION",
                List.of("sauth_1"),
                context
        );
        assertEquals(0, pendingConflicts.size());

        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                ScholardexEntityType.AUTHOR,
                "SCOPUS",
                "111",
                "SOURCE_ID_COLLISION",
                "OPEN"
        )).thenReturn(Optional.of(existing));

        ReflectionTestUtils.invokeMethod(
                service,
                "upsertConflictInContext",
                "SCOPUS",
                "111",
                "evt-1",
                "batch-1",
                "corr-1",
                "SOURCE_ID_COLLISION",
                null,
                context
        );

        assertEquals(1, pendingConflicts.size());
        ScholardexIdentityConflict conflict = pendingConflicts.get("AUTHOR|SCOPUS|111|SOURCE_ID_COLLISION");
        assertSame(existing, conflict);
        assertEquals(List.of(), conflict.getCandidateCanonicalIds());
        assertEquals("evt-1", conflict.getSourceEventId());
        assertEquals("batch-1", conflict.getSourceBatchId());
        assertEquals("corr-1", conflict.getSourceCorrelationId());
        assertEquals(Instant.parse("2024-03-03T00:00:00Z"), conflict.getDetectedAt());
    }

    @Test
    void rebuildCanonicalAuthorFactsFromScopusFactsUsesDefaultWrapperAndSortsByAuthorId() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );

        ScopusAuthorFact factZzz = new ScopusAuthorFact();
        factZzz.setAuthorId("zzz");
        factZzz.setName("Zed");
        factZzz.setAffiliationIds(List.of());
        factZzz.setSource("SCOPUS");
        ScopusAuthorFact factAaa = new ScopusAuthorFact();
        factAaa.setAuthorId("aaa");
        factAaa.setName("Aye");
        factAaa.setAffiliationIds(List.of());
        factAaa.setSource("SCOPUS");

        when(scopusAuthorFactRepository.findAll()).thenReturn(List.of(factZzz, factAaa));
        when(checkpointService.readCheckpoint(any())).thenReturn(Optional.empty());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), anyCollection()))
                .thenReturn(List.of());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR_AFFILIATION), anyCollection()))
                .thenReturn(List.of());
        when(sourceLinkService.normalizeSource("SCOPUS")).thenReturn("SCOPUS");
        when(scholardexAuthorFactRepository.findByIdIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorAffiliationFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorFactRepository.saveAll(anyCollection()))
                .thenAnswer(invocation -> List.copyOf((java.util.Collection<?>) invocation.getArgument(0)));
        when(sourceLinkService.batchUpsertWithState(anyCollection(), any(), eq(true)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));
        when(edgeWriterService.batchUpsertAuthorAffiliationEdges(anyList(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(0, 0, 0, 0, 0));

        ImportProcessingResult result = service.rebuildCanonicalAuthorFactsFromScopusFacts();

        // facts are now persisted via bulkOps.replaceOne(query, fact, opts) — capture the fact arg
        ArgumentCaptor<Object> factCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bulkOps, times(2)).replaceOne(any(), factCaptor.capture(), any());
        List<ScholardexAuthorFact> saved = factCaptor.getAllValues().stream()
                .map(o -> (ScholardexAuthorFact) o).toList();
        assertEquals("aaa", saved.get(0).getSourceRecordId());
        assertEquals("zzz", saved.get(1).getSourceRecordId());
        assertEquals(2, result.getProcessedCount());
    }

    @Test
    void flushPendingWritesCountsLinkStatesEdgeConflictsAndRecoveryFallback() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                mongoTemplate
        );
        ScholardexAuthorCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexAuthorFact> pendingAuthorFacts =
                (Map<String, ScholardexAuthorFact>) ReflectionTestUtils.getField(context, "pendingAuthorFacts");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexIdentityConflict> pendingConflicts =
                (Map<String, ScholardexIdentityConflict>) ReflectionTestUtils.getField(context, "pendingConflicts");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingSourceLinkCommands =
                (Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand>) ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> pendingEdgeCommands =
                (Map<String, ScholardexEdgeWriterService.EdgeWriteCommand>) ReflectionTestUtils.getField(context, "pendingEdgeCommands");

        ScholardexAuthorFact first = new ScholardexAuthorFact();
        first.setId("sauth_first");
        first.setDisplayName("First");
        first.setSourceRecordId("111");
        first.getScopusAuthorIds().add("111");
        ScholardexAuthorFact second = new ScholardexAuthorFact();
        second.setId("sauth_second");
        second.setDisplayName("Second");
        second.setSourceRecordId("222");
        second.getScopusAuthorIds().add("222");
        ScholardexAuthorFact recovered = new ScholardexAuthorFact();
        recovered.setId("sauth_recovered");
        recovered.getScopusAuthorIds().add("111");

        ScholardexIdentityConflict conflict = new ScholardexIdentityConflict();
        conflict.setEntityType(ScholardexEntityType.AUTHOR);
        conflict.setIncomingSource("SCOPUS");
        conflict.setIncomingSourceRecordId("111");
        conflict.setReasonCode("SOURCE_ID_COLLISION");

        pendingAuthorFacts.put(first.getId(), first);
        pendingAuthorFacts.put(second.getId(), second);
        pendingConflicts.put("AUTHOR|SCOPUS|111|SOURCE_ID_COLLISION", conflict);
        pendingSourceLinkCommands.put(
                "AUTHOR|SCOPUS|111",
                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR, "SCOPUS", "111", "sauth_first",
                        ScholardexSourceLinkService.STATE_LINKED, "reason-linked", "evt-1", "batch-1", "corr-1", false
                )
        );
        pendingSourceLinkCommands.put(
                "AFFILIATION|SCOPUS|af1",
                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AFFILIATION, "SCOPUS", "af1", "saff_1",
                        ScholardexSourceLinkService.STATE_UNMATCHED, "reason-unmatched", null, null, null, false
                )
        );
        pendingSourceLinkCommands.put(
                "AUTHOR|SCOPUS|222",
                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR, "SCOPUS", "222", null,
                        ScholardexSourceLinkService.STATE_CONFLICT, "reason-conflict", null, null, null, false
                )
        );
        pendingSourceLinkCommands.put(
                "AUTHOR|SCOPUS|333",
                new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR, "SCOPUS", "333", null,
                        ScholardexSourceLinkService.STATE_SKIPPED, "reason-skipped", null, null, null, false
                )
        );
        pendingEdgeCommands.put(
                "sauth_first|saff_1|SCOPUS",
                new ScholardexEdgeWriterService.EdgeWriteCommand(
                        "sauth_first", "saff_1", "SCOPUS", "111::affiliation::af1",
                        "evt-1", "batch-1", "corr-1",
                        ScholardexSourceLinkService.STATE_LINKED, "author-affiliation-bridge", false
                )
        );

        when(bulkOps.execute())
                .thenThrow(new DuplicateKeyException("dup"));
        when(scholardexAuthorFactRepository.save(first))
                .thenThrow(new DuplicateKeyException("dup"));
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsContains("111"))
                .thenReturn(Optional.of(recovered));
        when(scholardexAuthorFactRepository.save(recovered))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(scholardexAuthorFactRepository.save(second))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ScholardexSourceLink linked = new ScholardexSourceLink();
        linked.setLinkState(ScholardexSourceLinkService.STATE_LINKED);
        ScholardexSourceLink unmatched = new ScholardexSourceLink();
        unmatched.setLinkState(ScholardexSourceLinkService.STATE_UNMATCHED);
        ScholardexSourceLink conflictLink = new ScholardexSourceLink();
        conflictLink.setLinkState(ScholardexSourceLinkService.STATE_CONFLICT);
        ScholardexSourceLink skipped = new ScholardexSourceLink();
        skipped.setLinkState(ScholardexSourceLinkService.STATE_SKIPPED);
        when(sourceLinkService.batchUpsertWithState(anyCollection(), any(Map.class), eq(true)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of(
                        new ScholardexSourceLinkService.SourceLinkBatchItemResult(pendingSourceLinkCommands.get("AUTHOR|SCOPUS|111"), true, null, linked),
                        new ScholardexSourceLinkService.SourceLinkBatchItemResult(pendingSourceLinkCommands.get("AFFILIATION|SCOPUS|af1"), true, null, unmatched),
                        new ScholardexSourceLinkService.SourceLinkBatchItemResult(pendingSourceLinkCommands.get("AUTHOR|SCOPUS|222"), true, null, conflictLink),
                        new ScholardexSourceLinkService.SourceLinkBatchItemResult(pendingSourceLinkCommands.get("AUTHOR|SCOPUS|333"), true, null, skipped)
                )));
        when(edgeWriterService.batchUpsertAuthorAffiliationEdges(anyList(), any(), any(), eq(true)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(3, 0, 2, 1, 2));

        ReflectionTestUtils.invokeMethod(service, "flushPendingWrites", 0L, 10L, 20L, context);

        verify(identityConflictRepository).saveAll(pendingConflicts.values());
        verify(sourceLinkService).batchUpsertWithState(anyCollection(), any(Map.class), eq(true));
        verify(edgeWriterService).batchUpsertAuthorAffiliationEdges(anyList(), any(), any(), eq(true));
        assertEquals(2, ReflectionTestUtils.getField(context, "lastAuthorFactWrites"));
        assertEquals(1, ReflectionTestUtils.getField(context, "lastSourceLinkLinkedWrites"));
        assertEquals(1, ReflectionTestUtils.getField(context, "lastSourceLinkUnmatchedWrites"));
        assertEquals(1, ReflectionTestUtils.getField(context, "lastSourceLinkConflictWrites"));
        assertEquals(1, ReflectionTestUtils.getField(context, "lastSourceLinkSkippedWrites"));
        assertEquals(3, ReflectionTestUtils.getField(context, "lastEdgeWrites"));
        assertEquals(3, ReflectionTestUtils.getField(context, "lastConflictsWritten"));
        assertEquals("sauth_recovered", pendingSourceLinkCommands.get("AUTHOR|SCOPUS|111").canonicalEntityId());
    }

}
