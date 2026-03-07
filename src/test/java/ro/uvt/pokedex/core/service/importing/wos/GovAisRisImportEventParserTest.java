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
        assertEquals(MetricType.AIS, result.records().get(0).metricType());
        assertEquals(EditionNormalized.SCIE, result.records().get(0).editionNormalized());
        assertEquals("ACOUSTICS", result.records().get(0).categoryNameCanonical());
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
        assertEquals(EditionNormalized.SSCI, result.records().get(0).editionNormalized());
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
