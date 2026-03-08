package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexEdgeReconciliationServiceTest {

    @Mock
    private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock
    private ScholardexAuthorFactRepository authorFactRepository;
    @Mock
    private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Mock
    private ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock
    private ScholardexEdgeWriterService edgeWriterService;

    @Test
    void reconcileEdgesCreatesMissingAuthorshipEdgeFromPublicationArray() {
        ScholardexEdgeReconciliationService service = new ScholardexEdgeReconciliationService(
                publicationFactRepository,
                authorFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                identityConflictRepository,
                edgeWriterService
        );

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-1");
        publication.setAuthorIds(List.of("auth-1"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId("rec-1");

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-1", "auth-1", "SCOPUS"))
                .thenReturn(Optional.empty());
        when(authorshipFactRepository.findByPublicationId("pub-1")).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-1", true));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(1, result.getUpdatedCount());
        verify(edgeWriterService).upsertAuthorshipEdge(any());
    }

    @Test
    void reconcileEdgesPrunesStaleAuthorshipEdgeWhenSourceMatches() {
        ScholardexEdgeReconciliationService service = new ScholardexEdgeReconciliationService(
                publicationFactRepository,
                authorFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                identityConflictRepository,
                edgeWriterService
        );

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("pub-1");
        publication.setAuthorIds(List.of("auth-1"));
        publication.setSource("SCOPUS");
        publication.setSourceRecordId("rec-1");

        ScholardexAuthorshipFact staleEdge = new ScholardexAuthorshipFact();
        staleEdge.setId("edge-stale");
        staleEdge.setPublicationId("pub-1");
        staleEdge.setAuthorId("auth-2");
        staleEdge.setSource("SCOPUS");

        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(authorshipFactRepository.findByPublicationIdAndAuthorIdAndSource("pub-1", "auth-1", "SCOPUS"))
                .thenReturn(Optional.of(new ScholardexAuthorshipFact()));
        when(authorshipFactRepository.findByPublicationId("pub-1")).thenReturn(List.of(staleEdge));
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(edgeWriterService.upsertAuthorshipEdge(any()))
                .thenReturn(ScholardexEdgeWriterService.EdgeWriteResult.accepted("edge-1", false));

        ImportProcessingResult result = service.reconcileEdges();

        assertEquals(2, result.getUpdatedCount());
        verify(authorshipFactRepository).delete(staleEdge);
        verify(edgeWriterService).upsertAuthorshipEdge(any());
    }
}
