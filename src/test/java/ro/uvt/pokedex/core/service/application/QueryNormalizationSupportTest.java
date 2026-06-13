package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryNormalizationSupportTest {

    @Test
    void isValidIssnAcceptsWellFormedIssns() {
        // Real journals; hyphenated and compact forms both accepted.
        assertTrue(QueryNormalizationSupport.isValidIssn("0036-1410"));   // SIAM J. Math. Analysis (print)
        assertTrue(QueryNormalizationSupport.isValidIssn("00361410"));
        assertTrue(QueryNormalizationSupport.isValidIssn("0097-5397"));   // SIAM J. Computing (print)
        assertTrue(QueryNormalizationSupport.isValidIssn("1095-7111"));   // SIAM J. Computing (eISSN) — valid
        assertTrue(QueryNormalizationSupport.isValidIssn("1095-7154"));   // SIAM J. Math. Analysis (eISSN) — valid
    }

    @Test
    void isValidIssnAcceptsXCheckDigit() {
        assertTrue(QueryNormalizationSupport.isValidIssn("1050-124X")); // X check digit (weighted sum 56 ≡ 1 mod 11)
        assertTrue(QueryNormalizationSupport.isValidIssn("1050124x"));  // lower-case x normalised
    }

    @Test
    void isValidIssnRejectsRealSourceTypos() {
        // The check-digit-invalid values found in the live forum data (H55 cleanup).
        assertFalse(QueryNormalizationSupport.isValidIssn("0030-211X")); // RADICAL PHILOS typo (→ 0300-211X)
        assertFalse(QueryNormalizationSupport.isValidIssn("1929-7291")); // Invasive Plant Science and Management
        assertFalse(QueryNormalizationSupport.isValidIssn("0747-9664")); // JOURNAL OF ENGINEERING TECHNOLOGY
        assertFalse(QueryNormalizationSupport.isValidIssn("2150-0136")); // World Journal Pediatric/Congenital
        assertFalse(QueryNormalizationSupport.isValidIssn("1745-8212")); // Central Europe
    }

    @Test
    void isValidIssnRejectsMalformedShapes() {
        assertFalse(QueryNormalizationSupport.isValidIssn(null));
        assertFalse(QueryNormalizationSupport.isValidIssn(""));
        assertFalse(QueryNormalizationSupport.isValidIssn("2533820"));   // 7 chars (missing hyphen+digit)
        assertFalse(QueryNormalizationSupport.isValidIssn("0036-141"));  // too short
        assertFalse(QueryNormalizationSupport.isValidIssn("0036-14100")); // too long
        assertFalse(QueryNormalizationSupport.isValidIssn("ABCD-EFGX")); // non-numeric body
        assertFalse(QueryNormalizationSupport.isValidIssn("0036-141X")); // wrong check digit for 0036-141
    }

    @Test
    void normalizeIssnStaysPermissiveForPrefixSearch() {
        // Must NOT enforce the check digit — partial ISSNs are legitimate prefix-search input.
        assertEquals("0036", QueryNormalizationSupport.normalizeIssn("0036"));
        assertEquals("0030211X", QueryNormalizationSupport.normalizeIssn("0030-211X")); // invalid but still normalised
        assertNull(QueryNormalizationSupport.normalizeIssn("   "));
    }
}
