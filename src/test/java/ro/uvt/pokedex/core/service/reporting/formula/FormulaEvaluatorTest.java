package ro.uvt.pokedex.core.service.reporting.formula;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mvel2.MVEL;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H52 slice 4 — proves {@link FormulaEvaluator} reproduces the pre-v1 inline MVEL
 * behavior bit-for-bit and adds the value the inline call sites couldn't have:
 * compile caching across iterations, centralized {@code max}/{@code min} rewrite,
 * and one tractable error contract.
 */
class FormulaEvaluatorTest {

    private FormulaEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new FormulaEvaluator();
    }

    // ---------- Rewrite ----------

    @Test
    void rewritesMaxToMathMax() {
        FormulaEvaluator.Rewritten r = FormulaEvaluator.rewrite("max(S, 1)");
        assertEquals("Math.max(S, 1)", r.expression());
        assertTrue(r.usesMath());
    }

    @Test
    void rewritesMinToMathMin() {
        FormulaEvaluator.Rewritten r = FormulaEvaluator.rewrite("min(S, 1)");
        assertEquals("Math.min(S, 1)", r.expression());
        assertTrue(r.usesMath());
    }

    @Test
    void leavesNonMathFormulaAlone() {
        FormulaEvaluator.Rewritten r = FormulaEvaluator.rewrite("(S > 1.0) ? (3 + 3 * S) : (3 + S)");
        assertEquals("(S > 1.0) ? (3 + 3 * S) : (3 + S)", r.expression());
        assertFalse(r.usesMath());
    }

    @Test
    void rewriteIsWordBoundary() {
        // The pre-v1 String#replaceAll("max", "Math.max") would have corrupted
        // "maxScore" into "Math.maxScore". The slice-4 rewriter is token-aware.
        FormulaEvaluator.Rewritten r = FormulaEvaluator.rewrite("maxScore + 1");
        assertEquals("maxScore + 1", r.expression());
        assertFalse(r.usesMath());
    }

    @Test
    void rewriteIsIdempotentOnAlreadyQualified() {
        // Math.max already qualified — second rewrite must not produce Math.Math.max.
        FormulaEvaluator.Rewritten r = FormulaEvaluator.rewrite("Math.max(S, 1)");
        assertEquals("Math.max(S, 1)", r.expression());
        // The final expression still references Math, so the evaluator must inject
        // the binding (MVEL won't resolve the bare token otherwise). usesMath is
        // computed from the *final* expression, not from whether we rewrote.
        assertTrue(r.usesMath());
    }

    @Test
    void rejectsNullAndBlank() {
        assertThrows(NullPointerException.class, () -> FormulaEvaluator.rewrite(null));
        assertThrows(IllegalArgumentException.class, () -> FormulaEvaluator.rewrite("  "));
    }

    // ---------- Evaluation ----------

    @Test
    void evalProducesSameResultAsInlineMvel() {
        // Equivalence test: every existing indicator formula passed through this
        // evaluator must match what the bare MVEL call would produce.
        String formula = "(S > 1.0) ? (3 + 3 * S) : (3 + S)";

        Map<String, Object> inlineVars = new HashMap<>();
        inlineVars.put("S", 2.5);
        double inline = MVEL.eval(formula, inlineVars, Double.class);

        FormulaContext ctx = FormulaContext.builder().put("S", 2.5).build();
        double routed = evaluator.eval(formula, ctx);

        assertEquals(inline, routed, 0.0);
    }

    @Test
    void evalSupportsMaxAndMinRewriteEndToEnd() {
        FormulaContext ctx = FormulaContext.builder().put("S", 2.0).put("N", 4.0).build();

        assertEquals(4.0, evaluator.eval("max(S, N)", ctx), 0.0);
        assertEquals(2.0, evaluator.eval("min(S, N)", ctx), 0.0);
    }

    @Test
    void evalEvaluatesScoreExtraAfterPutAll() {
        // Mirrors the EconomicsJournalStrategy contract: "M" multiplier in Score.extra
        // ends up in the variable bag via Builder#putAll.
        Map<String, Object> extras = new HashMap<>();
        extras.put("M", 2.0);

        FormulaContext ctx = FormulaContext.builder()
                .put("S", 3.0).put("N", 1.0).putAll(extras).build();

        assertEquals(6.0, evaluator.eval("S * M", ctx), 0.0);
    }

    // ---------- Compile cache ----------

    @Test
    void compileCacheAmortizesRepeatedEval() {
        String formula = "(S > 1.0) ? (3 + 3 * S) : (3 + S)";

        for (int i = 0; i < 50; i++) {
            FormulaContext ctx = FormulaContext.builder().put("S", (double) i).build();
            evaluator.eval(formula, ctx);
        }

        // One compile, 50 evaluations.
        assertEquals(1, evaluator.compileMissCount());
        assertEquals(1, evaluator.cacheSize());
    }

    @Test
    void compileCacheKeysOnCanonicalHash() {
        // Slice 5: cache key is the SHA-256 of the canonical form. "max(S, 1)" and
        // "Math.max(S, 1)" canonicalize identically → share one compiled expression.
        FormulaContext ctx = FormulaContext.builder().put("S", 5.0).build();
        evaluator.eval("max(S, 1)", ctx);
        evaluator.eval("Math.max(S, 1)", ctx);
        assertEquals(1, evaluator.compileMissCount());
    }

    @Test
    void whitespaceVariantsShareCompiledExpression() {
        // Cosmetically-different but semantically-equal formulas: same canonical form,
        // same hash, same compiled MVEL expression. The canonicalizer *collapses*
        // whitespace but does not delete it, so "S+1" and "S + 1" still differ; this
        // test exercises the collapse path.
        FormulaContext ctx = FormulaContext.builder().put("S", 2.0).build();
        evaluator.eval("S + 1", ctx);
        evaluator.eval("  S   +   1  ", ctx);
        evaluator.eval("S\t+\n1", ctx);
        assertEquals(1, evaluator.compileMissCount());
        assertEquals(1, evaluator.cacheSize());
    }

    @Test
    void tryEvalReturnsEmptyOnFailure() {
        // Unbound variable "X" → MVEL throws → tryEval converts to empty.
        FormulaContext ctx = FormulaContext.builder().put("S", 1.0).build();
        OptionalDouble result = evaluator.tryEval("X + 1", ctx);
        assertTrue(result.isEmpty());
    }

    @Test
    void evalPropagatesOnFailure() {
        FormulaContext ctx = FormulaContext.builder().put("S", 1.0).build();
        assertThrows(RuntimeException.class, () -> evaluator.eval("X + 1", ctx));
    }
}
