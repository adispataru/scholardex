package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WosImportEventParserOrchestratorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesMixedEventsWithDeterministicSummary() throws Exception {
        WosImportEventRepository repository = mock(WosImportEventRepository.class);
        GovAisRisImportEventParser govParser = new GovAisRisImportEventParser(objectMapper);
        OfficialWosJsonImportEventParser jsonParser = new OfficialWosJsonImportEventParser(objectMapper);
        WosImportEventParserOrchestrator orchestrator =
                new WosImportEventParserOrchestrator(repository, List.of(govParser, jsonParser));

        WosImportEvent govEvent = govEvent("AIS", "2023", "2", Map.of(
                "c0", "Acoustics",
                "c1", "N/A",
                "c2", "2624-599X",
                "c3", "ACOUSTICS",
                "c4", "ESCI",
                "c5", "0.412"
        ));
        WosImportEvent jsonEvent = jsonEvent("1", Map.of(
                "journalTitle", "Journal T",
                "year", 2019,
                "edition", "SCIE",
                "issn", "1234-5678",
                "articleInfluenceScore", 0.2,
                "journalImpactFactor", 0.8,
                "categoryName", "ACOUSTICS",
                "rank", 2
        ));
        WosImportEvent unsupported = new WosImportEvent();
        unsupported.setId("ev-x");
        unsupported.setSourceType(WosSourceType.GOV_AIS_RIS);
        unsupported.setPayloadFormat("unknown");
        unsupported.setSourceFile("x");
        unsupported.setSourceVersion("v");
        unsupported.setSourceRowItem("3");
        unsupported.setPayload("{}");

        when(repository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(govEvent, unsupported, jsonEvent));

        WosParserRunResult result = orchestrator.parseAllEvents();

        assertEquals(3, result.summary().getProcessedCount());
        assertEquals(2, result.summary().getParsedCount());
        assertEquals(1, result.summary().getSkippedCount());
        assertEquals(0, result.summary().getErrorCount());
        assertEquals(3, result.records().size());
        assertTrue(result.records().stream().anyMatch(r -> "wos-json-1997-2019/journals-SCIE-year-2019.json".equals(r.sourceFile())));
    }

    private WosImportEvent govEvent(String metric, String year, String rowItem, Map<String, Object> cells) throws Exception {
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-gov-" + rowItem);
        event.setSourceType(WosSourceType.GOV_AIS_RIS);
        event.setSourceFile(metric + "_" + year + ".xlsx");
        event.setSourceVersion("v" + year);
        event.setSourceRowItem(rowItem);
        event.setPayloadFormat("excel-row");
        event.setPayload(objectMapper.writeValueAsString(Map.of(
                "metricType", metric,
                "year", year,
                "cells", cells
        )));
        return event;
    }

    private WosImportEvent jsonEvent(String rowItem, Map<String, Object> payload) throws Exception {
        WosImportEvent event = new WosImportEvent();
        event.setId("ev-json-" + rowItem);
        event.setSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT);
        event.setSourceFile("wos-json-1997-2019/journals-SCIE-year-2019.json");
        event.setSourceVersion("v2019");
        event.setSourceRowItem(rowItem);
        event.setPayloadFormat("json-item");
        event.setPayload(objectMapper.writeValueAsString(payload));
        return event;
    }
}
