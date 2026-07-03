package ro.uvt.pokedex.core.service.openalex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexSourceFactRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OpenAlexSourceApcImportServiceTest {

    @Mock
    private OpenAlexSourceFactRepository sourceFactRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** One line of an OpenAlex works dump with the fields the ingest reads. */
    private static String work(String sourceId, String issn, boolean isOa, Boolean isInDoaj, Integer apcUsd) {
        String apcList = apcUsd == null ? "null"
                : "{\"value\":" + apcUsd + ",\"currency\":\"USD\",\"value_usd\":" + apcUsd + "}";
        String doaj = isInDoaj == null ? "null" : String.valueOf(isInDoaj);
        return "{\"id\":\"https://openalex.org/W1\",\"apc_list\":" + apcList + ",\"primary_location\":{\"source\":{"
                + "\"id\":\"https://openalex.org/" + sourceId + "\",\"display_name\":\"" + sourceId + "\","
                + "\"issn\":[\"" + issn + "\"],\"is_oa\":" + isOa + ",\"is_in_doaj\":" + doaj + "}}}";
    }

    private Map<String, OpenAlexSourceFact> run(String... lines) throws IOException {
        Path dump = Files.createTempFile("uvt_works", ".jsonl");
        Files.write(dump, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        OpenAlexSourceApcImportService service =
                new OpenAlexSourceApcImportService(objectMapper, sourceFactRepository);
        service.importSourceApc(List.of(dump), "batch", "corr");

        ArgumentCaptor<List<OpenAlexSourceFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(sourceFactRepository, atLeastOnce()).saveAll(captor.capture());
        List<OpenAlexSourceFact> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all.stream().collect(Collectors.toMap(OpenAlexSourceFact::getId, Function.identity()));
    }

    @Test
    void goldOaWithApcIsFeeJournalHybridIsNot() throws IOException {
        Map<String, OpenAlexSourceFact> facts = run(
                work("S_gold", "2079-9292", true, false, 2165),   // MDPI Electronics analogue: gold, DOAJ misses it
                work("S_hybrid", "0924-2716", false, false, 3310), // hybrid: apc present but not gold
                work("S_diamond", "1111-2222", true, null, null)); // gold but no fee

        assertEquals(3, facts.size());

        OpenAlexSourceFact gold = facts.get("S_gold");
        assertTrue(gold.isFeeJournal());
        assertEquals(2165, gold.getApcUsd());
        assertTrue(gold.getIssns().contains("2079-9292"));
        assertEquals(Boolean.FALSE, gold.getIsInDoaj());

        assertFalse(facts.get("S_hybrid").isFeeJournal()); // is_oa=false → excluded despite apc
        assertNull(facts.get("S_diamond").getApcUsd());
        assertFalse(facts.get("S_diamond").isFeeJournal()); // gold but apc=0 → not a fee journal
    }

    @Test
    void aggregatesAcrossWorksTakingMaxApcAndOrOfIsOa() throws IOException {
        // Same source seen twice: once hybrid/low, once gold/high. OR of is_oa + max apc wins.
        Map<String, OpenAlexSourceFact> facts = run(
                work("S1", "2079-9292", false, false, 1000),
                work("S1", "2079-9292", true, true, 2165));

        assertEquals(1, facts.size());
        OpenAlexSourceFact f = facts.get("S1");
        assertEquals(Boolean.TRUE, f.getIsOa());
        assertEquals(2165, f.getApcUsd());
        assertTrue(f.isFeeJournal());
        assertEquals(2, f.getWorksObserved());
    }
}
