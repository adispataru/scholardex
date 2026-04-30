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

class GovAisRisImportEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GovAisRisImportEventParser parser = new GovAisRisImportEventParser(objectMapper);

    @Test
    void parsesAis2012WithScienceEditionNormalizedToScie() throws Exception {
        WosImportEvent event = event("AIS", "2012", Map.of(
                "c0", "ACOUST PHYS+",
                "c1", "1063-7710",
                "c2", 0.112,
                "c3", "ACOUSTICS",
                "c4", "SCIENCE"
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(1, result.records().size());
        WosParsedRecord record = result.records().get(0);
        assertEquals(MetricType.AIS, record.metricType());
        assertEquals(EditionNormalized.SCIE, record.editionNormalized());
        assertEquals("ACOUSTICS", record.categoryNameCanonical());
        assertEquals("ACOUST PHYS+", record.title());
        assertEquals("10637710", record.issn());
        assertEquals(2012, record.year());
        assertEquals(0.112, record.value());
    }

    @Test
    void parsesAis2013WithSocialSciencesEditionNormalizedToSsci() throws Exception {
        WosImportEvent event = event("AIS", "2013", Map.of(
                "c0", "Journal Social",
                "c1", "1234-5678",
                "c2", 0.512,
                "c3", "SOCIOLOGY",
                "c4", "social sciences"
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(1, result.records().size());
        WosParsedRecord record = result.records().get(0);
        assertEquals(EditionNormalized.SSCI, record.editionNormalized());
        assertEquals("Journal Social", record.title());
        assertEquals("12345678", record.issn());
        assertEquals(2013, record.year());
        assertEquals(0.512, record.value());
    }

    @Test
    void parsesAis2020WithQuarterAndEissn() throws Exception {
        WosImportEvent event = event("AIS", "2020", Map.of(
                "c0", "ULTRASOUND",
                "c1", "0960-7692",
                "c2", "1469-0705",
                "c3", 2.002,
                "c4", "SCIE",
                "c5", "ACOUSTICS",
                "c6", 1.0
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals("Q1", result.records().get(0).quarter());
        assertNull(result.records().get(0).rank());
        assertNull(result.records().get(0).quartileRank());
        assertEquals("14690705", result.records().get(0).eIssn());
    }

    @Test
    void parsesAis2023WithEsciEdition() throws Exception {
        WosImportEvent event = event("AIS", "2023", Map.of(
                "c0", "Acoustics",
                "c1", "N/A",
                "c2", "2624-599X",
                "c3", "ACOUSTICS",
                "c4", "ESCI",
                "c5", "0.412"
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(EditionNormalized.ESCI, result.records().get(0).editionNormalized());
        assertNull(result.records().get(0).issn());
        assertEquals("2624599X", result.records().get(0).eIssn());
    }

    @Test
    void parsesAis2024UsingCurrentLayout() throws Exception {
        WosImportEvent event = event("AIS", "2024", Map.of(
                "c0", "Acoustics 2024",
                "c1", "1234-5678",
                "c2", "2624-599X",
                "c3", "ACOUSTICS",
                "c4", "SCIE",
                "c5", "0.512"
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(1, result.records().size());
        assertEquals(EditionNormalized.SCIE, result.records().get(0).editionNormalized());
        assertEquals("12345678", result.records().get(0).issn());
        assertEquals("2624599X", result.records().get(0).eIssn());
        assertEquals(0.512, result.records().get(0).value());
    }

    @Test
    void parsesRis2019AndRis2020Variants() throws Exception {
        WosParsedEventResult ris2019 = parser.parse(event("RIS", "2019", Map.of(
                "c0", "Journal A",
                "c1", "2190-572X",
                "c2", 0.652
        )));
        WosParsedEventResult ris2020 = parser.parse(event("RIS", "2020", Map.of(
                "c0", "Journal A",
                "c1", "2190-572X",
                "c2", "2190-5738",
                "c3", 0.7226
        )));

        assertEquals(WosParsedEventStatus.PARSED, ris2019.status());
        assertEquals(WosParsedEventStatus.PARSED, ris2020.status());
        assertNull(ris2019.records().get(0).eIssn());
        assertEquals("21905738", ris2020.records().get(0).eIssn());
        assertEquals(MetricType.RIS, ris2020.records().get(0).metricType());
    }

    @Test
    void parsesRis2024WithEditionInC3AndValueInC4() throws Exception {
        WosParsedEventResult ris2024 = parser.parse(event("RIS", "2024", Map.of(
                "c0", "Journal C",
                "c1", "2190-572X",
                "c2", "2190-5738",
                "c3", "SCIE",
                "c4", 0.9331
        )));

        assertEquals(WosParsedEventStatus.PARSED, ris2024.status());
        assertEquals("21905738", ris2024.records().get(0).eIssn());
        assertEquals(EditionNormalized.SCIE, ris2024.records().get(0).editionNormalized());
        assertEquals(0.9331, ris2024.records().get(0).value());
    }

    @Test
    void parsesAis2011WithoutCategoryOrEdition() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2011", Map.of(
                "c0", "Early Journal",
                "c1", "1234-5678",
                "c2", 0.321
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        WosParsedRecord record = result.records().get(0);
        assertEquals("Early Journal", record.title());
        assertEquals("12345678", record.issn());
        assertEquals(2011, record.year());
        assertEquals(0.321, record.value());
        assertNull(record.categoryNameCanonical());
    }

    @Test
    void parsesAis2014To2017Layout() throws Exception {
        WosParsedEventResult result2014 = parser.parse(event("AIS", "2014", Map.of(
                "c0", "skipped-col",
                "c1", "Journal 2014",
                "c2", "1234-5678",
                "c3", 0.777
        )));
        WosParsedEventResult result2017 = parser.parse(event("AIS", "2017", Map.of(
                "c0", "skipped-col",
                "c1", "Journal 2017",
                "c2", "8765-4321",
                "c3", 0.888
        )));

        assertEquals(WosParsedEventStatus.PARSED, result2014.status());
        assertEquals("Journal 2014", result2014.records().get(0).title());
        assertEquals("12345678", result2014.records().get(0).issn());
        assertEquals(2014, result2014.records().get(0).year());
        assertEquals(0.777, result2014.records().get(0).value());

        assertEquals(WosParsedEventStatus.PARSED, result2017.status());
        assertEquals("Journal 2017", result2017.records().get(0).title());
        assertEquals(0.888, result2017.records().get(0).value());
    }

    @Test
    void parsesAis2018WithEditionAndCategory() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2018", Map.of(
                "c0", "Journal 2018",
                "c1", "1234-5678",
                "c2", 0.555,
                "c3", "SCIE",
                "c4", "CHEMISTRY",
                "c5", 1.0
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        WosParsedRecord record = result.records().get(0);
        assertEquals("Journal 2018", record.title());
        assertEquals("12345678", record.issn());
        assertEquals(2018, record.year());
        assertEquals(0.555, record.value());
        assertEquals("CHEMISTRY", record.categoryNameCanonical());
        assertEquals("Q1", record.quarter());
    }

    @Test
    void parsesAis2019WithQPrefixedQuarter() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2019", Map.of(
                "c0", "Journal 2019",
                "c1", "5678-1234",
                "c2", 0.444,
                "c3", "SCIE",
                "c4", "PHYSICS",
                "c5", "Q3"
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        WosParsedRecord record = result.records().get(0);
        assertEquals("Journal 2019", record.title());
        assertEquals("56781234", record.issn());
        assertEquals(2019, record.year());
        assertEquals(0.444, record.value());
        assertEquals("PHYSICS", record.categoryNameCanonical());
        assertEquals("Q3", record.quarter());
    }

    @Test
    void parsesAis2021WithCategoryEditionCombinedField() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2021", Map.of(
                "c0", "Journal 2021",
                "c1", "1234-5678",
                "c2", "8765-4321",
                "c3", 0.666,
                "c4", "SCIE",
                "c5", "PHYSICS - SSCI",
                "c6", 2.0
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        WosParsedRecord record = result.records().get(0);
        assertEquals("Journal 2021", record.title());
        assertEquals("12345678", record.issn());
        assertEquals("87654321", record.eIssn());
        assertEquals(2021, record.year());
        assertEquals(0.666, record.value());
        assertEquals("Q2", record.quarter());
        assertEquals("PHYSICS", record.categoryNameCanonical());
    }

    @Test
    void parsesAis2022WithCategoryEditionCombinedField() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2022", Map.of(
                "c0", "Journal 2022",
                "c1", "2345-6789",
                "c2", "3456-7890",
                "c3", "BIOLOGY - SCIE",
                "c4", 0.999,
                "c5", 3.0
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        WosParsedRecord record = result.records().get(0);
        assertEquals("Journal 2022", record.title());
        assertEquals("23456789", record.issn());
        assertEquals("34567890", record.eIssn());
        assertEquals(2022, record.year());
        assertEquals(0.999, record.value());
        assertEquals("BIOLOGY", record.categoryNameCanonical());
        assertEquals("Q3", record.quarter());
    }

    @Test
    void parsesRis2020WithNullMetricValueFallbackWhenBothEissnAndC3AreBlank() throws Exception {
        WosParsedEventResult result = parser.parse(event("RIS", "2020", Map.of(
                "c0", "Journal RIS Fallback",
                "c1", "1234-5678",
                "c2", "N/A",
                "c3", 0.555
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        WosParsedRecord record = result.records().get(0);
        assertEquals("Journal RIS Fallback", record.title());
        assertEquals("12345678", record.issn());
        assertNull(record.eIssn());
        assertEquals(0.555, record.value());
    }

    @Test
    void handlesSentinelMetricAsMissing() throws Exception {
        WosImportEvent event = event("AIS", "2023", Map.of(
                "c0", "Journal Z",
                "c1", "1234-5678",
                "c2", "8765-4321",
                "c3", "PHYSICS",
                "c4", "AHCI",
                "c5", "-999.999"
        ));

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertNull(result.records().get(0).value());
        assertEquals(EditionNormalized.AHCI, result.records().get(0).editionNormalized());
    }

    @Test
    void parsesYearGivenAsDecimalString() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2024.0", Map.of(
                "c0", "Decimal Year Journal",
                "c1", "1234-5678",
                "c2", "8765-4321",
                "c3", "ACOUSTICS",
                "c4", "SCIE",
                "c5", "0.512"
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(2024, result.records().get(0).year());
    }

    @Test
    void parsesMetricValueWithCommaDecimalSeparator() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2024", Map.of(
                "c0", "Comma Decimal Journal",
                "c1", "1234-5678",
                "c2", "8765-4321",
                "c3", "ACOUSTICS",
                "c4", "SCIE",
                "c5", "1,512"
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(1.512, result.records().get(0).value(), 0.001);
    }

    @Test
    void parseUnsupportedPayloadFormatReturnsSkipped() {
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-unsupported");
        event.setSourceType(WosSourceType.GOV_AIS_RIS);
        event.setSourceFile("AIS_2024.xlsx");
        event.setSourceVersion("v2024");
        event.setSourceRowItem("1");
        event.setPayloadFormat("json-item");
        event.setPayload("{}");

        WosParsedEventResult result = parser.parse(event);

        assertEquals(WosParsedEventStatus.SKIPPED, result.status());
    }

    @Test
    void parseReturnsSkippedWhenAllIdentityFieldsBlank() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2012", Map.of(
                "c0", "",
                "c1", "",
                "c2", 0.5,
                "c3", "ACOUSTICS",
                "c4", "SCIE"
        )));

        assertEquals(WosParsedEventStatus.SKIPPED, result.status());
    }

    @Test
    void parseRis2024FallbackMetricValueFromC2WhenEIssnNullAndC4NotParseable() throws Exception {
        WosParsedEventResult result = parser.parse(event("RIS", "2024", Map.of(
                "c0", "Journal RIS 2024 Fallback",
                "c1", "1234-5678",
                "c2", "0.77",
                "c3", "SCIE",
                "c4", "N/A"
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(0.77, result.records().get(0).value(), 0.001);
    }

    @Test
    void parseRis2020MetricFallbackFromC2WhenC3NotParseableAndEIssnNull() throws Exception {
        WosParsedEventResult result = parser.parse(event("RIS", "2020", Map.of(
                "c0", "Journal RIS 2020 Fallback",
                "c1", "1234-5678",
                "c2", "0.88",
                "c3", "N/A"
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(0.88, result.records().get(0).value(), 0.001);
    }

    @Test
    void parseAis2021PreservesEditionFromParsedCategoryField() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2021", Map.of(
                "c0", "Journal 2021 Edition",
                "c1", "1234-5678",
                "c2", "8765-4321",
                "c3", 0.5,
                "c4", "SSCI",
                "c5", "PHYSICS - SCIE",
                "c6", 1.0
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(EditionNormalized.SCIE, result.records().get(0).editionNormalized());
        assertEquals("PHYSICS", result.records().get(0).categoryNameCanonical());
    }

    @Test
    void parseAis2020ReturnsNullQuarterWhenC6Absent() throws Exception {
        WosParsedEventResult result = parser.parse(event("AIS", "2020", Map.of(
                "c0", "Journal Quarter Blank",
                "c1", "1234-5678",
                "c2", "8765-4321",
                "c3", 0.5,
                "c4", "SCIE",
                "c5", "ACOUSTICS"
        )));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertNull(result.records().get(0).quarter());
    }

    private WosImportEvent event(String metricType, String year, Map<String, Object> cells) throws Exception {
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-" + metricType + "-" + year);
        event.setSourceType(WosSourceType.GOV_AIS_RIS);
        event.setSourceFile(metricType + "_" + year + ".xlsx");
        event.setSourceVersion("v" + year);
        event.setSourceRowItem("1");
        event.setPayloadFormat("excel-row");
        event.setPayload(objectMapper.writeValueAsString(Map.of(
                "metricType", metricType,
                "year", year,
                "cells", cells
        )));
        return event;
    }
}
