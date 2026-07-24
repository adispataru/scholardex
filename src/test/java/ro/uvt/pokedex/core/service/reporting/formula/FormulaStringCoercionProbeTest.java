package ro.uvt.pokedex.core.service.reporting.formula;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Probe: several activity fields consumed arithmetically (N_luni on Visiting Staff, N_ani on
 * Consolidare echipe, N_editori on Editor Proceedings) are declared {@code number: false}, so the
 * binding layer hands MVEL a String. This pins whether MVEL's coercion actually yields the
 * intended number — if it doesn't, those indicators silently score 0.
 */
class FormulaStringCoercionProbeTest {

    private final FormulaEvaluator evaluator = new FormulaEvaluator();

    @Test
    void multiplyByNumericStringBehavesLikeNumber() {
        // Info_D_ix shape: X * N_luni with N_luni bound as "3"
        FormulaContext ctx = FormulaContext.builder()
                .put("S", 15.0)
                .put("N_luni", "3")
                .build();
        OptionalDouble r = evaluator.tryEval(
                "X = S == 0 ? 1 : S < 20 ? 12 : S < 100 ? 8 : S < 200 ? 4 : S < 500 ? 2 : 1;\nX*N_luni", ctx);
        assertTrue(r.isPresent(), "formula with String N_luni failed to evaluate");
        assertEquals(36.0, r.getAsDouble(), 1e-9);
    }

    @Test
    void subtractionOnNumericStringBehavesLikeNumber() {
        // Info_D_ii shape: max(N_editori-2, 1) with N_editori bound as "4"
        FormulaContext ctx = FormulaContext.builder()
                .put("S", 8.0)
                .put("N_editori", "4")
                .put("N_editii", 1)
                .build();
        OptionalDouble r = evaluator.tryEval("(S/max(N_editori-2, 1)) * N_editii", ctx);
        assertTrue(r.isPresent(), "formula with String N_editori failed to evaluate");
        assertEquals(4.0, r.getAsDouble(), 1e-9);
    }
}
