package ro.uvt.pokedex.core.service.reporting.formula;

import java.util.Objects;

/**
 * H52 slice 5 + 8b: produces a canonical text form of an indicator formula. Output
 * is what {@link FormulaHasher} hashes for the {@code Indicator.formulaHash} field
 * and what {@link FormulaEvaluator} uses as the compile-cache key.
 *
 * <p>Pipeline (in order):</p>
 *
 * <ol>
 *   <li>Apply the same {@code max(...)} / {@code min(...)} → {@code Math.max(...)} /
 *       {@code Math.min(...)} rewrite as {@link FormulaEvaluator}, so the two stay in
 *       lockstep.</li>
 *   <li>Strip {@code // ...} line comments — MVEL ignores them at eval time, so they
 *       must not affect identity either.</li>
 *   <li>Tokenize via {@link FormulaTokenizer} and re-emit with normalized spacing
 *       (slice 8b). This closes the {@code "S+1"} vs {@code "S + 1"} gap that the
 *       slice-5 text-level whitespace-collapse had — both shapes now produce the
 *       same canonical form, same hash.</li>
 * </ol>
 *
 * <p>Deliberately <strong>not</strong> normalized (would change semantics):</p>
 * <ul>
 *   <li>Operator precedence parens — {@code 3 + 3 * S} ≠ {@code (3 + 3) * S}.</li>
 *   <li>Numeric literal forms — {@code 1.0} ≠ {@code 1}: MVEL infers different types.</li>
 *   <li>Quoted-string contents — spaces inside {@code 'Director General'} stay as written.</li>
 *   <li>Variable identifier case — {@code Buget} and {@code buget} are distinct vars.</li>
 * </ul>
 *
 * <p><strong>Hash invalidation note (slice 8b).</strong> Existing {@code formulaHash}
 * values stamped under slice 5 are <em>different</em> from what slice 8b produces for
 * the same formula. Migration runner now detects the mismatch and re-stamps on its
 * next sweep; the {@code BeforeConvert} hook does the same on any admin save. The
 * downstream {@code userIndicatorResults} cache identity in commit 3 hasn't been
 * wired yet, so this invalidation is a free lunch.</p>
 */
public final class FormulaCanonicalizer {

    private FormulaCanonicalizer() {}

    /**
     * Produces the canonical text form of {@code rawFormula}. Pure function — same
     * input always yields the same output, no I/O, no statics.
     *
     * @throws NullPointerException if {@code rawFormula} is null
     * @throws IllegalArgumentException if {@code rawFormula} is blank
     */
    public static String canonicalize(String rawFormula) {
        Objects.requireNonNull(rawFormula, "rawFormula");
        if (rawFormula.isBlank()) {
            throw new IllegalArgumentException("formula must not be blank");
        }

        // Step 1: rewrite max/min to Math.*. Done first so the comment/whitespace passes
        // see the rewritten tokens (e.g. so a comment ending right after a rewritten
        // "Math.max(" is correctly stripped).
        String expr = FormulaEvaluator.rewrite(rawFormula).expression();

        // Step 2: strip MVEL/Java line comments. Block comments are not used in any
        // production formula and are deliberately left to slice 6's AST pass to handle.
        // Line-comment stripping is conservative: anything from "//" to end-of-line.
        StringBuilder noComments = new StringBuilder(expr.length());
        boolean inLineComment = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                    noComments.append(c);
                }
                continue;
            }
            if (c == '/' && i + 1 < expr.length() && expr.charAt(i + 1) == '/') {
                inLineComment = true;
                i++; // consume the second '/'
                continue;
            }
            noComments.append(c);
        }

        // Step 3 (slice 8b): tokenize and re-emit with normalized spacing. Folds
        // "S+1" / "S + 1" / "S\t+\n1" into a single canonical form. The tokenizer
        // preserves quoted-string contents verbatim, so 'Director General' keeps its
        // internal space.
        return FormulaTokenizer.normalize(FormulaTokenizer.tokenize(noComments.toString()));
    }
}
