package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScholardexAuthorCanonicalizationServiceBatchScopeTest {

    @Test
    void batchScopedRebuildLoadsOnlyFactsFromRequestedSourceBatch() {
        ScopusAuthorFactRepository scopusAuthorFactRepository = mock(ScopusAuthorFactRepository.class);
        ScholardexAuthorFactRepository scholardexAuthorFactRepository = mock(ScholardexAuthorFactRepository.class);
        ScholardexAuthorAffiliationFactRepository scholardexAuthorAffiliationFactRepository = mock(ScholardexAuthorAffiliationFactRepository.class);
        ScholardexSourceLinkService sourceLinkService = mock(ScholardexSourceLinkService.class);
        ScholardexIdentityConflictRepository identityConflictRepository = mock(ScholardexIdentityConflictRepository.class);
        ScholardexCanonicalBuildCheckpointService checkpointService = mock(ScholardexCanonicalBuildCheckpointService.class);
        org.springframework.data.mongodb.core.MongoTemplate mongoTemplate = mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        org.springframework.data.mongodb.core.BulkOperations bulkOps = mock(org.springframework.data.mongodb.core.BulkOperations.class);
        when(mongoTemplate.bulkOps(any(), any(Class.class))).thenReturn(bulkOps);
        when(bulkOps.replaceOne(any(), any(), any())).thenReturn(bulkOps);
        ScholardexEdgeWriterService edgeWriterService = mock(ScholardexEdgeWriterService.class);

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("10038760900");
        sourceFact.setName("Author A");
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceBatchId("upload-batch-1");

        when(scopusAuthorFactRepository.findBySourceBatchId("upload-batch-1")).thenReturn(List.of(sourceFact));
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorFactRepository.findByIdIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorAffiliationFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(any(), anyCollection())).thenReturn(List.of());
        when(sourceLinkService.batchUpsertWithState(any(), any(), any(Boolean.class)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));
        when(edgeWriterService.batchUpsertAuthorAffiliationEdges(any(), any(), any(), any(Boolean.class)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(0, 0, 0, 0, 0));

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

        service.rebuildCanonicalAuthorFactsFromScopusFacts(
                new CanonicalBuildOptions(null, null, false, "upload-batch-1", null, false, false)
        );

        verify(scopusAuthorFactRepository).findBySourceBatchId("upload-batch-1");
        verify(scopusAuthorFactRepository, never()).findAll();
    }

    @Test
    void batchScopedReplayUsesFallbackLookupForExistingAuthorSourceLink() {
        ScopusAuthorFactRepository scopusAuthorFactRepository = mock(ScopusAuthorFactRepository.class);
        ScholardexAuthorFactRepository scholardexAuthorFactRepository = mock(ScholardexAuthorFactRepository.class);
        ScholardexAuthorAffiliationFactRepository scholardexAuthorAffiliationFactRepository = mock(ScholardexAuthorAffiliationFactRepository.class);
        ScholardexSourceLinkService sourceLinkService = mock(ScholardexSourceLinkService.class);
        ScholardexIdentityConflictRepository identityConflictRepository = mock(ScholardexIdentityConflictRepository.class);
        ScholardexCanonicalBuildCheckpointService checkpointService = mock(ScholardexCanonicalBuildCheckpointService.class);
        org.springframework.data.mongodb.core.MongoTemplate mongoTemplate = mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        org.springframework.data.mongodb.core.BulkOperations bulkOps = mock(org.springframework.data.mongodb.core.BulkOperations.class);
        when(mongoTemplate.bulkOps(any(), any(Class.class))).thenReturn(bulkOps);
        when(bulkOps.replaceOne(any(), any(), any())).thenReturn(bulkOps);
        ScholardexEdgeWriterService edgeWriterService = mock(ScholardexEdgeWriterService.class);

        ScopusAuthorFact sourceFact = new ScopusAuthorFact();
        sourceFact.setAuthorId("14027901400");
        sourceFact.setName("Replay Author");
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceBatchId("upload-batch-2");

        when(scopusAuthorFactRepository.findBySourceBatchId("upload-batch-2")).thenReturn(List.of(sourceFact));
        when(scholardexAuthorFactRepository.findByScopusAuthorIdsIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorFactRepository.findByIdIn(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorFactRepository.saveAll(anyCollection())).thenReturn(List.of());
        when(scholardexAuthorAffiliationFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(any(), anyCollection())).thenReturn(List.of());
        when(sourceLinkService.findByKey(
                eq(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType.AUTHOR),
                eq("SCOPUS_JSON_UPLOAD"),
                eq("14027901400")
        )).thenReturn(Optional.of(new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink()));
        when(sourceLinkService.batchUpsertWithState(any(), any(Map.class), eq(true)))
                .thenReturn(new ScholardexSourceLinkService.BatchWriteResult(List.of()));
        when(edgeWriterService.batchUpsertAuthorAffiliationEdges(any(), any(), any(), any(Boolean.class)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(0, 0, 0, 0, 0));

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

        service.rebuildCanonicalAuthorFactsFromScopusFacts(
                new CanonicalBuildOptions(null, null, false, "upload-batch-2", null, false, false)
        );

        verify(sourceLinkService).batchUpsertWithState(any(), any(Map.class), eq(true));
    }
}
