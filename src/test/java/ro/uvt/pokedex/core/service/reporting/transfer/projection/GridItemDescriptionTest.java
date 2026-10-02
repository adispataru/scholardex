package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** H142 — the line the faculty's grid lists for one activity of a run. */
class GridItemDescriptionTest {

    private static Map<String, Object> record(String name, String date, Map<String, Object> fields) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("id", "a1");
        attrs.put("name", name);
        attrs.put("date", date);
        attrs.put("fields", fields);
        attrs.put("activity", Map.of("name", "Participare eveniment artistic"));
        return attrs;
    }

    @Test
    void theNameTheResearcherGaveIsTheLineWithItsYear() {
        assertEquals("Concert de Crăciun, Corul FMT-UVT (2024)",
                GridItemDescription.of(record("Concert de Crăciun, Corul FMT-UVT", "2024-12-20", Map.of())));
        assertEquals("Gala UVT 80, 24.05.2024",
                GridItemDescription.of(record("Gala UVT 80, 24.05.2024", "2024-05-24", Map.of())), "the year is already there");
    }

    @Test
    void aNameThatIsOnlyTheTypesGivesWayToTheRecordsOwnText() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("Dovezi", "Recital de orgă, Catedrala Mitropolitană, Timișoara");
        fields.put("Link", "https://youtu.be/xyz");
        fields.put("Marime_formatie", "1");
        assertEquals("Recital de orgă, Catedrala Mitropolitană, Timișoara (2023)",
                GridItemDescription.of(record("Participare eveniment artistic", "2023-03-01", fields)));
    }

    @Test
    void aNameShortenedOnImportGivesWayToTheWholeText() {
        String whole = "Spectacol de operă „Il campanello” de G. Donizetti, FMT-UVT, sala TNT, maestru de cor, "
                + "cu studenții anului III, regia Ion Popescu";
        Map<String, Object> fields = Map.of("Dovezi", whole);
        assertEquals(whole + " (2026)", GridItemDescription.of(record(whole.substring(0, 60) + "…", "2026-05-12", fields)));
    }

    @Test
    void nothingToSayIsNull() {
        assertNull(GridItemDescription.of(null));
        assertNull(GridItemDescription.of(record("", "", Map.of())));
    }
}
