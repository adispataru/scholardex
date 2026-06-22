package ro.uvt.pokedex.core.service.openalex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAlexBulkImportServiceTest {

    @Mock
    private OpenAlexImportService openAlexImportService;
    @Mock
    private ScholardexAffiliationFactRepository affiliationFactRepository;
    @Mock
    private ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexInstitutionFactRepository institutionFactRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpenAlexBulkImportService service() {
        return new OpenAlexBulkImportService(openAlexImportService, affiliationFactRepository,
                institutionFactRepository, objectMapper);
    }

    // ── backbone mapping ──────────────────────────────────────────────────────

    @Test
    void backboneIdIsRorDerivedAndStableInSaffNamespace() {
        String id = OpenAlexBulkImportService.buildRorBackboneId("0583a0t97");
        assertThat(id).startsWith("saff_");
        assertThat(id).isEqualTo(OpenAlexBulkImportService.buildRorBackboneId("0583a0t97")); // deterministic
        assertThat(id).isNotEqualTo(OpenAlexBulkImportService.buildRorBackboneId("01ggx4157"));
    }

    @Test
    void toBackboneFactMapsRorAliasesAndGeo() throws Exception {
        var rec = objectMapper.readValue("""
                {"id":"https://openalex.org/I123","ror":"https://ror.org/0583a0t97",
                 "display_name":"West University of Timişoara",
                 "display_name_alternatives":["Universitatea de Vest din Timișoara"],
                 "display_name_acronyms":["UVT"],
                 "country_code":"RO","geo":{"city":"Timișoara","country":"Romania","country_code":"RO"}}
                """, ro.uvt.pokedex.core.service.openalex.dto.OpenAlexInstitutionRecord.class);

        ScholardexAffiliationFact fact = service().toBackboneFact(rec, "I123", "batch", "corr");

        assertThat(fact).isNotNull();
        assertThat(fact.getId()).isEqualTo(OpenAlexBulkImportService.buildRorBackboneId("0583a0t97"));
        assertThat(fact.getRorIds()).containsExactly("0583a0t97");
        assertThat(fact.getName()).isEqualTo("West University of Timişoara");
        assertThat(fact.getAliases()).contains("Universitatea de Vest din Timișoara", "UVT");
        assertThat(fact.getCity()).isEqualTo("Timișoara");
        assertThat(fact.getCountry()).isEqualTo("Romania");
        assertThat(fact.getSource()).isEqualTo("OPENALEX");
        assertThat(fact.getSourceRecordId()).isEqualTo("I123");
    }

    @Test
    void toBackboneFactSkipsRecordsWithoutRor() throws Exception {
        var rec = objectMapper.readValue(
                "{\"id\":\"https://openalex.org/I999\",\"display_name\":\"No ROR Inc\"}",
                ro.uvt.pokedex.core.service.openalex.dto.OpenAlexInstitutionRecord.class);
        assertThat(service().toBackboneFact(rec, "I999", "b", "c")).isNull();
    }

    // ── H75 S1.0: institution source fact (raw inputs for the V2 backbone derivation) ─────────────────────────

    @Test
    void toInstitutionFactStoresRawMappingInputs() throws Exception {
        var rec = objectMapper.readValue("""
                {"id":"https://openalex.org/I123","ror":"https://ror.org/0583a0t97",
                 "display_name":"West University of Timişoara",
                 "display_name_alternatives":["Universitatea de Vest din Timișoara"],
                 "display_name_acronyms":["UVT"],
                 "country_code":"RO","geo":{"city":"Timișoara","country":"Romania","country_code":"RO"}}
                """, ro.uvt.pokedex.core.service.openalex.dto.OpenAlexInstitutionRecord.class);

        var fact = service().toInstitutionFact(rec, "I123", "batch", "corr");

        assertThat(fact.getId()).isEqualTo("I123");
        assertThat(fact.getRor()).isEqualTo("0583a0t97");
        assertThat(fact.getDisplayName()).isEqualTo("West University of Timişoara");
        assertThat(fact.getDisplayNameAlternatives()).containsExactly("Universitatea de Vest din Timișoara");
        assertThat(fact.getDisplayNameAcronyms()).containsExactly("UVT");
        assertThat(fact.getCountryCode()).isEqualTo("RO");
        assertThat(fact.getGeoCity()).isEqualTo("Timișoara");
        assertThat(fact.getGeoCountry()).isEqualTo("Romania");
        assertThat(fact.getSource()).isEqualTo("OPENALEX");
        assertThat(fact.getSourceRecordId()).isEqualTo("I123");
    }

    // ── referenced-only filter over a gz snapshot ─────────────────────────────

    @Test
    void importInstitutionBackboneKeepsOnlyReferencedIds() throws IOException {
        Path dir = Files.createTempDirectory("oa-inst");
        Path gz = dir.resolve("part_0000.gz");
        writeGz(gz, List.of(
                "{\"id\":\"https://openalex.org/I1\",\"ror\":\"https://ror.org/aaa\",\"display_name\":\"Referenced Uni\"}",
                "{\"id\":\"https://openalex.org/I2\",\"ror\":\"https://ror.org/bbb\",\"display_name\":\"Unreferenced Uni\"}",
                "{\"id\":\"https://openalex.org/I3\",\"display_name\":\"Referenced But No ROR\"}"));

        int count = service().importInstitutionBackbone(dir, Set.of("I1", "I3"), "batch", "corr");

        // Backbone: I1 kept; I2 filtered out (not referenced); I3 skipped (no ROR)
        assertThat(count).isEqualTo(1);
        ArgumentCaptor<List<ScholardexAffiliationFact>> cap = ArgumentCaptor.forClass(List.class);
        verify(affiliationFactRepository).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().getFirst().getRorIds()).containsExactly("aaa");

        // H75 S1.0: institution source facts written for ALL referenced ids (I1 + I3), incl. the no-ROR one; I2 filtered.
        ArgumentCaptor<List<ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact>> instCap =
                ArgumentCaptor.forClass(List.class);
        verify(institutionFactRepository).saveAll(instCap.capture());
        assertThat(instCap.getValue())
                .extracting(ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact::getId)
                .containsExactlyInAnyOrder("I1", "I3");
    }

    @Test
    void importInstitutionBackboneSkipsWhenNoReferencedIds() throws IOException {
        Path dir = Files.createTempDirectory("oa-inst-empty");
        int count = service().importInstitutionBackbone(dir, Set.of(), "b", "c");
        assertThat(count).isZero();
        verify(affiliationFactRepository, never()).saveAll(any());
    }

    // ── works (full) + citers (bare) + referenced-id collection ───────────────

    @Test
    void importAllReadsWorksAndCitersFullAndCollectsInstitutionIds() throws IOException {
        Path works = Files.createTempFile("uvt-works", ".jsonl");
        Files.writeString(works,
                "{\"id\":\"https://openalex.org/W1\",\"title\":\"P1\"," +
                        "\"authorships\":[{\"author\":{\"id\":\"https://openalex.org/A1\"}," +
                        "\"institutions\":[{\"id\":\"https://openalex.org/I1\",\"ror\":\"https://ror.org/aaa\"}]}]}\n");
        Path citers = Files.createTempFile("uvt-citers", ".jsonl");
        Files.writeString(citers,
                "{\"id\":\"https://openalex.org/W2\",\"title\":\"C1\"," +
                        "\"authorships\":[{\"author\":{\"id\":\"https://openalex.org/A2\"}," +
                        "\"institutions\":[{\"id\":\"https://openalex.org/I2\",\"ror\":\"https://ror.org/bbb\"}]}]}\n");
        Path instDir = Files.createTempDirectory("oa-inst2");
        writeGz(instDir.resolve("part_0000.gz"), List.of(
                "{\"id\":\"https://openalex.org/I1\",\"ror\":\"https://ror.org/aaa\",\"display_name\":\"Uni One\"}",
                "{\"id\":\"https://openalex.org/I2\",\"ror\":\"https://ror.org/bbb\",\"display_name\":\"Uni Two\"}"));

        when(openAlexImportService.importFullWork(any(), anyString(), anyString())).thenReturn("W1", "W2");

        var result = service().importAll(works, citers, instDir, "batch", "corr");

        // H73 slice 3: BOTH uvt works and citers go through the FULL path (citers no longer bare)
        verify(openAlexImportService, times(2)).importFullWork(any(), anyString(), anyString());
        verify(openAlexImportService, never()).upsertNeighborWorks(any(), anyString(), anyString());
        assertThat(result.worksImported()).isEqualTo(1);
        assertThat(result.citersImported()).isEqualTo(1);
        // both I1 (from works) and I2 (from citers) are collected → both backboned
        assertThat(result.referencedInstitutions()).isEqualTo(2);
        assertThat(result.backboneInstitutions()).isEqualTo(2);
    }

    private static void writeGz(Path path, List<String> lines) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            for (String line : lines) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
