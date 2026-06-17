package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.doaj.DoajJournalFact;
import ro.uvt.pokedex.core.repository.doaj.DoajJournalFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoajDataServiceTest {

    @Mock private DoajJournalFactRepository doajJournalFactRepository;

    private Path writeCsv(Path dir, String content) throws IOException {
        Path csv = dir.resolve("doaj.csv");
        Files.writeString(csv, content);
        return csv;
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsNormalizedIssnsAndSkipsRowsWithoutAnyIssn(@TempDir Path dir) throws IOException {
        String csv = """
                Journal title,Journal ISSN (print version),Journal EISSN (online version)
                Alpha Journal,1234-5678,2345-6789
                Beta Journal,,3456-7890
                Gamma Journal,,
                """;
        Path path = writeCsv(dir, csv);
        when(doajJournalFactRepository.findByIdIn(anyCollection())).thenReturn(List.of());

        DoajDataService service = new DoajDataService(doajJournalFactRepository);
        ImportProcessingResult result = service.importDoajCsvFromPath(path.toString(), "b1", "2026");

        assertEquals(3, result.getProcessedCount());
        assertEquals(2, result.getImportedCount());   // Alpha + Beta
        assertEquals(1, result.getSkippedCount());     // Gamma (no ISSN)

        ArgumentCaptor<List<DoajJournalFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(doajJournalFactRepository).saveAll(captor.capture());
        Map<String, DoajJournalFact> byId = captor.getValue().stream()
                .collect(Collectors.toMap(DoajJournalFact::getId, f -> f));

        DoajJournalFact alpha = byId.get("doaj_12345678");
        assertEquals("12345678", alpha.getIssn());
        assertEquals("23456789", alpha.getEIssn());
        assertEquals("Alpha Journal", alpha.getTitle());
        assertEquals("2026", alpha.getAsOf());
        assertEquals("DOAJ", alpha.getSource());
        assertEquals("b1", alpha.getSourceBatchId());

        DoajJournalFact beta = byId.get("doaj_34567890"); // keyed by eISSN when print ISSN absent
        assertNull(beta.getIssn());
        assertEquals("34567890", beta.getEIssn());
        assertTrue(byId.size() == 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertPreservesCreatedAtAndCountsUpdatesForExistingJournals(@TempDir Path dir) throws IOException {
        String csv = """
                Journal title,Journal ISSN (print version),Journal EISSN (online version)
                Alpha Journal,1234-5678,
                """;
        Path path = writeCsv(dir, csv);
        DoajJournalFact existing = new DoajJournalFact();
        existing.setId("doaj_12345678");
        existing.setCreatedAt(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        when(doajJournalFactRepository.findByIdIn(anyCollection())).thenReturn(List.of(existing));

        DoajDataService service = new DoajDataService(doajJournalFactRepository);
        ImportProcessingResult result = service.importDoajCsvFromPath(path.toString(), "b2", "2026");

        assertEquals(1, result.getProcessedCount());
        assertEquals(0, result.getImportedCount());
        assertEquals(1, result.getUpdatedCount());

        ArgumentCaptor<List<DoajJournalFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(doajJournalFactRepository).saveAll(captor.capture());
        DoajJournalFact saved = captor.getValue().iterator().next();
        assertEquals(java.time.Instant.parse("2020-01-01T00:00:00Z"), saved.getCreatedAt());
    }
}
