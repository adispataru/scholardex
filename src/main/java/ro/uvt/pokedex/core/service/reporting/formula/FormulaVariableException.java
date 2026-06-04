package ro.uvt.pokedex.core.service.reporting.formula;

/**
 * H52 slice 12: thrown by {@link FormulaVariableContract#assertVariablesDeclared} when an
 * indicator formula references a variable its {@code IndicatorKind} does not provide.
 *
 * <p>Subclass of {@link IllegalArgumentException} so the controller layer surfaces it as a
 * 400 (same path as {@link FormulaSandboxException}), and MVEL-test scaffolding that catches
 * runtime exceptions still works.</p>
 */
public class FormulaVariableException extends IllegalArgumentException {
    public FormulaVariableException(String message) {
        super(message);
    }
}
