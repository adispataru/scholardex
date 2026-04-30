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

        assertTrue(CanonicalizationSupport.isBlank(null));
        assertTrue(CanonicalizationSupport.isBlank(" "));
        assertFalse(CanonicalizationSupport.isBlank("x"));

        assertEquals(12L, CanonicalizationSupport.nanosToMillis(12_900_000L));
        assertEquals(List.of(), CanonicalizationSupport.safeList(null));
        assertEquals(List.of("a"), CanonicalizationSupport.safeList(List.of("a")));
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
}
