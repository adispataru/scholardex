package ro.uvt.pokedex.core.service.importing.wos.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WosParsedEventResultTest {

    @Test
    void parsed_hasParsedStatusAndRecordsAndNullMessage() {
        WosParsedRecord rec = new WosParsedRecord(
                "Journal", "1234-5678", null, 2023, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        WosParsedEventResult result = WosParsedEventResult.parsed(List.of(rec));

        assertEquals(WosParsedEventStatus.PARSED, result.status());
        assertEquals(1, result.records().size());
        assertNull(result.message());
    }

    @Test
    void skipped_hasSkippedStatusAndEmptyRecordsAndMessage() {
        WosParsedEventResult result = WosParsedEventResult.skipped("already processed");

        assertEquals(WosParsedEventStatus.SKIPPED, result.status());
        assertTrue(result.records().isEmpty());
        assertEquals("already processed", result.message());
    }

    @Test
    void error_hasErrorStatusAndEmptyRecordsAndMessage() {
        WosParsedEventResult result = WosParsedEventResult.error("parse failed");

        assertEquals(WosParsedEventStatus.ERROR, result.status());
        assertTrue(result.records().isEmpty());
        assertEquals("parse failed", result.message());
    }

    @Test
    void empty_returnsContextWithAllNullFields() {
        WosIdentitySourceContext ctx = WosIdentitySourceContext.empty();

        assertNull(ctx.year());
        assertNull(ctx.editionRaw());
        assertNull(ctx.sourceEventId());
        assertNull(ctx.sourceFile());
        assertNull(ctx.sourceVersion());
        assertNull(ctx.sourceRowItem());
    }
}
