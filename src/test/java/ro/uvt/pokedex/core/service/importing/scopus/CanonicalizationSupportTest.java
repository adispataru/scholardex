package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalizationSupportTest {

    @Test
    void normalizationHashAndSafetyHelpersCoverBranches() {
        assertNull(CanonicalizationSupport.normalizeBlank(null));
        assertNull(CanonicalizationSupport.normalizeBlank("   "));
        assertEquals("x", CanonicalizationSupport.normalizeBlank(" x "));

        assertEquals("", CanonicalizationSupport.normalizeToken(null));
        assertEquals("", CanonicalizationSupport.normalizeToken("   "));
        assertEquals("mixed", CanonicalizationSupport.normalizeToken(" MiXeD "));

        assertNull(CanonicalizationSupport.normalizeName(null));
        assertEquals("ecole polytechnique", CanonicalizationSupport.normalizeName("École  Polytechnique"));
        assertNull(CanonicalizationSupport.normalizeName(" !!! "));

        String shortHash = CanonicalizationSupport.shortHash("material");
        assertEquals(24, shortHash.length());
        assertTrue(shortHash.matches("[0-9a-f]{24}"));

        // H56: hashing was reimplemented for speed; pin the output so persisted deterministic ids
        // (spub_/sauth_/sae_/…) can never silently change. SHA-256("abc") is a NIST test vector.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CanonicalizationSupport.sha256Hex("abc"));
        assertEquals("ba7816bf8f01cfea414140de", CanonicalizationSupport.shortHash("abc"));
        // matches the previous MessageDigest + String.format("%02x") implementation, incl. unicode
        assertEquals(legacySha256Hex("spub|10.1000/χρ|2024"), CanonicalizationSupport.sha256Hex("spub|10.1000/χρ|2024"));

        assertTrue(CanonicalizationSupport.isBlank(null));
        assertTrue(CanonicalizationSupport.isBlank(" "));
        assertFalse(CanonicalizationSupport.isBlank("x"));

        assertEquals(12L, CanonicalizationSupport.nanosToMillis(12_900_000L));
        assertEquals(List.of(), CanonicalizationSupport.safeList(null));
        assertEquals(List.of("a"), CanonicalizationSupport.safeList(List.of("a")));

        // H72: verified Scopus institution profiles (60…) vs ad-hoc raw-string ids (1xxxxxxxx)
        assertTrue(CanonicalizationSupport.isVerifiedScopusAffiliationId("60000434"));
        assertTrue(CanonicalizationSupport.isVerifiedScopusAffiliationId(" 60091123 "));
        assertFalse(CanonicalizationSupport.isVerifiedScopusAffiliationId("112945959"));
        assertFalse(CanonicalizationSupport.isVerifiedScopusAffiliationId("122024502"));
        assertFalse(CanonicalizationSupport.isVerifiedScopusAffiliationId(null));
    }

    @Test
    void startBatchAndAddUniqueCoverFallbackPaths() {
        assertEquals(3, CanonicalizationSupport.normalizeStartBatch(3, 10, true));
        assertEquals(0, CanonicalizationSupport.normalizeStartBatch(-5, 10, true));
        assertEquals(11, CanonicalizationSupport.normalizeStartBatch(null, 10, true));
        assertEquals(0, CanonicalizationSupport.normalizeStartBatch(null, -1, true));
        assertEquals(0, CanonicalizationSupport.normalizeStartBatch(null, 10, false));

        List<String> values = new ArrayList<>();
        CanonicalizationSupport.addUnique(values, null);
        CanonicalizationSupport.addUnique(values, " ");
        CanonicalizationSupport.addUnique(values, "x");
        CanonicalizationSupport.addUnique(values, "x");
        assertEquals(List.of("x"), values);
    }

    /** The pre-H56 implementation, kept verbatim as the comparison oracle. */
    private static String legacySha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
