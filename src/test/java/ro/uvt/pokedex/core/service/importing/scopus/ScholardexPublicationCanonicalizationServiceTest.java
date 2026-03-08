package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexPublicationCanonicalizationServiceTest {

    @Mock
    private ScopusPublicationFactRepository scopusPublicationFactRepository;
    @Mock
    private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock
    private ScholardexSourceLinkRepository scholardexSourceLinkRepository;

    private ScholardexPublicationCanonicalizationService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexPublicationCanonicalizationService(
                scopusPublicationFactRepository,
                scholardexPublicationFactRepository,
                scholardexSourceLinkRepository
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

        when(scopusPublicationFactRepository.findAll()).thenReturn(List.of(scopusFact));
        when(scholardexPublicationFactRepository.findByEid("2-s2.0-abc")).thenReturn(Optional.empty());
        when(scholardexSourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(any(), any(), any())).thenReturn(Optional.empty());

        ImportProcessingResult result = service.rebuildCanonicalPublicationFactsFromScopusFacts();

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        verify(scholardexPublicationFactRepository).save(any(ScholardexPublicationFact.class));
        verify(scholardexSourceLinkRepository).save(any());
    }
}
