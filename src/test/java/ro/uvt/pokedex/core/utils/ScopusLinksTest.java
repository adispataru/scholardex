package ro.uvt.pokedex.core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScopusLinksTest {

    @Test
    void buildsTheRecordLinkFromAnEid() {
        assertEquals("https://www.scopus.com/inward/record.uri?partnerID=HzOxMe3b&scp=85012345678&origin=inward",
                ScopusLinks.recordUrl(" 2-s2.0-85012345678 "));
    }

    @Test
    void noLinkWithoutAScopusEid() {
        assertNull(ScopusLinks.recordUrl(null));
        assertNull(ScopusLinks.recordUrl(""));
        assertNull(ScopusLinks.recordUrl("W2741809807"));
        assertNull(ScopusLinks.recordUrl("2-s2.0-85012\"><script>"));
    }
}
