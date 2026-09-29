package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaContext;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;

import java.util.OptionalDouble;

/**
 * Turns a Hirsch index into the indicator's points by evaluating the indicator formula with {@code S = h}.
 * Standards differ on what the h-index is worth: physics counts h itself (formula {@code S}), while the
 * 2026 Psihologie standard (Comisia 28, I13) counts {@code h × h} (formula {@code S*S}). Before this the
 * formula of an h-index indicator was never evaluated and the score was always h.
 *
 * <p>A blank formula, one that fails to evaluate, or a non-finite result keeps h — an h-index indicator
 * saved without a meaningful formula must not silently score 0.</p>
 *
 * <p>Holds its own evaluator on purpose: the evaluator is stateless apart from its compile cache, and a
 * constructor dependency on the report facade would ripple through every test that builds it.</p>
 */
public final class HIndexScoreSupport {

    private static final FormulaEvaluator EVALUATOR = new FormulaEvaluator();

    private HIndexScoreSupport() {
    }

    public static double score(Indicator indicator, int h) {
        String formula = indicator == null ? null : indicator.getFormula();
        if (formula == null || formula.isBlank()) {
            return h;
        }
        OptionalDouble value = EVALUATOR.tryEval(formula,
                FormulaContext.builder().put("S", (double) h).build());
        return value.isPresent() && Double.isFinite(value.getAsDouble()) ? value.getAsDouble() : h;
    }

    /** {@code 9} for an integral score, {@code 2.25} otherwise — the h-index total used to print as an int. */
    public static String format(double score) {
        return score == Math.rint(score) && Math.abs(score) < 1e15
                ? String.valueOf((long) score)
                : String.format(java.util.Locale.ROOT, "%.2f", score);
    }
}
