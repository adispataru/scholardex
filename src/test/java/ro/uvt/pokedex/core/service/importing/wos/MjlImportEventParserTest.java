package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MjlImportEventParserTest {

    private final MjlImportEventParser parser = new MjlImportEventParser(new ObjectMapper());

    private WosImportEvent event(String payload) {
        WosImportEvent e = new WosImportEvent();
        e.setId("evt-1");
        e.setSourceType(WosSourceType.MJL_COVERAGE);
        e.setSourceFile("Science Citation Index Expanded (SCIE) (1).csv");
        e.setSourceVersion("2025");
        e.setSourceRowItem("1234-5678|SCIE");
        e.setPayloadFormat("mjl-csv-row");
        e.setPayload(payload);
        return e;
    }

    @Test
    void parsesSingleCategoryCoverageRecord() {
        WosParsedEventResult r = parser.parse(event(
                "{\"edition\":\"SCIE\",\"title\":\"Journal of Things\",\"issn\":\"1234-5678\",\"eIssn\":\"8765-4321\",\"publisher\":\"Elsevier\",\"categories\":\"Computer Science\"}"));
        assertEquals(WosParsedEventStatus.PARSED, r.status());
        assertEquals(1, r.records().size());
        WosParsedRecord rec = r.records().get(0);
        assertEquals(EditionNormalized.SCIE, rec.editionNormalized());
        assertEquals("Computer Science", rec.categoryNameCanonical());
        assertEquals(2025, rec.year());
        assertNull(rec.metricType());   // coverage, not a metric
        assertNull(rec.quartileRank());
        assertEquals(WosSourceType.MJL_COVERAGE, rec.sourceType());
    }

    @Test
    void splitsPipeSeparatedCategoriesIntoOneRecordEach() {
        WosParsedEventResult r = parser.parse(event(
                "{\"edition\":\"AHCI\",\"title\":\"X\",\"issn\":\"0001-6241\",\"eIssn\":null,\"categories\":\"Language & Linguistics | Literature\"}"));
        assertEquals(2, r.records().size());
        assertTrue(r.records().stream().anyMatch(x -> "Language & Linguistics".equals(x.categoryNameCanonical())));
        assertTrue(r.records().stream().anyMatch(x -> "Literature".equals(x.categoryNameCanonical())));
        assertTrue(r.records().stream().allMatch(x -> x.editionNormalized() == EditionNormalized.AHCI));
    }

    @Test
    void blankCategoriesYieldsSingleCategorylessRecord() {
        WosParsedEventResult r = parser.parse(event(
                "{\"edition\":\"ESCI\",\"title\":\"Y\",\"issn\":\"\",\"eIssn\":\"2222-0000\",\"categories\":\"\"}"));
        assertEquals(1, r.records().size());
        assertNull(r.records().get(0).categoryNameCanonical());
        assertEquals(EditionNormalized.ESCI, r.records().get(0).editionNormalized());
    }

    @Test
    void skipsWhenNoIssnOrEissn() {
        WosParsedEventResult r = parser.parse(event(
                "{\"edition\":\"SCIE\",\"title\":\"No Identity\",\"issn\":\"\",\"eIssn\":\"\",\"categories\":\"X\"}"));
        assertEquals(WosParsedEventStatus.SKIPPED, r.status());
    }
}
