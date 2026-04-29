package ro.uvt.pokedex.core.service.importing.wos.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WosParserRunSummaryTest {

    // --- counters ---

    @Test
    void markProcessed_incrementsProcessedCount() {
        WosParserRunSummary s = new WosParserRunSummary(5);
        s.markProcessed();
        s.markProcessed();
        assertEquals(2, s.getProcessedCount());
    }

    @Test
    void markParsed_incrementsParsedCount() {
        WosParserRunSummary s = new WosParserRunSummary(5);
        s.markParsed();
        assertEquals(1, s.getParsedCount());
    }

    @Test
    void markSkipped_incrementsSkippedCount() {
        WosParserRunSummary s = new WosParserRunSummary(5);
        s.markSkipped("reason");
        assertEquals(1, s.getSkippedCount());
    }

    @Test
    void markError_incrementsErrorCount() {
        WosParserRunSummary s = new WosParserRunSummary(5);
        s.markError("reason");
        assertEquals(1, s.getErrorCount());
    }

    // --- sample collection ---

    @Test
    void markSkipped_addsSampleWhenBelowCap() {
        WosParserRunSummary s = new WosParserRunSummary(3);
        s.markSkipped("a");
        s.markSkipped("b");
        assertEquals(2, s.getSamples().size());
        assertTrue(s.getSamples().contains("a"));
        assertTrue(s.getSamples().contains("b"));
    }

    @Test
    void markError_addsSampleWhenBelowCap() {
        WosParserRunSummary s = new WosParserRunSummary(3);
        s.markError("err1");
        assertEquals(1, s.getSamples().size());
        assertEquals("err1", s.getSamples().get(0));
    }

    @Test
    void addSample_stopsAtMaxSamples() {
        WosParserRunSummary s = new WosParserRunSummary(2);
        s.markSkipped("a");
        s.markSkipped("b");
        s.markSkipped("c"); // should not be added
        assertEquals(2, s.getSamples().size());
    }

    @Test
    void addSample_nullSample_notAdded() {
        WosParserRunSummary s = new WosParserRunSummary(5);
        s.markSkipped(null);
        assertEquals(1, s.getSkippedCount());
        assertTrue(s.getSamples().isEmpty());
    }

    @Test
    void addSample_blankSample_notAdded() {
        WosParserRunSummary s = new WosParserRunSummary(5);
        s.markSkipped("   ");
        assertEquals(1, s.getSkippedCount());
        assertTrue(s.getSamples().isEmpty());
    }

    @Test
    void getSamples_returnsUnmodifiableView() {
        WosParserRunSummary s = new WosParserRunSummary(5);
        s.markSkipped("x");
        assertThrows(UnsupportedOperationException.class, () -> s.getSamples().add("y"));
    }

    // --- constructor maxSamples guard ---

    @Test
    void constructor_negativeMaxSamples_treatedAsZero() {
        WosParserRunSummary s = new WosParserRunSummary(-1);
        s.markSkipped("a");
        assertTrue(s.getSamples().isEmpty()); // cap clamped to 0
    }
}
