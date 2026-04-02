package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DblpPublicationEnrichmentServiceTest {

    @Mock
    private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private ScholardexPublicationDblpEvidenceRepository evidenceRepository;

    private DblpPublicationEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new DblpPublicationEnrichmentService(
                publicationFactRepository,
                scholardexProjectionReadService,
                evidenceRepository
        );
    }

    @Test
    void streamsGzipAndPersistsExactDoiMatch(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", "10.1000/test", "Edge Clouds", "2024-05-01", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes in Computer Science")));
        when(evidenceRepository.findByPublicationId("spub_1")).thenReturn(Optional.empty());

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>Edge Clouds</title>
                    <year>2024</year>
                    <doi>10.1000/test</doi>
                    <booktitle>International Conference on Edge Clouds</booktitle>
                    <series>Lecture Notes in Computer Science</series>
                    <ee>https://doi.org/10.1000/test</ee>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.candidatesConsidered()).isEqualTo(1);
        assertThat(summary.recordsScanned()).isEqualTo(1);
        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.updated()).isEqualTo(0);
        assertThat(summary.unmatched()).isEqualTo(0);

        ArgumentCaptor<ScholardexPublicationDblpEvidence> captor = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        ScholardexPublicationDblpEvidence saved = captor.getValue();
        assertThat(saved.getPublicationId()).isEqualTo("spub_1");
        assertThat(saved.getDblpKey()).isEqualTo("books/lncs/Test2024");
        assertThat(saved.getMatchMethod()).isEqualTo("DOI_EXACT");
        assertThat(saved.getConferenceName()).isEqualTo("International Conference on Edge Clouds");
    }

    @Test
    void sanitizesNamedEntitiesWhileStreaming(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", null, "Muller Scheduling", "2024-05-01", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes in Computer Science")));
        when(evidenceRepository.findByPublicationId("spub_1")).thenReturn(Optional.empty());

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>M&uuml;ller Scheduling</title>
                    <year>2024</year>
                    <booktitle>International Symposium on M&uuml;ller Systems</booktitle>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.matched()).isEqualTo(1);
        ArgumentCaptor<ScholardexPublicationDblpEvidence> captor = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Muller");
        assertThat(captor.getValue().getConferenceName()).contains("Muller");
    }

    @Test
    void unknownNamedEntitiesDoNotAbortStreaming(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", null, "Cloud Systems", "2024-05-01", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes in Computer Science")));
        when(evidenceRepository.findByPublicationId("spub_1")).thenReturn(Optional.empty());

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>Cloud&reg; Systems</title>
                    <year>2024</year>
                    <booktitle>International Cloud&unknown; Symposium</booktitle>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.matched()).isEqualTo(1);
        ArgumentCaptor<ScholardexPublicationDblpEvidence> captor = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Cloud");
        assertThat(captor.getValue().getConferenceName()).contains("International Cloud");
    }

    @Test
    void strayAmpersandsDoNotAbortStreaming(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", null, "Cloud Systems", "2024-05-01", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes in Computer Science")));
        when(evidenceRepository.findByPublicationId("spub_1")).thenReturn(Optional.empty());

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>Cloud & Systems</title>
                    <year>2024</year>
                    <booktitle>International Cloud & Systems Symposium</booktitle>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.matched()).isEqualTo(1);
        ArgumentCaptor<ScholardexPublicationDblpEvidence> captor = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("Cloud");
        assertThat(captor.getValue().getConferenceName()).contains("International Cloud");
    }

    @Test
    void builtinXmlEntitiesRemainParseable(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", null, "Cloud Systems", "2024-05-01", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes in Computer Science")));
        when(evidenceRepository.findByPublicationId("spub_1")).thenReturn(Optional.empty());

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>Cloud &amp; Systems</title>
                    <year>2024</year>
                    <booktitle>International Cloud &amp; Systems Symposium</booktitle>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.matched()).isEqualTo(1);
        ArgumentCaptor<ScholardexPublicationDblpEvidence> captor = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).contains("&");
        assertThat(captor.getValue().getConferenceName()).contains("&");
    }

    @Test
    void fallsBackToExactTitleYearMatchWhenDoiMissing(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", null, "Efficient Federated Scheduling", "2025-03-15", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes on Data Engineering and Communications Technologies")));
        when(evidenceRepository.findByPublicationId("spub_1")).thenReturn(Optional.empty());

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lnde/Test2025">
                    <title>Efficient Federated Scheduling</title>
                    <year>2025</year>
                    <booktitle>International Symposium on Scheduling</booktitle>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.matched()).isEqualTo(1);
        ArgumentCaptor<ScholardexPublicationDblpEvidence> captor = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        assertThat(captor.getValue().getMatchMethod()).isEqualTo("TITLE_YEAR_EXACT");
    }

    @Test
    void ignoresNonLectureNotesCandidates(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", "10.1000/test", "Edge Clouds", "2024-05-01", "forum_other");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_other", "Random Proceedings")));

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>Edge Clouds</title>
                    <year>2024</year>
                    <doi>10.1000/test</doi>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.candidatesConsidered()).isEqualTo(0);
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void leavesPublicationUnmatchedWhenNoStrictMatchExists(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", null, "Edge Clouds", "2024-05-01", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes in Computer Science")));

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>Different Title</title>
                    <year>2024</year>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.unmatched()).isEqualTo(1);
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void marksAmbiguousLocalMatchesAsConflict(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact first = candidatePublication("spub_1", null, "Shared Title", "2024-05-01", "forum_ln");
        ScholardexPublicationFact second = candidatePublication("spub_2", null, "Shared Title", "2024-09-01", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(first, second));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes in Computer Science")));

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>Shared Title</title>
                    <year>2024</year>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.conflicts()).isEqualTo(2);
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void rerunUpdatesExistingEvidenceInPlace(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_1", "10.1000/test", "Edge Clouds", "2024-05-01", "forum_ln");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("forum_ln", "Lecture Notes in Computer Science")));

        ScholardexPublicationDblpEvidence existing = new ScholardexPublicationDblpEvidence();
        existing.setId("evidence_1");
        existing.setPublicationId("spub_1");
        existing.setDblpKey("books/lncs/Test2024");
        existing.setDumpVersion("feb-2026");
        existing.setMatchMethod("DOI_EXACT");
        existing.setDoi("10.1000/test");
        existing.setTitle("Edge Clouds");
        existing.setYear(2024);
        existing.setBooktitle("Old Conference Name");
        existing.setConferenceName("Old Conference Name");
        existing.setCreatedAt(Instant.parse("2026-03-01T00:00:00Z"));
        when(evidenceRepository.findByPublicationId("spub_1")).thenReturn(Optional.of(existing));

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <incollection key="books/lncs/Test2024">
                    <title>Edge Clouds</title>
                    <year>2024</year>
                    <doi>10.1000/test</doi>
                    <booktitle>New Conference Name</booktitle>
                  </incollection>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.matched()).isEqualTo(0);
        assertThat(summary.updated()).isEqualTo(1);
        ArgumentCaptor<ScholardexPublicationDblpEvidence> captor = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("evidence_1");
        assertThat(captor.getValue().getDumpVersion()).isEqualTo("march-2026");
        assertThat(captor.getValue().getConferenceName()).isEqualTo("New Conference Name");
    }

    @Test
    void configuredRunFailsClearlyWhenDumpPathMissing() throws Exception {
        setField("dblpFilePath", "");

        assertThatThrownBy(() -> service.runConfiguredEnrichment())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("general.init.dblp.file");
    }

    @Test
    void includesLectureNotesConferencePapersFromProjectionForums(@TempDir Path tempDir) throws Exception {
        ScholardexPublicationFact publication = candidatePublication("spub_cp", "10.1007/978-3-319-49583-5_11",
                "Online resource coalition reorganization for efficient scheduling on the intercloud",
                "2016-01-01", "25674");
        publication.setSubtype("cp");
        when(publicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(lectureNotesForum("25674", "Lecture Notes in Computer Science")));
        when(evidenceRepository.findByPublicationId("spub_cp")).thenReturn(Optional.empty());

        Path dump = writeGzipXml(tempDir.resolve("dblp.xml.gz"), """
                <dblp>
                  <inproceedings key="conf/ccgrid/FortunatoF16">
                    <title>Online resource coalition reorganization for efficient scheduling on the intercloud</title>
                    <year>2016</year>
                    <doi>10.1007/978-3-319-49583-5_11</doi>
                    <booktitle>Proceedings of the 16th IEEE/ACM International Symposium on Cluster, Cloud and Grid Computing</booktitle>
                    <series>Lecture Notes in Computer Science</series>
                  </inproceedings>
                </dblp>
                """);

        DblpPublicationEnrichmentService.DblpEnrichmentRunSummary summary = service.runEnrichment(dump, "march-2026");

        assertThat(summary.candidatesConsidered()).isEqualTo(1);
        assertThat(summary.recordsScanned()).isEqualTo(1);
        assertThat(summary.matched()).isEqualTo(1);

        ArgumentCaptor<ScholardexPublicationDblpEvidence> captor = ArgumentCaptor.forClass(ScholardexPublicationDblpEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        assertThat(captor.getValue().getPublicationId()).isEqualTo("spub_cp");
        assertThat(captor.getValue().getMatchMethod()).isEqualTo("DOI_EXACT");
        assertThat(captor.getValue().getConferenceName())
                .isEqualTo("Proceedings of the 16th IEEE/ACM International Symposium on Cluster, Cloud and Grid Computing");
    }

    private ScholardexPublicationFact candidatePublication(String id, String doiNormalized, String title, String coverDate, String forumId) {
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId(id);
        publication.setSubtype("ch");
        publication.setDoiNormalized(doiNormalized);
        publication.setTitle(title);
        publication.setTitleNormalized(ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService.normalizeTitle(title));
        publication.setCoverDate(coverDate);
        publication.setForumId(forumId);
        return publication;
    }

    private Forum lectureNotesForum(String id, String name) {
        Forum forum = new Forum();
        forum.setId(id);
        forum.setPublicationName(name);
        return forum;
    }

    private Path writeGzipXml(Path path, String xml) throws IOException {
        try (OutputStream out = Files.newOutputStream(path);
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return path;
    }

    private void setField(String fieldName, String value) throws Exception {
        Field field = DblpPublicationEnrichmentService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
