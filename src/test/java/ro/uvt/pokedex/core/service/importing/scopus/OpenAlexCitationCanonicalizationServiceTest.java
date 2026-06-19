package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.openalex.OpenAlexClient;
import ro.uvt.pokedex.core.service.openalex.OpenAlexImportService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAlexCitationCanonicalizationServiceTest {

    @Mock private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Mock private OpenAlexImportService openAlexImportService;
    @Mock private OpenAlexClient openAlexClient;
    @Mock private OpenAlexCanonicalizationService openAlexCanonicalizationService;
    @Mock private ScholardexCitationFactRepository citationFactRepository;
    @Mock private ScholardexSourceLinkRepository sourceLinkRepository;

    @InjectMocks private OpenAlexCitationCanonicalizationService service;

    @Test
    void buildsEdgeWhenBothEndpointsAreCanonicalAndSkipsOutOfCorpusRefs() {
        // Citing work W1 cites RinCorpus (canonical) and RoutsideCorpus (no source-link => not canonical).
        OpenAlexPublicationFact citing = fact("W1", "RinCorpus", "RoutsideCorpus");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(citing));
        when(sourceLinkRepository.findByEntityTypeAndSourceRecordIdIn(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of(link("W1", "spub_citing"), link("RinCorpus", "spub_cited")));
        when(citationFactRepository.findById(anyString())).thenReturn(Optional.empty());

        service.rebuildCitationFacts();

        verify(citationFactRepository).save(argThat(c ->
                "spub_citing".equals(c.getCitingPublicationId())
                        && "spub_cited".equals(c.getCitedPublicationId())
                        && "OPENALEX".equals(c.getSource())));
        // Only one edge — RoutsideCorpus had no canonical mapping.
        verify(citationFactRepository).save(any());
    }

    @Test
    void buildsIncomingEdgeFromCitedByWorkIdsEvenWhenCitersReferenceListOmitsThePaper() {
        // The synced paper W1 knows (via per-paper cites:) that Wciter cites it — but Wciter is a bare neighbor with
        // an empty reference list, so ONLY citedByWorkIds can produce the incoming edge.
        OpenAlexPublicationFact mine = fact("W1");
        mine.setCitedByWorkIds(new java.util.ArrayList<>(List.of("Wciter")));
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(mine));
        when(sourceLinkRepository.findByEntityTypeAndSourceRecordIdIn(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of(link("W1", "spub_mine"), link("Wciter", "spub_citer")));
        when(citationFactRepository.findById(anyString())).thenReturn(Optional.empty());

        service.rebuildCitationFacts();

        verify(citationFactRepository).save(argThat(c ->
                "spub_citer".equals(c.getCitingPublicationId())
                        && "spub_mine".equals(c.getCitedPublicationId())
                        && "OPENALEX".equals(c.getSource())));
    }

    @Test
    void skipsWhenCitingWorkIsNotCanonical() {
        OpenAlexPublicationFact citing = fact("W9", "R1");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(citing));
        when(sourceLinkRepository.findByEntityTypeAndSourceRecordIdIn(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of(link("R1", "spub_r1"))); // W9 itself has no canonical link

        service.rebuildCitationFacts();

        verify(citationFactRepository, never()).save(any());
    }

    @Test
    void skipsSelfCitation() {
        OpenAlexPublicationFact citing = fact("W1", "Rself");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(citing));
        // Both the work and its reference resolve to the SAME canonical pub (a metadata duplicate) -> no self-loop.
        when(sourceLinkRepository.findByEntityTypeAndSourceRecordIdIn(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of(link("W1", "spub_same"), link("Rself", "spub_same")));

        service.rebuildCitationFacts();

        verify(citationFactRepository, never()).save(any());
    }

    private OpenAlexPublicationFact fact(String workId, String... referencedWorks) {
        OpenAlexPublicationFact f = new OpenAlexPublicationFact();
        f.setSourceRecordId(workId);
        f.setReferencedWorks(new java.util.ArrayList<>(List.of(referencedWorks)));
        return f;
    }

    private ScholardexSourceLink link(String workId, String canonicalId) {
        ScholardexSourceLink l = new ScholardexSourceLink();
        l.setEntityType(ScholardexEntityType.PUBLICATION);
        l.setSource("OPENALEX");
        l.setSourceRecordId(workId);
        l.setCanonicalEntityId(canonicalId);
        return l;
    }
}
