package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JcrReferenceImportEventParserTest {

    private final JcrReferenceImportEventParser parser = new JcrReferenceImportEventParser(new ObjectMapper());

    private WosImportEvent event(String payload) {
        WosImportEvent e = new WosImportEvent();
        e.setId("evt-jcr-1");
        e.setSourceType(WosSourceType.JCR_REFERENCE);
        e.setSourceFile("JCR 2025.csv");
        e.setSourceVersion("2025");
        e.setSourceRowItem("ACOUST AUST");
        e.setPayloadFormat("jcr-csv-row");
        e.setPayload(payload);
        return e;
    }

    @Test
    void parsesNamingReferenceRecord() {
        WosParsedEventResult r = parser.parse(event(
                "{\"title20\":\"ACOUST AUST\",\"title\":\"ACOUSTICS AUSTRALIA\",\"country\":\"AUSTRALIA\",\"editions\":\"SCIE\"}"));
        assertEquals(WosParsedEventStatus.PARSED, r.status());
        assertEquals(1, r.records().size());
        WosParsedRecord rec = r.records().get(0);
        assertEquals("ACOUSTICS AUSTRALIA", rec.title());
        assertEquals("ACOUST AUST", rec.abbreviatedTitle());
        assertEquals("SCIE", rec.editionRaw());
        assertEquals(WosSourceType.JCR_REFERENCE, rec.sourceType());
        assertNull(rec.issn());
        assertNull(rec.eIssn());
        assertNull(rec.metricType());
        assertEquals(2025, rec.year());
    }

    @Test
    void skipsRowsMissingTitles() {
        WosParsedEventResult r = parser.parse(event("{\"title20\":\"X\",\"title\":null}"));
        assertEquals(WosParsedEventStatus.SKIPPED, r.status());
    }

    @Test
    void skipsUnsupportedEvents() {
        WosImportEvent e = event("{}");
        e.setSourceType(WosSourceType.MJL_COVERAGE);
        assertEquals(WosParsedEventStatus.SKIPPED, parser.parse(e).status());
    }
}
