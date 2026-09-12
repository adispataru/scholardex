package ro.uvt.pokedex.core.service.crossref;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** H106 S6 — the sweep asks Crossref for the SERIES of every Springer-ISBN paper and stores it beside the volume. */
class CrossrefVolumeEnrichmentServiceTest {

    private final CrossrefClient client = mock(CrossrefClient.class);
    private final ScholardexPublicationFactRepository pubs = mock(ScholardexPublicationFactRepository.class);
    private final ScholardexPublicationDblpEvidenceRepository evidence = mock(ScholardexPublicationDblpEvidenceRepository.class);
    private final CrossrefVolumeEnrichmentService service = new CrossrefVolumeEnrichmentService(client, pubs, evidence);

    @Test
    void everySpringerPaperIsACandidateWhateverItsForumOrDblpState() {
        ScholardexPublicationFact restamped = pub("spub_esocc", "10.1007/978-3-031-1_1", "sforum_esocc");
        ScholardexPublicationFact forumless = pub("spub_citing", "https://doi.org/10.1007/978-3-642-35326-0_26", null);
        ScholardexPublicationFact journal = pub("spub_j", "10.1016/j.csi.2018.05.003", "sforum_j");
        when(pubs.findAll()).thenReturn(List.of(restamped, forumless, journal));
        ScholardexPublicationDblpEvidence dblpNamed = new ScholardexPublicationDblpEvidence();
        dblpNamed.setPublicationId("spub_esocc");
        dblpNamed.setSeries("conf/esocc");
        when(evidence.findByPublicationId("spub_esocc")).thenReturn(Optional.of(dblpNamed));
        when(evidence.findByPublicationId("spub_citing")).thenReturn(Optional.empty());
        when(client.containerTitles("10.1007/978-3-031-1_1"))
                .thenReturn(Optional.of(new CrossrefClient.ContainerTitles("Lecture Notes in Computer Science", "Service-Oriented and Cloud Computing")));
        when(client.containerTitles("https://doi.org/10.1007/978-3-642-35326-0_26"))
                .thenReturn(Optional.of(new CrossrefClient.ContainerTitles("Communications in Computer and Information Science", "Advanced Machine Learning Technologies and Applications")));

        ImportProcessingResult result = service.sweep(false, 0);

        assertThat(result.getProcessedCount()).isEqualTo(2);
        assertThat(result.getImportedCount()).isEqualTo(2);
        verify(client, never()).containerTitles("10.1016/j.csi.2018.05.003");
        ArgumentCaptor<ScholardexPublicationDblpEvidence> saved = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidence, times(2)).save(saved.capture());
        ScholardexPublicationDblpEvidence esocc = saved.getAllValues().get(0);
        assertThat(esocc.getSeries()).isEqualTo("conf/esocc");          // DBLP's field untouched
        assertThat(esocc.getCrossrefSeries()).isEqualTo("Lecture Notes in Computer Science");
        assertThat(esocc.getVolumeTitle()).isEqualTo("Service-Oriented and Cloud Computing");
        assertThat(esocc.getCrossrefSeriesCheckedAt()).isNotNull();
        ScholardexPublicationDblpEvidence ccis = saved.getAllValues().get(1);
        assertThat(ccis.getPublicationId()).isEqualTo("spub_citing");
        assertThat(ccis.getCrossrefSeries()).isEqualTo("Communications in Computer and Information Science");
        assertThat(ccis.getMatchMethod()).isEqualTo("crossref-volume");
    }

    @Test
    void h92RowsAreAskedOnceForTheirSeriesAndSettledRowsAreLeftAlone() {
        ScholardexPublicationFact h92 = pub("spub_h92", "10.1007/978-3-032-23335-6_20", "sforum_s");
        ScholardexPublicationFact done = pub("spub_done", "10.1007/978-3-032-19105-2_17", "sforum_s");
        ScholardexPublicationFact missed = pub("spub_miss", "10.1007/978-3-031-96099-4_3", "sforum_s");
        when(pubs.findAll()).thenReturn(List.of(h92, done, missed));
        ScholardexPublicationDblpEvidence h92Row = new ScholardexPublicationDblpEvidence();
        h92Row.setPublicationId("spub_h92");
        h92Row.setVolumeTitle("Advanced Information Networking and Applications");
        h92Row.setCrossrefCheckedAt(Instant.now());                       // H92 era: volume only, never asked for the series
        ScholardexPublicationDblpEvidence doneRow = new ScholardexPublicationDblpEvidence();
        doneRow.setPublicationId("spub_done");
        doneRow.setCrossrefSeries("Communications in Computer and Information Science");
        doneRow.setVolumeTitle("Machine Learning and Principles and Practice of Knowledge Discovery in Databases");
        doneRow.setCrossrefSeriesCheckedAt(Instant.now());
        ScholardexPublicationDblpEvidence missRow = new ScholardexPublicationDblpEvidence();
        missRow.setPublicationId("spub_miss");
        missRow.setCrossrefSeriesCheckedAt(Instant.now());                // asked, Crossref had nothing
        when(evidence.findByPublicationId("spub_h92")).thenReturn(Optional.of(h92Row));
        when(evidence.findByPublicationId("spub_done")).thenReturn(Optional.of(doneRow));
        when(evidence.findByPublicationId("spub_miss")).thenReturn(Optional.of(missRow));
        when(client.containerTitles("10.1007/978-3-032-23335-6_20"))
                .thenReturn(Optional.of(new CrossrefClient.ContainerTitles("Lecture Notes on Data Engineering and Communications Technologies", null)));

        assertThat(service.countCandidates()).isEqualTo(1);
        ImportProcessingResult result = service.sweep(false, 0);

        assertThat(result.getProcessedCount()).isEqualTo(1);
        verify(client, never()).containerTitles("10.1007/978-3-032-19105-2_17");
        verify(client, never()).containerTitles("10.1007/978-3-031-96099-4_3");
        // A record with a series but no volume keeps the volume H92 stored.
        assertThat(h92Row.getCrossrefSeries()).isEqualTo("Lecture Notes on Data Engineering and Communications Technologies");
        assertThat(h92Row.getVolumeTitle()).isEqualTo("Advanced Information Networking and Applications");

        // recheckEmpty re-asks the incomplete rows (missed series), not the settled one.
        when(client.containerTitles(any())).thenReturn(Optional.empty());
        ImportProcessingResult recheck = service.sweep(true, 0, true);
        assertThat(recheck.getProcessedCount()).isEqualTo(1);            // spub_miss only (h92 now has its series)
    }

    private static ScholardexPublicationFact pub(String id, String doi, String forumId) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        p.setDoi(doi);
        p.setForumId(forumId);
        p.setTitle("T " + id);
        return p;
    }
}
