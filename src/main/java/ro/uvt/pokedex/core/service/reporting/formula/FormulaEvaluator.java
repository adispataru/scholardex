package ro.uvt.pokedex.core.service.reporting.formula;

import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * H52 slice 4: typed entry point for indicator formula evaluation.
 *
 * <p>Replaces two divergent inline implementations
 * ({@code ScientificProductionService.getScore(...)} and {@code ActivityReportingService})
 * with one centralized policy:</p>
 *
 * <ol>
 *   <li><strong>Rewrite</strong>: {@code max(a,b)} → {@code Math.max(a,b)} and
 *       {@code min(a,b)} → {@code Math.min(a,b)}. Identical for every call site.</li>
 *   <li><strong>Compile cache</strong>: the rewritten expression string is the cache
 *       key. MVEL's compiled {@link Serializable} expression is reused across every
 *       evaluation — was previously recompiled per researcher iteration. Slice 5
 *       will swap the key to the persisted {@code formulaHash}.</li>
 *   <li><strong>{@code Math} injection</strong>: when the rewrite triggers, a mutable
 *       copy of the {@link FormulaContext} variable map gets {@code Math} added before
 *       it's handed to MVEL. The original immutable context is untouched.</li>
 * </ol>
 *
 * <p>Two entry points so call sites can keep their distinct error semantics from
 * before this refactor:</p>
 *
 * <ul>
 *   <li>{@link #eval(String, FormulaContext)} — propagates any MVEL exception. Used by
 *       the scientific-production hot path where a bad formula should fail the report,
 *       loudly.</li>
 *   <li>{@link #tryEval(String, FormulaContext)} — logs and returns an empty
 *       {@link OptionalDouble}. Used by the activity-reporting path which historically
 *       caught {@code PropertyAccessException} and substituted 0.0; the caller decides
 *       what to do on empty.</li>
 * </ul>
 *
 * <p><strong>Not yet a sandbox.</strong> Today the evaluator inherits MVEL's permissive
 * defaults (the rewritten expression can reach any class on the classpath via
 * fully-qualified names). The variable-allowlist parser in slice 5 will fix that.</p>
 */
@Component
@Slf4j
public class FormulaEvaluator {

    private final ConcurrentMap<String, Serializable> compileCache = new ConcurrentHashMap<>();
    private final LongAdder compileMisses = new LongAdder();

    /**
     * Evaluates the formula and returns the result. Any exception from MVEL (parse
     * error, unbound variable, type coercion, etc.) is rethrown to the caller.
     *
     * @throws NullPointerException if {@code rawFormula} or {@code ctx} is null
     * @throws IllegalArgumentException if {@code rawFormula} is blank
     */
    public double eval(String rawFormula, FormulaContext ctx) {
        // Canonical form folds whitespace + the max/min rewrite. Hash of the canonical
        // form is the compile-cache key — two formulas that differ only in whitespace
        // share one compiled MVEL expression. The same hash is what gets persisted on
        // {@code Indicator.formulaHash}, so commit-3's userIndicatorResults cache
        // identity lines up with the in-process compile cache.
        String canonical = FormulaCanonicalizer.canonicalize(rawFormula);
        Rewritten rewritten = rewrite(rawFormula); // reused for the usesMath flag only
        String cacheKey = FormulaHasher.hashCanonical(canonical);
        Serializable compiled = compileCache.computeIfAbsent(cacheKey, k -> {
            // H52 slice 7: run the denylist sandbox once per unique formula, at the
            // compile-cache miss. Same check fires at save time via the stamper;
            // this is the defense-in-depth path for anything that slipped past save.
            FormulaSandbox.assertSafe(canonical);
            compileMisses.increment();
            return MVEL.compileExpression(canonical);
        });

        // Always hand MVEL a mutable copy: production formulas use the statement
        // separator with intermediate assignments (e.g. {@code B = Buget; X = B < 50 ? 1 : 2; ...}),
        // and MVEL writes those bindings back into the map. The pre-v1 inline impl
        // happened to pass a freshly-built HashMap so this worked by accident; the
        // immutable {@link FormulaContext#variables()} view would throw
        // UnsupportedOperationException on the first assignment.
        Map<String, Object> vars = ctx.mutableCopy();
        if (rewritten.usesMath()) {
            vars.put(FormulaContext.RESERVED_MATH_KEY, Math.class);
        }
        return MVEL.executeExpression(compiled, vars, Double.class);
    }

    /**
     * Evaluates the formula and returns its result, or {@link OptionalDouble#empty()}
     * if MVEL threw. The exception is logged at WARN with the indicator-id-free
     * context the caller provides via the log message it emits afterwards.
     */
    public OptionalDouble tryEval(String rawFormula, FormulaContext ctx) {
        try {
            return OptionalDouble.of(eval(rawFormula, ctx));
        } catch (RuntimeException ex) {
            log.warn("Formula evaluation failed; returning empty. formula={}, error={}",
                    rawFormula, ex.toString());
            return OptionalDouble.empty();
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Visible for tests — counts every {@code computeIfAbsent} miss. Useful for proving
     * the cache actually amortizes across iterations.
     */
    public long compileMissCount() {
        return compileMisses.sum();
    }

    /**
     * Visible for tests — count of cached compiled expressions currently held.
     */
    public int cacheSize() {
        return compileCache.size();
    }

    /**
     * Applies the {@code max}/{@code min} → {@code Math.*} rewrite. The output also
     * carries a flag so callers know whether {@code Math} must be injected.
     *
     * <p>Idempotent — {@code Math.max} is left alone (we only match the bare token).</p>
     */
    static Rewritten rewrite(String rawFormula) {
        if (rawFormula == null) {
            throw new NullPointerException("formula must not be null");
        }
        if (rawFormula.isBlank()) {
            throw new IllegalArgumentException("formula must not be blank");
        }
        String expr = rawFormula;
        // Word-boundary rewrite so "max" only matches as a token, not as a substring of
        // a user variable like "maxScore". The negative lookbehind {@code (?<!Math\.)}
        // also keeps {@code Math.max(...)} alone so the rewriter is idempotent — the
        // pre-v1 inline impl used a bare String#replaceAll which would have corrupted
        // both "maxScore" and "Math.max" (→ "Math.Math.max").
        expr = expr.replaceAll("(?<!Math\\.)\\bmax\\s*\\(", "Math.max(");
        expr = expr.replaceAll("(?<!Math\\.)\\bmin\\s*\\(", "Math.min(");

        // Math binding is required whenever the *final* expression references it,
        // regardless of whether we rewrote (the input may have arrived already
        // qualified). MVEL can't resolve {@code Math} without either an import in
        // a ParserContext or a variable binding, so we choose the simpler latter.
        boolean usesMath = expr.contains("Math.");
        return new Rewritten(expr, usesMath);
    }

    /** Result of the {@link #rewrite} step. */
    record Rewritten(String expression, boolean usesMath) {}
}
