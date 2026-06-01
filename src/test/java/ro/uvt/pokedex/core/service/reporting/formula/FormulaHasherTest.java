package ro.uvt.pokedex.core.service.reporting.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaHasherTest {

    @Test
    void producesLowercaseHexSha256() {
        String hash = FormulaHasher.hash("S * N");
        assertEquals(64, hash.length(), "SHA-256 hex is 64 chars");
        assertTrue(hash.matches("[0-9a-f]+"), "must be lowercase hex");
    }

    @Test
    void cosmeticEditsKeepHashStable() {
        String a = FormulaHasher.hash("(S > 1.0) ? (3 + 3 * S) : (3 + S)");
        String b = FormulaHasher.hash("  (S > 1.0)\t?\n(3 + 3 * S) : (3 + S)  ");
        assertEquals(a, b);
    }

    @Test
    void maxRewriteHashStability() {
        // "max" and "Math.max" canonicalize to the same form → same hash.
        assertEquals(
                FormulaHasher.hash("max(S, 1)"),
                FormulaHasher.hash("Math.max(S, 1)")
        );
    }

    @Test
    void semanticEditChangesHash() {
        assertNotEquals(
                FormulaHasher.hash("S + N"),
                FormulaHasher.hash("S - N")
        );
        assertNotEquals(
                FormulaHasher.hash("3 + 3 * S"),
                FormulaHasher.hash("(3 + 3) * S")
        );
    }

    @Test
    void lineCommentStrippingKeepsHashStable() {
        assertEquals(
                FormulaHasher.hash("S * M // economics journal multiplier"),
                FormulaHasher.hash("S * M")
        );
    }

    @Test
    void rejectsNullAndBlank() {
        assertThrows(NullPointerException.class, () -> FormulaHasher.hash(null));
        assertThrows(IllegalArgumentException.class, () -> FormulaHasher.hash("  "));
    }

    @Test
    void canonicalShortcutMatchesFullPath() {
        String canonical = FormulaCanonicalizer.canonicalize("max(S, N)");
        assertEquals(
                FormulaHasher.hash("max(S, N)"),
                FormulaHasher.hashCanonical(canonical)
        );
    }
}
