package ro.uvt.pokedex.core.service.reporting.formula;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * H52 slice 8b — proves the tokenizer + normalizer collapses cosmetic spacing without
 * touching semantic content. Covers every operator and literal shape that appears in
 * the production indicator catalog.
 */
class FormulaTokenizerTest {

    // ---------- Spacing normalization ----------

    @Test
    void spacedAndUnspacedFormulasNormalizeToSameForm() {
        // The slice-5 gap: "S+1" and "S + 1" hashed differently. Slice 8b closes it.
        String a = FormulaTokenizer.normalize(FormulaTokenizer.tokenize("S+1"));
        String b = FormulaTokenizer.normalize(FormulaTokenizer.tokenize("S + 1"));
        String c = FormulaTokenizer.normalize(FormulaTokenizer.tokenize("S\t+\n1"));
        assertEquals(a, b);
        assertEquals(a, c);
    }

    @Test
    void functionCallStaysTightWithNoSpaceBeforeParen() {
        assertEquals("Math.max(S, 1)",
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("Math.max( S , 1 )")));
        assertEquals("Math.max(S, 1)",
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("Math.max(S,1)")));
    }

    @Test
    void ternaryNormalizes() {
        String canon = FormulaTokenizer.normalize(FormulaTokenizer.tokenize("(S>1.0)?(3+3*S):(3+S)"));
        assertEquals("(S > 1.0) ? (3 + 3 * S) : (3 + S)", canon);
    }

    @Test
    void statementSeparatorTakesNoSpaceBeforeOneSpaceAfter() {
        String canon = FormulaTokenizer.normalize(FormulaTokenizer.tokenize("B=Buget;X=B<50000?1:2"));
        assertEquals("B = Buget; X = B < 50000 ? 1 : 2", canon);
    }

    @Test
    void multiCharOperatorsAreSingleTokens() {
        assertEquals("Rol == 'Membru' ? X : X * 2",
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("Rol=='Membru'?X:X*2")));
        assertEquals("S >= 1 && N <= 3",
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("S>=1&&N<=3")));
        assertEquals("S != 0 || N != 0",
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("S!=0||N!=0")));
    }

    // ---------- Semantic-preserving rules ----------

    @Test
    void parensAffectCanonicalForm() {
        // Operator-precedence parens must survive; otherwise the canonical form would
        // claim semantically-different formulas are equal.
        assertNotEquals(
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("3 + 3 * S")),
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("(3 + 3) * S"))
        );
    }

    @Test
    void numericLiteralFormsAffectCanonicalForm() {
        // 1 (int) and 1.0 (double) coerce differently in MVEL — keep them distinct.
        assertNotEquals(
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("S + 1")),
                FormulaTokenizer.normalize(FormulaTokenizer.tokenize("S + 1.0"))
        );
    }

    @Test
    void quotedStringsArePreservedVerbatim() {
        // Spaces inside a quoted string must not be folded — 'Director General' stays
        // as-is. Single and double quotes both supported.
        String single = FormulaTokenizer.normalize(FormulaTokenizer.tokenize("Rol=='Director General'?2:1"));
        String dbl = FormulaTokenizer.normalize(FormulaTokenizer.tokenize("Rol==\"Director General\"?2:1"));
        org.junit.jupiter.api.Assertions.assertTrue(single.contains("'Director General'"), single);
        org.junit.jupiter.api.Assertions.assertTrue(dbl.contains("\"Director General\""), dbl);
    }

    // ---------- Token-stream correctness ----------

    @Test
    void identifiersIncludeDotQualifiedNames() {
        List<FormulaTokenizer.Token> toks = FormulaTokenizer.tokenize("Math.max(S, 1)");
        assertEquals(FormulaTokenizer.Type.IDENT, toks.get(0).type());
        assertEquals("Math.max", toks.get(0).text());
        assertEquals(FormulaTokenizer.Type.LPAREN, toks.get(1).type());
    }

    @Test
    void numbersIncludeDecimalAndExponent() {
        List<FormulaTokenizer.Token> toks = FormulaTokenizer.tokenize("1.5e2 + 0.25");
        assertEquals("1.5e2", toks.get(0).text());
        assertEquals("0.25", toks.get(2).text());
        assertEquals(FormulaTokenizer.Type.NUMBER, toks.get(0).type());
        assertEquals(FormulaTokenizer.Type.NUMBER, toks.get(2).type());
    }

    @Test
    void invalidExponentRewindsAndLeavesSuffixAsIdentifier() {
        List<FormulaTokenizer.Token> toks = FormulaTokenizer.tokenize("1e + 2E- + 3E+4");

        assertEquals(8, toks.size());
        assertEquals("1", toks.get(0).text());
        assertEquals(FormulaTokenizer.Type.NUMBER, toks.get(0).type());
        assertEquals("e", toks.get(1).text());
        assertEquals(FormulaTokenizer.Type.IDENT, toks.get(1).type());
        assertEquals("2", toks.get(3).text());
        assertEquals("E", toks.get(4).text());
        assertEquals("-", toks.get(5).text());
        assertEquals("+", toks.get(6).text());
        assertEquals("3E+4", toks.get(7).text());
        assertEquals(FormulaTokenizer.Type.NUMBER, toks.get(7).type());
    }

    @Test
    void escapedAndUnterminatedStringsAreSingleStringTokens() {
        List<FormulaTokenizer.Token> escaped = FormulaTokenizer.tokenize("'Director\\'s Office' + \"A\\\"B\"");
        assertEquals("'Director\\'s Office'", escaped.get(0).text());
        assertEquals(FormulaTokenizer.Type.STRING, escaped.get(0).type());
        assertEquals("\"A\\\"B\"", escaped.get(2).text());
        assertEquals(FormulaTokenizer.Type.STRING, escaped.get(2).type());

        List<FormulaTokenizer.Token> unterminated = FormulaTokenizer.tokenize("'still a string");
        assertEquals(1, unterminated.size());
        assertEquals("'still a string", unterminated.get(0).text());
        assertEquals(FormulaTokenizer.Type.STRING, unterminated.get(0).type());
    }

    @Test
    void dotChainsOnlyJoinWhenDotIsImmediatelyFollowedByIdentifierStart() {
        List<FormulaTokenizer.Token> spaced = FormulaTokenizer.tokenize("Math . max + A.1 + B._ok");

        assertEquals("Math", spaced.get(0).text());
        assertEquals(".", spaced.get(1).text());
        assertEquals(FormulaTokenizer.Type.OP, spaced.get(1).type());
        assertEquals("max", spaced.get(2).text());
        assertEquals("A", spaced.get(4).text());
        assertEquals(".", spaced.get(5).text());
        assertEquals("1", spaced.get(6).text());
        assertEquals("B._ok", spaced.get(8).text());
        assertEquals(FormulaTokenizer.Type.IDENT, spaced.get(8).type());
    }

    @Test
    void arrowAndUnknownCharactersAreOperatorTokens() {
        List<FormulaTokenizer.Token> toks = FormulaTokenizer.tokenize("x -> y @ z");

        assertEquals("x", toks.get(0).text());
        assertEquals("->", toks.get(1).text());
        assertEquals(FormulaTokenizer.Type.OP, toks.get(1).type());
        assertEquals("y", toks.get(2).text());
        assertEquals("@", toks.get(3).text());
        assertEquals(FormulaTokenizer.Type.OP, toks.get(3).type());
        assertEquals("z", toks.get(4).text());
    }

    @Test
    void productionFormulasRoundTripStably() {
        // Every formula that appears in production: tokenize → normalize → tokenize again.
        // The second pass produces the same tokens; canonical form is a fixed point.
        String[] formulas = {
                "S",
                "S * M",
                "S/max(N-2, 1)",
                "(S > 1.0) ? (3 + 3 * S) : (3 + S)",
                "S / N",
                "B = Buget; X = B < 50000 ? 1 : 2; Rol == 'Membru' ? X : X * 2",
        };
        for (String f : formulas) {
            String once = FormulaTokenizer.normalize(FormulaTokenizer.tokenize(f));
            String twice = FormulaTokenizer.normalize(FormulaTokenizer.tokenize(once));
            assertEquals(once, twice, "canonical form not idempotent for: " + f);
        }
    }
}
