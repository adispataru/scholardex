package ro.uvt.pokedex.core.service.dblp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.dblp.dto.DblpSearchResponse;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DblpConferenceResolveServiceTest {

    @Mock private DblpConferenceCandidateDetector candidateDetector;
    @Mock private DblpClient dblpClient;
    @Mock private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock private ScholardexForumFactRepository forumFactRepository;
    @Mock private ScholardexPublicationDblpEvidenceRepository evidenceRepository;
    @Mock private ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository sourceLinkRepository;

    @InjectMocks private DblpConferenceResolveService service;

    @Test
    void resolvesByDoiThenWritesEvidenceMintsForumAndStampsForumId() {
        ScholardexPublicationFact pub = pub("p1", "10.1109/ispdc.2017.18");
        when(candidateDetector.detect(any())).thenReturn(List.of(pub));
        when(dblpClient.search("10.1109/ispdc.2017.18")).thenReturn(List.of(
                hit("conf/ispdc/Filelis-Papadopoulos17", "ISPDC", "10.1109/ISPDC.2017.18", "2017")));
        when(forumFactRepository.findByDblpIdsContaining("conf/ispdc")).thenReturn(Optional.empty());
        when(evidenceRepository.findByPublicationId("p1")).thenReturn(Optional.empty());

        service.resolve(List.of(pub));

        // scorer-compatible evidence (conferenceName + series), API-sourced.
        verify(evidenceRepository).save(argThat(e ->
                "p1".equals(e.getPublicationId()) && "ISPDC".equals(e.getConferenceName())
                        && "conf/ispdc".equals(e.getSeries()) && "api".equals(e.getDumpVersion())));
        // conference-series forum minted, keyed by the stream key (no ISSN).
        verify(forumFactRepository).save(argThat(f ->
                f.getDblpIds().contains("conf/ispdc") && "ISPDC".equals(f.getName())));
        // forumId stamped on the publication.
        verify(publicationFactRepository).save(argThat(p -> p.getForumId() != null && p.getForumId().startsWith("sforum_")));
    }

    @Test
    void reusesAnExistingConferenceForumInsteadOfMintingADuplicate() {
        ScholardexPublicationFact pub = pub("p2", "10.1109/ispdc.2017.99");
        ScholardexForumFact existing = new ScholardexForumFact();
        existing.setId("sforum_existing");
        existing.setName("ISPDC");
        existing.setDblpIds(new java.util.ArrayList<>(List.of("conf/ispdc")));
        when(candidateDetector.detect(any())).thenReturn(List.of(pub));
        when(dblpClient.search("10.1109/ispdc.2017.99")).thenReturn(List.of(
                hit("conf/ispdc/Author17", "ISPDC", "10.1109/ISPDC.2017.99", "2017")));
        when(forumFactRepository.findByDblpIdsContaining("conf/ispdc")).thenReturn(Optional.of(existing));
        when(evidenceRepository.findByPublicationId("p2")).thenReturn(Optional.empty());

        service.resolve(List.of(pub));

        verify(forumFactRepository, never()).save(any()); // no new forum: name already set, existing reused
        verify(publicationFactRepository).save(argThat(p -> "sforum_existing".equals(p.getForumId())));
    }

    @Test
    void skipsWhenDblpHasNoConferenceMatch() {
        ScholardexPublicationFact pub = pub("p3", "10.1/unknown");
        when(candidateDetector.detect(any())).thenReturn(List.of(pub));
        when(dblpClient.search(anyString())).thenReturn(List.of()); // no hits by DOI or title

        service.resolve(List.of(pub));

        verify(evidenceRepository, never()).save(any());
        verify(forumFactRepository, never()).save(any());
        verify(publicationFactRepository, never()).save(any());
    }

    @Test
    void rebuildFromEvidenceRelinksForumsWithoutHittingTheApi() {
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence ev =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence();
        ev.setPublicationId("p9");
        ev.setSeries("conf/iccs");
        ev.setConferenceName("ICCS");
        when(evidenceRepository.findAll()).thenReturn(List.of(ev));
        when(forumFactRepository.findByDblpIdsContaining("conf/iccs")).thenReturn(Optional.empty());
        when(publicationFactRepository.findById("p9")).thenReturn(Optional.of(pub("p9", null)));

        service.rebuildFromEvidence();

        verify(forumFactRepository).save(argThat(f -> f.getDblpIds().contains("conf/iccs")));
        verify(publicationFactRepository).save(argThat(p -> p.getForumId() != null && p.getForumId().startsWith("sforum_")));
        verifyNoInteractions(dblpClient); // durability path is API-free
    }

    private ScholardexPublicationFact pub(String id, String doi) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        p.setDoi(doi);
        p.setDoiNormalized(doi);
        p.setTitle("A Paper");
        return p;
    }

    private DblpSearchResponse.DblpInfo hit(String key, String venue, String doi, String year) {
        DblpSearchResponse.DblpInfo info = new DblpSearchResponse.DblpInfo();
        info.setKey(key);
        info.setVenue(venue);
        info.setDoi(doi);
        info.setYear(year);
        info.setTitle("A Paper");
        return info;
    }
}
