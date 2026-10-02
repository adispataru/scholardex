package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CitationCountSupportTest {

    @Test
    void anotherRecordOfTheSameSourceNeverLowersTheNumberButTheSameRecordRefreshesIt() {
        assertEquals(Integer.valueOf(140), CitationCountSupport.sourceCount(true, 140, 32));
        assertEquals(Integer.valueOf(140), CitationCountSupport.sourceCount(true, 32, 140));
        assertEquals(Integer.valueOf(32), CitationCountSupport.sourceCount(false, 140, 32));
        assertEquals(Integer.valueOf(140), CitationCountSupport.sourceCount(false, 140, null));
        assertEquals(Integer.valueOf(32), CitationCountSupport.sourceCount(true, null, 32));
    }

    @Test
    void theScalarIsTheBestOfTheTwoSourcesAndTheStoredValueStandsInForAnUnknownOne() {
        assertEquals(Integer.valueOf(19), CitationCountSupport.scalar(16, 19));
        assertNull(CitationCountSupport.scalar(null, null));
        // both known: the stored scalar does not matter any more (a stale value cannot win)
        assertEquals(Integer.valueOf(19), CitationCountSupport.scalar(16, 19, 99));
        // one unknown (pre-H131 record, or a single-source work): the stored scalar is kept as a floor
        assertEquals(Integer.valueOf(99), CitationCountSupport.scalar(null, 10, 99));
        assertEquals(Integer.valueOf(45), CitationCountSupport.scalar(45, null, 2));
        assertNull(CitationCountSupport.scalar(null, null, null));
    }
}
