package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialWosJsonImportEventParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OfficialWosJsonImportEventParser parser = new OfficialWosJsonImportEventParser(objectMapper);

    @Test
    void parsesAisAndIfFromOfficialJsonWithEsciEdition() throws Exception {
        WosImportEvent event = event(Map.of(
                "journalTitle", "Acoustics",
                "year", 2023,
                "edition", "ESCI",
                "issn", "N/A",
                "eissn", "2624-599X",
                "articleInfluenceScore", 0.412,
                "journalImpactFactor", 1.155,
                "categoryName", "ACOUSTICS",
                "rank", 12
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(2, result.records().size());
        assertTrue(result.records().stream().anyMatch(r -> r.metricType() == MetricType.AIS));
        assertTrue(result.records().stream().anyMatch(r -> r.metricType() == MetricType.IF));
        assertTrue(result.records().stream().allMatch(r -> r.editionNormalized() == EditionNormalized.ESCI));
        assertTrue(result.records().stream().allMatch(r -> r.quartileRank() == null));
    }

    @Test
    void handlesScienceTokenAsScieAndSentinelAsMissing() throws Exception {
        WosImportEvent event = event(Map.of(
                "journalTitle", "Journal T",
                "year", 2019,
                "edition", "SCIENCE",
                "issn", "1234-5678",
                "articleInfluenceScore", -999.999,
                "journalImpactFactor", 0.85,
                "categoryName", "ACOUSTICS",
                "rank", 32
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(2, result.records().size());
        assertTrue(result.records().stream().allMatch(r -> r.editionNormalized() == EditionNormalized.SCIE));
        assertTrue(result.records().stream().anyMatch(r -> r.metricType() == MetricType.AIS && r.value() == null));
    }

    @Test
    void skipsWhenNoSupportedMetricPresent() throws Exception {
        WosImportEvent event = event(Map.of(
                "journalTitle", "Journal X",
                "year", 2018,
                "edition", "AHCI",
                "issn", "1000-2000",
                "categoryName", "HISTORY",
                "rank", 3
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.SKIPPED, result.status());
    }

    @Test
    void bundledEditionValueSplitsIntoScieAndSsciRecords() throws Exception {
        WosImportEvent event = event(Map.of(
                "journalTitle", "Bundled Journal",
                "year", 2023,
                "edition", "SCIE + SSCI",
                "issn", "1234-5678",
                "articleInfluenceScore", 1.23,
                "categoryName", "ECONOMICS",
                "rank", 5
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(2, result.records().size());
        assertTrue(result.records().stream().anyMatch(r -> r.editionNormalized() == EditionNormalized.SCIE));
        assertTrue(result.records().stream().anyMatch(r -> r.editionNormalized() == EditionNormalized.SSCI));
        assertTrue(result.records().stream().allMatch(r -> r.metricType() == MetricType.AIS));
    }

    private WosImportEvent event(Map<String, Object> payload) throws Exception {
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-json");
        event.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        event.setSourceFile("wos-json-1997-2019/journals-SCIE-year-2019.json");
        event.setSourceVersion("v2019");
        event.setSourceRowItem("0");
        event.setPayloadFormat("json-item");
        event.setPayload(objectMapper.writeValueAsString(payload));
        return event;
    }
}
