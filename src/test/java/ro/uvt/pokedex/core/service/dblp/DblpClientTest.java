package ro.uvt.pokedex.core.service.dblp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DblpClientTest {

    @Test
    void derivesConferenceSeriesStreamFromRecordKey() {
        // conf/<stream>/<record> → conf/<stream>; the year-edition record id is dropped (series is the forum grain).
        assertEquals("conf/ispdc", DblpClient.conferenceStreamKey("conf/ispdc/Filelis-Papadopoulos17"));
        assertEquals("conf/iccs", DblpClient.conferenceStreamKey("conf/iccs/SmithJ19"));
    }

    @Test
    void returnsNullForNonConferenceOrMalformedKeys() {
        assertNull(DblpClient.conferenceStreamKey("journals/tocs/SmithJ19")); // journal, not a conference
        assertNull(DblpClient.conferenceStreamKey("conf/iccs"));               // no record segment
        assertNull(DblpClient.conferenceStreamKey(null));
        assertNull(DblpClient.conferenceStreamKey("books/sp/Knuth73"));
    }
}
