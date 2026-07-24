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
    void mintedForumIsNamedAfterTheStreamAcronymNotTheFirstVolumeTitle() {
        // Regression: conf/aina's forum was minted as "AINA Workshops" because a WAINA-era paper arrived
        // first, which made the scorer workshop-reduce every main-track AINA paper on the stream. The
        // stream-level forum must carry the stream acronym, never a volume-level title.
        ScholardexPublicationFact pub = pub("p4", "10.1007/978-3-031-57931-8_19");
        when(candidateDetector.detect(any())).thenReturn(List.of(pub));
        when(dblpClient.search("10.1007/978-3-031-57931-8_19")).thenReturn(List.of(
                hit("conf/aina/MunteanuPI24", "AINA Workshops", "10.1007/978-3-031-57931-8_19", "2024")));
        when(forumFactRepository.findByDblpIdsContaining("conf/aina")).thenReturn(Optional.empty());
        when(evidenceRepository.findByPublicationId("p4")).thenReturn(Optional.empty());

        service.resolve(List.of(pub));

        // evidence keeps the per-paper volume title (the scorer's per-paper truth)…
        verify(evidenceRepository).save(argThat(e -> "AINA Workshops".equals(e.getConferenceName())));
        // …but the shared stream forum is named from the stream key.
        verify(forumFactRepository).save(argThat(f ->
                f.getDblpIds().contains("conf/aina") && "AINA".equals(f.getName())));
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

    @Test
    void restampPreservesTheDisplacedSourceForumAsOriginalForumId() {
        // H85: the pub arrives with its source-derived per-year proceedings forum; the re-stamp onto the
        // conf/X stream must preserve it so scoring can still read publisher signals ("IEEE/ACM …").
        ScholardexPublicationFact pub = pub("p10", "10.1109/ucc.2013.12");
        pub.setForumId("sforum_raw_ucc2013");
        when(candidateDetector.detect(any())).thenReturn(List.of(pub));
        when(dblpClient.search("10.1109/ucc.2013.12")).thenReturn(List.of(
                hit("conf/ucc/Author13", "UCC", "10.1109/UCC.2013.12", "2013")));
        when(forumFactRepository.findByDblpIdsContaining("conf/ucc")).thenReturn(Optional.empty());
        when(evidenceRepository.findByPublicationId("p10")).thenReturn(Optional.empty());

        service.resolve(List.of(pub));

        verify(publicationFactRepository).save(argThat(p ->
                p.getForumId() != null && p.getForumId().startsWith("sforum_")
                        && "sforum_raw_ucc2013".equals(p.getOriginalForumId())));
    }

    @Test
    void repeatRestampWithTheSameStreamForumKeepsTheEarlierCapture() {
        // Idempotency: a second rebuildFromEvidence pass sees forumId already on the stream forum —
        // originalForumId must keep the raw venue, not get clobbered with the stream id.
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence ev =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence();
        ev.setPublicationId("p11");
        ev.setSeries("conf/ucc");
        ev.setConferenceName("UCC");
        ScholardexForumFact stream = new ScholardexForumFact();
        stream.setId("sforum_stream_ucc");
        stream.setName("UCC");
        stream.setDblpIds(new java.util.ArrayList<>(List.of("conf/ucc")));
        ScholardexPublicationFact pub = pub("p11", null);
        pub.setForumId("sforum_stream_ucc");
        pub.setOriginalForumId("sforum_raw_ucc2013");
        when(evidenceRepository.findAll()).thenReturn(List.of(ev));
        when(forumFactRepository.findByDblpIdsContaining("conf/ucc")).thenReturn(Optional.of(stream));
        when(publicationFactRepository.findById("p11")).thenReturn(Optional.of(pub));

        service.rebuildFromEvidence();

        verify(publicationFactRepository).save(argThat(p ->
                "sforum_stream_ucc".equals(p.getForumId())
                        && "sforum_raw_ucc2013".equals(p.getOriginalForumId())));
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
