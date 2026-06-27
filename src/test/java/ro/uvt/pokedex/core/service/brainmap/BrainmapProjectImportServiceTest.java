package ro.uvt.pokedex.core.service.brainmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.BrainmapProjectFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.BrainmapProjectFactRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BrainmapProjectImportServiceTest {

    @Mock
    private BrainmapProjectFactRepository projectFactRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BrainmapProjectImportService service() {
        return new BrainmapProjectImportService(projectFactRepository, objectMapper);
    }

    @Test
    void toFactMapsRawScrapedFields() {
        var rec = new ro.uvt.pokedex.core.service.brainmap.dto.BrainmapProjectRecord();
        rec.setPkXProiectId("42");
        rec.setCode("PN-III-P2-2.1-PED-2016-0592");
        rec.setTitle("PV power forecasting toolkit for smart grid-management");
        rec.setDirectorFirst("Marius");
        rec.setDirectorLast("Paulescu");
        rec.setDirectorRole("Director");
        rec.setCoordinator("UNIVERSITATEA DE VEST TIMISOARA (JUDEŢUL TIMIŞ  - TIMISOARA)");
        rec.setFunder("UEFISCDI");
        rec.setStartYear("2017");
        rec.setEndYear("2018");

        BrainmapProjectFact fact = service().toFact(rec, "batch", "corr");

        assertThat(fact).isNotNull();
        assertThat(fact.getId()).isEqualTo("42");
        assertThat(fact.getCode()).isEqualTo("PN-III-P2-2.1-PED-2016-0592");
        assertThat(fact.getDirectorLast()).isEqualTo("Paulescu");
        assertThat(fact.getCoordinator()).startsWith("UNIVERSITATEA DE VEST");
        assertThat(fact.getFunder()).isEqualTo("UEFISCDI");
        assertThat(fact.getSource()).isEqualTo("BRAINMAP");
        assertThat(fact.getSourceBatchId()).isEqualTo("batch");
        assertThat(fact.getCreatedAt()).isNotNull();
    }

    @Test
    void toFactSkipsRecordsWithoutBrainmapId() {
        var rec = new ro.uvt.pokedex.core.service.brainmap.dto.BrainmapProjectRecord();
        rec.setCode("PN-III-X");
        assertThat(service().toFact(rec, "b", "c")).isNull();
    }

    @Test
    void importAllStreamsJsonlAndUpserts() throws Exception {
        Path file = Files.createTempFile("uvt_projects", ".jsonl");
        Files.writeString(file, String.join("\n",
                "{\"pkXProiectId\":\"8\",\"code\":\"Horizon-239038-101061610\",\"title\":\"U*Night\","
                        + "\"directorLast\":\"Popescu\",\"funder\":\"EC\",\"startYear\":\"2022\"}",
                "{\"pkXProiectId\":\"9\",\"code\":\"PN-II-RU-TE-2014-4-0398\",\"title\":\"Aplicații\","
                        + "\"directorLast\":\"Maricuțoiu\",\"funder\":\"UEFISCDI\"}",
                "",  // blank line ignored
                "{\"code\":\"PN-NO-ID\"}"  // no pkXProiectId -> skipped
        ), StandardCharsets.UTF_8);

        BrainmapProjectImportService.BrainmapImportResult result = service().importAll(file, "batch", "corr");

        assertThat(result.projectsImported()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);

        ArgumentCaptor<List<BrainmapProjectFact>> captor = ArgumentCaptor.captor();
        verify(projectFactRepository).saveAll(captor.capture());
        List<BrainmapProjectFact> saved = captor.getValue();
        assertThat(saved).extracting(BrainmapProjectFact::getId).containsExactly("8", "9");
        assertThat(saved).extracting(BrainmapProjectFact::getFunder).containsExactly("EC", "UEFISCDI");

        Files.deleteIfExists(file);
    }

    @Test
    void importAllSkipsMissingFile() throws Exception {
        var result = service().importAll(Path.of("/nonexistent/does-not-exist.jsonl"), "b", "c");
        assertThat(result.projectsImported()).isZero();
        verify(projectFactRepository, never()).saveAll(anyList());
    }
}
