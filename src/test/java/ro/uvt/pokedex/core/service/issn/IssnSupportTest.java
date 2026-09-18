package ro.uvt.pokedex.core.service.issn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssnSupportTest {

    @Test
    void realIssnsPassTheCheckDigitIncludingTheXForm() {
        assertTrue(IssnSupport.isValid("1583-7165")); // Anale. Seria Informatică (Tibiscus)
        assertTrue(IssnSupport.isValid("2068-3227")); // GeoGebra journal
        assertTrue(IssnSupport.isValid("1841-3307")); // Annals of West University of Timișoara, Math-CS
        assertTrue(IssnSupport.isValid("2434-561X"));
        assertTrue(IssnSupport.isValid("2434-561x"));
    }

    @Test
    void aTypoFailsTheCheckDigit() {
        assertFalse(IssnSupport.isValid("1234-5678"));
        assertFalse(IssnSupport.isValid("1583-7166"));
    }

    @Test
    void shapesAreNormalizedAndJunkIsRejected() {
        assertEquals("1583-7165", IssnSupport.normalize("15837165"));
        assertEquals("1583-7165", IssnSupport.normalize(" ISSN 1583 7165 "));
        assertEquals("2434-561X", IssnSupport.normalize("2434-561x"));
        assertNull(IssnSupport.normalize("1583-716"));
        assertNull(IssnSupport.normalize("X583-7165"));
        assertNull(IssnSupport.normalize(null));
        assertFalse(IssnSupport.isValid(""));
    }
}
