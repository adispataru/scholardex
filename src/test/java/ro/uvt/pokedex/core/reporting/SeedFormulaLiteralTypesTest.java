package ro.uvt.pokedex.core.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaContext;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards every committed indicator formula against one trap of the formula engine: a variable assigned from
 * a conditional takes its type from the LAST literal of that conditional. {@code m = c1 ? 2 : (c2 ? 1.5 : 1)}
 * makes {@code m} an integer, and when the 1.5 branch is taken the evaluation fails with a class cast. The
 * scoring paths turn a failed evaluation into a zero, so nothing is reported: the item just scores 0.
 * {@code m = c1 ? 3 : (c2 ? 1 : 0.5)} happens to work, because its last literal is the decimal. The remedy
 * is to write every branch of such an assignment as a decimal ({@code 2.0 : (… ? 1.5 : 1.0)}).
 */
class SeedFormulaLiteralTypesTest {

    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*[A-Za-z_]\\w*\\s*=(?!=)(.*)$", Pattern.DOTALL);
    private static final Pattern BRANCH_LITERAL = Pattern.compile("[?:]\\s*\\(*\\s*(\\d+(?:\\.\\d+)?)(?![\\w.])");

    /** The literals standing directly in a branch of a conditional ({@code ? 3}, {@code : (0.5}). */
    static List<String> branchLiterals(String expression) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = BRANCH_LITERAL.matcher(expression.replaceAll("'[^']*'", "''"));
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    /** True when an assignment ends its conditional on an integer literal and has a decimal one before it. */
    static boolean assignsADecimalToAnIntegerVariable(String formula) {
        for (String statement : formula.split(";")) {
            Matcher assignment = ASSIGNMENT.matcher(statement);
            if (!assignment.matches() || !assignment.group(1).contains("?")) {
                continue;
            }
            List<String> literals = branchLiterals(assignment.group(1));
            if (literals.isEmpty()) {
                continue;
            }
            boolean endsOnAnInteger = !literals.getLast().contains(".");
            boolean decimalBefore = literals.stream().anyMatch(literal -> literal.contains("."));
            if (endsOnAnInteger && decimalBefore) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theEngineReallyFailsOnTheTrap() {
        FormulaEvaluator evaluator = new FormulaEvaluator();
        FormulaContext middle = FormulaContext.builder().put("T", "B").build();
        assertThrows(RuntimeException.class,
                () -> evaluator.eval("m = T == 'A' ? 2 : (T == 'B' ? 1.5 : 1); 8 * m", middle));
        assertThrows(RuntimeException.class,
                () -> evaluator.eval("m = T == 'B' ? 0.5 : 1; 8 * m", middle));
        assertEquals(12.0, evaluator.eval("m = T == 'A' ? 2.0 : (T == 'B' ? 1.5 : 1.0); 8 * m", middle), 1e-9);
        // The decimal as the last literal, or no assignment at all, is fine.
        assertEquals(4.0, evaluator.eval("m = T == 'A' ? 3 : (T == 'C' ? 1 : 0.5); 8 * m", middle), 1e-9);
        assertEquals(1.5, evaluator.eval("T == 'A' ? 2 : (T == 'B' ? 1.5 : 1)", middle), 1e-9);
    }

    @Test
    void theCheckSeesTheTrapAndNothingElse() {
        assertTrue(assignsADecimalToAnIntegerVariable("m = T == 'A' ? 2 : (T == 'B' ? 1.5 : 1); 8 * m"));
        assertTrue(assignsADecimalToAnIntegerVariable("m = T == 'A' ? 2.0 : (T == 'B' ? 1.5 : 1); 8 * m"));
        assertTrue(assignsADecimalToAnIntegerVariable("m = T == 'B' ? 0.5 : 1; 8 * m"));
        assertTrue(!assignsADecimalToAnIntegerVariable("m = T == 'A' ? 2.0 : (T == 'B' ? 1.5 : 1.0); 8 * m"));
        assertTrue(!assignsADecimalToAnIntegerVariable("m = T == 'A1' ? 3 : (T == 'A2' ? 1 : 0.5); 8 * m"));
        // A decimal in the CONDITION is not a branch; a variable in a branch carries its own type.
        assertTrue(!assignsADecimalToAnIntegerVariable("m = (X != null && X >= 0.1) ? 3 : 1; 4 * m"));
        assertTrue(!assignsADecimalToAnIntegerVariable("n = (N == null || N < 1) ? 1 : N; 2.5 / n"));
        assertTrue(!assignsADecimalToAnIntegerVariable("T == 'm = 1.5' ? 0.5 : 0"));
    }

    @Test
    void noCommittedFormulaAssignsADecimalToAnIntegerVariable() throws Exception {
        List<String> offenders = new ArrayList<>();
        JsonNode indicators = new ObjectMapper().readTree(
                Files.readString(Path.of("seed", "precious-config", "indicators.json")));
        for (JsonNode indicator : indicators) {
            String formula = indicator.path("formula").asText("");
            if (assignsADecimalToAnIntegerVariable(formula)) {
                offenders.add(indicator.get("name").asText() + ": " + formula);
            }
        }
        assertEquals(List.of(), offenders);
    }
}
