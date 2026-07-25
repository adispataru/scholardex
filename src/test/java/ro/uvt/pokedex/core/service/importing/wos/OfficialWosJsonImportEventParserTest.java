package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedEventStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        WosParsedRecord aisRecord = result.records().stream()
                .filter(r -> r.metricType() == MetricType.AIS).findFirst().orElseThrow();
        assertEquals("Acoustics", aisRecord.title());
        assertEquals(2023, aisRecord.year());
        assertNull(aisRecord.issn());
        assertEquals("2624599X", aisRecord.eIssn());
        assertEquals("ESCI", aisRecord.editionRaw());
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

        WosParsedRecord ifRecord = result.records().stream()
                .filter(r -> r.metricType() == MetricType.IF).findFirst().orElseThrow();
        assertEquals("Journal T", ifRecord.title());
        assertEquals(2019, ifRecord.year());
        assertEquals("12345678", ifRecord.issn());
        assertEquals(0.85, ifRecord.value());
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

    @Test
    void parsesEIssnFromCamelCaseKeyWhenLowercaseKeyMissing() throws Exception {
        WosImportEvent event = event(Map.of(
                "journalTitle", "Camel Journal",
                "year", 2021,
                "edition", "SCIE",
                "issn", "8888-9999",
                "eIssn", "7777-6666",
                "journalImpactFactor", 2.5,
                "categoryName", "CHEMISTRY",
                "rank", 1
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        WosParsedRecord ifRecord = result.records().stream()
                .filter(r -> r.metricType() == MetricType.IF).findFirst().orElseThrow();
        assertEquals("77776666", ifRecord.eIssn());
        assertEquals("88889999", ifRecord.issn());
        assertEquals("Camel Journal", ifRecord.title());
    }

    @Test
    void parseUnsupportedPayloadFormatReturnsSkipped() {
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-unsupported");
        event.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        event.setSourceFile("file.json");
        event.setSourceVersion("v2019");
        event.setSourceRowItem("0");
        event.setPayloadFormat("excel-row");
        event.setPayload("{}");

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.SKIPPED, result.status());
    }

    @Test
    void parseFallsBackToAbbrJournalWhenJournalTitleBlank() throws Exception {
        WosImportEvent event = event(new java.util.HashMap<>(java.util.Map.of(
                "year", 2020,
                "edition", "SCIE",
                "issn", "1234-5678",
                "abbrJournal", "Abbr J",
                "articleInfluenceScore", 0.5,
                "categoryName", "CHEMISTRY",
                "rank", 2
        )));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals("Abbr J", result.records().get(0).title());
    }

    @Test
    void theNineNineNineSentinelIsNotIngestedAsAnImpactFactor() throws Exception {
        // The real 1998 extracts carry journalImpactFactor=999.999 for 56 journals (50 SCIE + 6 SSCI) —
        // WoS's "no value" sentinel, the positive twin of the -999 the parser already rejected. Nothing
        // downstream clamps it (999.999 is an ordinary finite double), so before this guard "Journal Of
        // Sociology" scored on an Impact Factor of 999.999. The record is still emitted; its value is null.
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("journalTitle", "Journal Of Sociology");
        payload.put("year", 1998);
        payload.put("edition", "SCIE");
        payload.put("issn", "1440-7833");
        payload.put("journalImpactFactor", 999.999);
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-1998");
        event.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        event.setSourceFile("wos-json-1997-2019/journals-SCIE-year-1998.json");
        event.setSourceVersion("v1998");
        event.setSourceRowItem("1126");
        event.setPayloadFormat("json-item");
        event.setPayload(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertNull(result.records().stream()
                .filter(r -> r.metricType() == MetricType.IF).findFirst().orElseThrow().value(),
                "999.999 is WoS's no-value sentinel, not an Impact Factor");
    }

    @Test
    void aGenuinelyHighImpactFactorIsStillIngested() throws Exception {
        // The bound must not eat real data: CA-A Cancer Journal legitimately reaches ~685.
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("journalTitle", "CA-A Cancer Journal For Clinicians");
        payload.put("year", 2025);
        payload.put("edition", "SCIE");
        payload.put("issn", "0007-9235");
        payload.put("journalImpactFactor", 685.2);
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-ca");
        event.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        event.setSourceFile("wos-json-1997-2019/journals-SCIE-year-2025.json");
        event.setSourceVersion("v2025");
        event.setSourceRowItem("1");
        event.setPayloadFormat("json-item");
        event.setPayload(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(685.2, result.records().stream()
                .filter(r -> r.metricType() == MetricType.IF).findFirst().orElseThrow().value());
    }

    @Test
    void parsedIfRecordWhenIfValueNullButKeyPresent() throws Exception {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("journalTitle", "Journal IF Key");
        payload.put("year", 2022);
        payload.put("edition", "SCIE");
        payload.put("issn", "1234-5678");
        payload.put("journalImpactFactor", null);
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-json");
        event.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        event.setSourceFile("wos-json-1997-2019/journals-SCIE-year-2019.json");
        event.setSourceVersion("v2019");
        event.setSourceRowItem("0");
        event.setPayloadFormat("json-item");
        event.setPayload(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertTrue(result.records().stream().anyMatch(r -> r.metricType() == MetricType.IF));
        assertNull(result.records().stream().filter(r -> r.metricType() == MetricType.IF).findFirst().orElseThrow().value());
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

    @Test
    void doesNotMapTheExtractsCitationRankIntoRecords() throws Exception {
        // the JSON "rank" is the category rank by total cites, not by AIS/IF — it must never become the
        // fact-level metric rank (the enrichment computes that from metric values)
        WosParsedEventResult result = parser.parse(event(Map.of(
                "year", 2010,
                "journalTitle", "ACOUSTICS AUSTRALIA",
                "issn", "0814-6039",
                "categoryName", "ACOUSTICS",
                "edition", "SCIE",
                "articleInfluenceScore", 0.5,
                "rank", 1
        )));
        assertEquals(WosParsedEventStatus.PARSED, result.status());
        result.records().forEach(record -> assertNull(record.rank()));
    }
}
