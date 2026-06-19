package ro.uvt.pokedex.core.service.openalex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrcidSupportTest {

    @Test
    void normalizesBareUrlAndDigitsForms() {
        assertEquals("0000-0002-1825-0097", OrcidSupport.normalize("0000-0002-1825-0097"));
        assertEquals("0000-0002-1825-0097", OrcidSupport.normalize("https://orcid.org/0000-0002-1825-0097"));
        assertEquals("0000-0002-1825-0097", OrcidSupport.normalize("0000000218250097"));
        assertEquals("0000-0002-1825-0097", OrcidSupport.normalize("  0000-0002-1825-0097  "));
    }

    @Test
    void preservesTrailingXChecksum() {
        assertEquals("0000-0002-1825-009X", OrcidSupport.normalize("0000-0002-1825-009x"));
    }

    @Test
    void rejectsBlankAndMalformed() {
        assertNull(OrcidSupport.normalize(null));
        assertNull(OrcidSupport.normalize(""));
        assertNull(OrcidSupport.normalize("not-an-orcid"));
        assertNull(OrcidSupport.normalize("0000-0002-1825"));
    }
}
