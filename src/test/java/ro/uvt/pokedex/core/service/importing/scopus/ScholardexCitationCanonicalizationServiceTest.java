package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Mock
    private ScopusTouchQueueService touchQueueService;

    @Test
    void rebuildCanonicalCitationFactsCreatesCanonicalEdgeAndSourceLink() {
        ScholardexCitationCanonicalizationService service = new ScholardexCitationCanonicalizationService(
                scopusCitationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                checkpointService,
                touchQueueService
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
                checkpointService,
                touchQueueService
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
        verify(scholardexIdentityConflictRepository).saveAll(any());
    }

    private CanonicalBuildOptions fullRescanOptions() {
        return new CanonicalBuildOptions(null, null, true, null, false, false, false, false, true);
    }
}
