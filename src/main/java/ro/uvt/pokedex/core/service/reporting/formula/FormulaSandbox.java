package ro.uvt.pokedex.core.service.reporting.formula;

import java.util.List;
import java.util.regex.Pattern;

/**
 * H52 slice 7: a denylist-based sanity check over indicator formulas. Rejects
 * canonical forms that reference dangerous classes or reflective entry points.
 *
 * <p><strong>Threat model.</strong> Indicator formulas are authored by admins who
 * already have full Mongo write access to the {@code indicators} collection. This is
 * not adversarial sandboxing — a malicious admin will always win — but a guardrail
 * against the realistic footguns: pasting a snippet that calls {@code System.exit(0)}
 * during a report run, accidentally invoking {@code Runtime.getRuntime().exec(...)}
 * via copy/paste from a tutorial, or using {@code Class.forName} to reach a class the
 * v1 contract doesn't expose. A determined attacker can defeat this with concatenation
 * or reflection; the real defense is locking down the indicator-edit role.</p>
 *
 * <p><strong>Why a denylist instead of a real MVEL sandbox?</strong> An empty
 * {@link org.mvel2.ParserContext} still resolves {@code java.lang} classes via the
 * system class loader (proven by direct probe — see slice-7 notes). Building a real
 * sandbox would require a custom {@code VariableResolverFactory} that intercepts class
 * lookups, and is multi-day work. A scan over the canonical form covers every formula
 * we have or expect; rejections are deterministic and easy to audit.</p>
 *
 * <p><strong>Two integration points.</strong> Same check fires:</p>
 * <ul>
 *   <li>In {@link FormulaEvaluator} on compile-cache misses, so unsafe expressions
 *       can never execute even if they got into the database through some other path.</li>
 *   <li>In {@link IndicatorFormulaHashStamper}'s {@code onBeforeConvert}, so saves
 *       are rejected loudly with the indicator id in the message.</li>
 * </ul>
 */
public final class FormulaSandbox {

    private FormulaSandbox() {}

    /**
     * Patterns rejected anywhere in the canonical form. The {@code Math.} reference is
     * allowed because {@link FormulaEvaluator} rewrites bare {@code max}/{@code min}
     * to {@code Math.max}/{@code Math.min} and binds {@code Math} as a variable —
     * production formulas use only those two members and nothing else from class-level
     * APIs.
     */
    private static final List<Rule> RULES = List.of(
            new Rule("System.", "java.lang.System access (e.g. System.exit, System.getProperty)"),
            new Rule("Runtime.", "java.lang.Runtime access (e.g. Runtime.getRuntime().exec)"),
            new Rule("Runtime(", "java.lang.Runtime instantiation"),
            new Rule("ProcessBuilder", "java.lang.ProcessBuilder use"),
            new Rule("Process.", "java.lang.Process use"),
            new Rule("Thread.", "java.lang.Thread access"),
            new Rule("Class.forName", "reflective Class.forName lookup"),
            new Rule("ClassLoader", "ClassLoader access"),
            new Rule("forName(", "reflective forName-style lookup"),
            new Rule(".getClass(", "reflective .getClass() chain"),
            new Rule("getDeclaredField", "reflective field access"),
            new Rule("getDeclaredMethod", "reflective method access"),
            new Rule("setAccessible", "reflective setAccessible"),
            new Rule("ScriptEngine", "javax.script ScriptEngine access"),
            new Rule("java.io.", "fully-qualified java.io access"),
            new Rule("java.nio.", "fully-qualified java.nio access"),
            new Rule("java.net.", "fully-qualified java.net access"),
            new Rule("java.lang.reflect", "java.lang.reflect access"),
            new Rule("javax.script", "javax.script access")
    );

    /**
     * MVEL inline-import declarations: {@code import java.io.File;}. Token-based so
     * we don't false-positive on a variable named {@code importance}.
     */
    private static final Pattern IMPORT_DECLARATION =
            Pattern.compile("\\bimport\\s+[a-zA-Z_][\\w.]*\\s*;");

    /**
     * Throws {@link FormulaSandboxException} if {@code canonical} matches any
     * denylist rule. Otherwise returns silently.
     *
     * <p>Pass the canonical form ({@link FormulaCanonicalizer#canonicalize}) rather
     * than the raw formula — the canonicalizer strips line comments where someone
     * might have hidden a denied token, and collapses whitespace so
     * {@code System . exit} matches the same rule as {@code System.exit}.</p>
     */
    public static void assertSafe(String canonical) {
        if (canonical == null) {
            throw new FormulaSandboxException("canonical formula is null");
        }
        for (Rule rule : RULES) {
            if (canonical.contains(rule.token)) {
                throw new FormulaSandboxException("Formula references " + rule.reason
                        + " — disallowed by the H52 sandbox. Trigger token: '" + rule.token + "'");
            }
        }
        if (IMPORT_DECLARATION.matcher(canonical).find()) {
            throw new FormulaSandboxException("Formula uses an inline 'import' statement — "
                    + "imports are disallowed by the H52 sandbox");
        }
    }

    private record Rule(String token, String reason) {}
}
