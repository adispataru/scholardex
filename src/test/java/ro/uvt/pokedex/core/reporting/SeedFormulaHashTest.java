package ro.uvt.pokedex.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaHasher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every committed indicator carries the hash the application stamps when it saves a formula. The prod scripts are
 * generated from the seed and write indicators with mongosh, which stamps nothing: a formula changed without its
 * hash keeps the old formula's identity, and cached results of the old formula could be served for the new one.
 * A mismatch here means the seed was edited by hand or by mongosh — compute the hash with {@link FormulaHasher}.
 */
class SeedFormulaHashTest {

    @Test
    void everyCommittedFormulaCarriesItsOwnHash() throws Exception {
        JsonNode indicators = new ObjectMapper().readTree(Files.readString(Path.of("seed", "precious-config", "indicators.json")));
        List<String> stale = new ArrayList<>();
        for (JsonNode indicator : indicators) {
            JsonNode formula = indicator.get("formula");
            if (formula == null || formula.isNull() || formula.asText().isBlank()) {
                continue;
            }
            String expected = FormulaHasher.hash(formula.asText());
            JsonNode stored = indicator.get("formulaHash");
            if (stored == null || !expected.equals(stored.asText())) {
                stale.add(indicator.get("name").asText() + " (expected " + expected + ")");
            }
        }
        assertEquals(List.of(), stale);
    }
}
