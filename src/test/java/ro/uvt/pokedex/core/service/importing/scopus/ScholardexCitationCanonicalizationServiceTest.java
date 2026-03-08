package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(cited, citing));
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());

        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceRecordId("2-s2.0-cited->2-s2.0-citing");
        sourceFact.setCitedEid("2-s2.0-cited");
        sourceFact.setCitingEid("2-s2.0-citing");
        sourceFact.setSourceEventId("evt-1");
        sourceFact.setSourceBatchId("batch-1");
        sourceFact.setSourceCorrelationId("corr-1");
        when(scopusCitationFactRepository.findAll()).thenReturn(List.of(sourceFact));

        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(scholardexCitationFactRepository.findByCitedPublicationIdAndCitingPublicationIdAndSource("spub_1", "spub_2", "SCOPUS_JSON_BOOTSTRAP"))
                .thenReturn(Optional.empty());
        when(scholardexCitationFactRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.rebuildCanonicalCitationFactsFromScopusFacts();

        assertEquals(1, result.getImportedCount());
        assertEquals(0, result.getSkippedCount());
        ArgumentCaptor<ScholardexCitationFact> edgeCaptor = ArgumentCaptor.forClass(ScholardexCitationFact.class);
        verify(scholardexCitationFactRepository).save(edgeCaptor.capture());
        assertEquals("spub_1", edgeCaptor.getValue().getCitedPublicationId());
        assertEquals("spub_2", edgeCaptor.getValue().getCitingPublicationId());
        assertEquals("SCOPUS_JSON_BOOTSTRAP", edgeCaptor.getValue().getSource());

        verify(sourceLinkService).link(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(false));
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

        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        ScopusCitationFact sourceFact = new ScopusCitationFact();
        sourceFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        sourceFact.setSourceRecordId("2-s2.0-missing->2-s2.0-citing");
        sourceFact.setCitedEid("2-s2.0-missing");
        sourceFact.setCitingEid("2-s2.0-citing");
        when(scopusCitationFactRepository.findAll()).thenReturn(List.of(sourceFact));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        var result = service.rebuildCanonicalCitationFactsFromScopusFacts();

        assertEquals(1, result.getSkippedCount());
        verify(scholardexCitationFactRepository, never()).save(any());
        verify(scholardexIdentityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }
}
