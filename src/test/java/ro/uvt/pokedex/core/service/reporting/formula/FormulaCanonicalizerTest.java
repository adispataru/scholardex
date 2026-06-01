package ro.uvt.pokedex.core.service.reporting.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormulaCanonicalizerTest {

    // ---------- Stability ----------

    @Test
    void cosmeticWhitespaceCollapsesToSingleForm() {
        // Slice 8b: tokenizer-based canonicalization folds spaced and unspaced shapes
        // into the same form. All four variants below produce identical canonical text.
        String a = FormulaCanonicalizer.canonicalize("(S > 1.0) ? (3 + 3 * S) : (3 + S)");
        String b = FormulaCanonicalizer.canonicalize("  (S>1.0)?(3+3*S):(3+S)  ");
        String c = FormulaCanonicalizer.canonicalize("(S > 1.0)\n  ? (3 + 3 * S)\n  : (3 + S)");
        String d = FormulaCanonicalizer.canonicalize("(S>1.0)\n?(3+3*S)\n:(3+S)");
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals(a, d);
    }

    @Test
    void spacedAndUnspacedOperatorsCanonicalizeIdentically() {
        // Slice 8b headline assertion: the gap the slice-5 canonicalizer had
        // ({@code "S+1"} vs {@code "S + 1"} hashing differently) is now closed.
        assertEquals(
                FormulaCanonicalizer.canonicalize("S+1"),
                FormulaCanonicalizer.canonicalize("S + 1")
        );
    }

    @Test
    void maxRewriteMakesUnqualifiedAndQualifiedFormsAgree() {
        // Formula A uses bare max; formula B uses Math.max already.
        // Both should canonicalize to the same form so editing one into the other
        // doesn't invalidate the userIndicatorResults cache.
        String a = FormulaCanonicalizer.canonicalize("max(S, 1)");
        String b = FormulaCanonicalizer.canonicalize("Math.max(S, 1)");
        assertEquals(a, b);
    }

    @Test
    void minRewriteMakesUnqualifiedAndQualifiedFormsAgree() {
        assertEquals(
                FormulaCanonicalizer.canonicalize("min(S, 1)"),
                FormulaCanonicalizer.canonicalize("Math.min(S, 1)")
        );
    }

    @Test
    void lineCommentsAreStripped() {
        String a = FormulaCanonicalizer.canonicalize("S * N // multiply by author count");
        String b = FormulaCanonicalizer.canonicalize("S * N");
        assertEquals(a, b);
    }

    // ---------- Semantic edits are NOT normalized ----------

    @Test
    void parensAffectCanonicalForm() {
        // Operator-precedence parens matter to the result; the canonicalizer must
        // not strip them. A semantically-different formula must hash differently
        // (slice 6 AST canonicalization may revisit this).
        assertNotEquals(
                FormulaCanonicalizer.canonicalize("3 + 3 * S"),
                FormulaCanonicalizer.canonicalize("(3 + 3) * S")
        );
    }

    @Test
    void numericLiteralFormsAffectCanonicalForm() {
        // MVEL infers int vs double from the literal form; the canonicalizer makes no
        // attempt to coerce. Document the limit.
        assertNotEquals(
                FormulaCanonicalizer.canonicalize("S + 1"),
                FormulaCanonicalizer.canonicalize("S + 1.0")
        );
    }

    @Test
    void quotedStringContentsArePreserved() {
        // Spaces inside a string literal must not be collapsed.
        String canon = FormulaCanonicalizer.canonicalize("Rol == 'Director General' ? 2 : 1");
        // The collapsed form keeps the quoted string verbatim.
        org.junit.jupiter.api.Assertions.assertTrue(canon.contains("'Director General'"),
                "quoted string was modified: " + canon);
    }

    // ---------- Edge cases ----------

    @Test
    void rejectsNullAndBlank() {
        assertThrows(NullPointerException.class, () -> FormulaCanonicalizer.canonicalize(null));
        assertThrows(IllegalArgumentException.class, () -> FormulaCanonicalizer.canonicalize("   "));
    }

    @Test
    void idempotent() {
        String once = FormulaCanonicalizer.canonicalize("max(S, N) + 1");
        String twice = FormulaCanonicalizer.canonicalize(once);
        assertEquals(once, twice);
    }
}
