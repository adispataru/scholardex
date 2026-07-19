package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;
import ro.uvt.pokedex.core.repository.erih.ErihJournalFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErihDataServiceTest {

    @Mock private ErihJournalFactRepository erihJournalFactRepository;

    @Test
    @SuppressWarnings("unchecked")
    void parsesJsonlNormalizesIssnsCapturesDoajFlagAndSkipsRowsWithoutId(@TempDir Path dir) throws IOException {
        String jsonl = String.join("\n",
                "{\"id\":\"509624\",\"tidsskriftISSNP\":\"1814-3237\",\"tidsskriftISSNE\":\"1857-498X\",\"navn\":\"local name\",\"navn_en\":\"Natural Sciences USM\",\"discipline_ids\":[\"24\",\"27\"],\"oa_doaj\":1}",
                "{\"id\":\"509625\",\"tidsskriftISSNE\":\"3023-4247\",\"navn_en\":\"HIKEM\",\"discipline_ids\":[\"14\"],\"oa_doaj\":0}",
                "{\"tidsskriftISSNP\":\"0000-0000\",\"navn_en\":\"No Id Journal\"}",
                "");
        Path path = dir.resolve("erih.jsonl");
        Files.writeString(path, jsonl);
        when(erihJournalFactRepository.findByIdIn(anyCollection())).thenReturn(List.of());

        ErihDataService service = new ErihDataService(new ro.uvt.pokedex.core.service.importing.ImportPathGuard("/"), erihJournalFactRepository);
        ImportProcessingResult result = service.importErihJsonlFromPath(path.toString(), "b1", "2026");

        assertEquals(3, result.getProcessedCount());
        assertEquals(2, result.getImportedCount());
        assertEquals(1, result.getSkippedCount());

        ArgumentCaptor<List<ErihJournalFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(erihJournalFactRepository).saveAll(captor.capture());
        Map<String, ErihJournalFact> byId = captor.getValue().stream()
                .collect(Collectors.toMap(ErihJournalFact::getId, f -> f));

        ErihJournalFact a = byId.get("509624");
        assertEquals("18143237", a.getIssn());
        assertEquals("1857498X", a.getEIssn());
        assertEquals("Natural Sciences USM", a.getTitle()); // navn_en preferred over navn
        assertEquals(List.of("24", "27"), a.getDisciplineIds());
        assertTrue(a.isOaDoaj());
        assertEquals("2026", a.getAsOf());
        assertEquals("ERIH", a.getSource());

        ErihJournalFact b = byId.get("509625");
        assertNull(b.getIssn());
        assertEquals("30234247", b.getEIssn());
        assertFalse(b.isOaDoaj());
        assertEquals(2, byId.size());
    }
}
