package ro.uvt.pokedex.core.service.reporting.formula;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaContextTest {

    @Test
    void buildsImmutableMap() {
        FormulaContext ctx = FormulaContext.builder()
                .put("S", 3.5)
                .put("N", 2)
                .build();

        Map<String, Object> vars = ctx.variables();
        assertEquals(3.5, vars.get("S"));
        assertEquals(2, vars.get("N"));
        assertThrows(UnsupportedOperationException.class, () -> vars.put("X", 1));
    }

    @Test
    void rejectsNullAndBlankKeys() {
        assertThrows(NullPointerException.class, () -> FormulaContext.builder().put(null, 1.0));
        assertThrows(IllegalArgumentException.class, () -> FormulaContext.builder().put(" ", 1.0));
    }

    @Test
    void reservesMathKey() {
        // The Math binding is exclusively the evaluator's; user code may not bind it.
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> FormulaContext.builder().put("Math", Math.class)
        );
        assertTrue(ex.getMessage().contains("reserved"));
    }

    @Test
    void putAllNullIsNoOp() {
        FormulaContext ctx = FormulaContext.builder().putAll(null).put("S", 1.0).build();
        assertEquals(1.0, ctx.variables().get("S"));
    }

    @Test
    void putAllAppendsStrategySpecificExtras() {
        Map<String, Object> extras = new HashMap<>();
        extras.put("M", 1.5);
        extras.put("Q", "Q1");

        FormulaContext ctx = FormulaContext.builder()
                .put("S", 4.0)
                .putAll(extras)
                .build();

        assertEquals(4.0, ctx.variables().get("S"));
        assertEquals(1.5, ctx.variables().get("M"));
        assertEquals("Q1", ctx.variables().get("Q"));
    }

    @Test
    void mutableCopyIsIndependent() {
        FormulaContext ctx = FormulaContext.builder().put("S", 1.0).build();
        Map<String, Object> copy = ctx.mutableCopy();
        copy.put("X", 99);

        assertEquals(1.0, ctx.variables().get("S"));
        assertNotEquals(99, ctx.variables().get("X"));
    }
}
