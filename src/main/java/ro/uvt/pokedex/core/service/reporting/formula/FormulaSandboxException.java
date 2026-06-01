package ro.uvt.pokedex.core.service.reporting.formula;

/**
 * H52 slice 7: thrown by {@link FormulaSandbox#assertSafe} when a formula references
 * a denylisted class or reflective entry point. Subclass of
 * {@link IllegalArgumentException} so the existing controller-layer 400/redirect flow
 * surfaces it without bespoke handling, and so MVEL test scaffolding that catches
 * runtime exceptions still works.
 */
public class FormulaSandboxException extends IllegalArgumentException {
    public FormulaSandboxException(String message) {
        super(message);
    }
}
