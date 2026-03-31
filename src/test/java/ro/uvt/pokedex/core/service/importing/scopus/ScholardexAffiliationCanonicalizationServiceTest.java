package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ScholardexSourceLinkService.SourceLinkUpsertCommand>> commandCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sourceLinkService).batchUpsertWithState(commandCaptor.capture(), any(), eq(false));
        ScholardexSourceLinkService.SourceLinkUpsertCommand command = commandCaptor.getValue().iterator().next();
        assertEquals("saff_existing", command.canonicalEntityId());
        assertEquals(1, result.getImportedCount());
    }
}
