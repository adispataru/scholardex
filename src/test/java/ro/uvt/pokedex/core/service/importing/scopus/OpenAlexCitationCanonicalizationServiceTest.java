package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexWorkDoiRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.openalex.OpenAlexClient;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

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
    @Mock private OpenAlexWorkDoiRepository workDoiRepository;
    @Mock private OpenAlexClient openAlexClient;
    @Mock private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock private ScholardexCitationFactRepository citationFactRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;

    @InjectMocks private OpenAlexCitationCanonicalizationService service;

    @Test
    void writesEdgeForAReferencedWorkInTheCorpusAndSkipsOnesOutside() {
        OpenAlexPublicationFact citing = new OpenAlexPublicationFact();
        citing.setSourceRecordId("W1");
        citing.setReferencedWorks(new java.util.ArrayList<>(List.of("RinCorpus", "RoutsideCorpus")));
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(citing));

        // DOI cache: RinCorpus is cached; RoutsideCorpus is missing -> fetched.
        when(workDoiRepository.findByIdIn(any())).thenReturn(List.of(workDoi("RinCorpus", "10.1/cited")));
        when(workDoiRepository.findById("RoutsideCorpus")).thenReturn(Optional.empty());
        when(openAlexClient.fetchWorksByIds(any())).thenReturn(List.of(work("RoutsideCorpus", "10.1/outside")));

        // The citing work resolves to its canonical pub via the OPENALEX source-link.
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setCanonicalEntityId("spub_citing");
        when(sourceLinkService.findByKey(ScholardexEntityType.PUBLICATION, "OPENALEX", "W1")).thenReturn(Optional.of(link));

        // RinCorpus's DOI matches one canonical pub; RoutsideCorpus matches none.
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/cited"))
                .thenReturn(List.of(pub("spub_cited")));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/outside")).thenReturn(List.of());
        when(citationFactRepository.findById(anyString())).thenReturn(Optional.empty());

        service.rebuildCitationFacts();

        // Edge written for the in-corpus reference: citing W1 -> cited spub_cited.
        verify(citationFactRepository).save(argThat(c ->
                "spub_citing".equals(c.getCitingPublicationId())
                        && "spub_cited".equals(c.getCitedPublicationId())
                        && "OPENALEX".equals(c.getSource())));
        // The missing referenced DOI was fetched and cached.
        verify(workDoiRepository).save(argThat(d -> "RoutsideCorpus".equals(d.getId()) && "10.1/outside".equals(d.getDoi())));
    }

    @Test
    void skipsWhenTheCitingWorkHasNoCanonicalPublication() {
        OpenAlexPublicationFact citing = new OpenAlexPublicationFact();
        citing.setSourceRecordId("W9");
        citing.setReferencedWorks(new java.util.ArrayList<>(List.of("R1")));
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(citing));
        when(workDoiRepository.findByIdIn(any())).thenReturn(List.of(workDoi("R1", "10.1/x")));
        when(sourceLinkService.findByKey(ScholardexEntityType.PUBLICATION, "OPENALEX", "W9")).thenReturn(Optional.empty());

        service.rebuildCitationFacts();

        verify(citationFactRepository, never()).save(any());
    }

    private ScholardexPublicationFact pub(String id) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        return p;
    }

    private ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexWorkDoi workDoi(String id, String doi) {
        ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexWorkDoi d = new ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexWorkDoi();
        d.setId(id);
        d.setDoi(doi);
        return d;
    }

    private OpenAlexWorksResponse.OpenAlexWork work(String id, String doi) {
        OpenAlexWorksResponse.OpenAlexWork w = new OpenAlexWorksResponse.OpenAlexWork();
        w.setId("https://openalex.org/" + id);
        w.setDoi(doi);
        return w;
    }
}
