package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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

    @Test
    void upsertFromScopusFactRecoversDuplicateScopusAuthorIdByReusingExistingCanonicalRecord() {
        ScholardexAuthorCanonicalizationService service = new ScholardexAuthorCanonicalizationService(
                scopusAuthorFactRepository,
                scholardexAuthorFactRepository,
                scholardexAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService
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
        when(scholardexAuthorFactRepository.saveAll(anyCollection()))
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
                checkpointService
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
                checkpointService
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
        assertIterableEquals(List.of("Spataru, Adrian", "A. Spataru", "Adrian Spataru"), saved.getAlternativeNames());
    }

}
