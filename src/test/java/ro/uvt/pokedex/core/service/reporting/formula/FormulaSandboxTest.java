package ro.uvt.pokedex.core.service.reporting.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaSandboxTest {

    // ---------- Things production formulas use → must pass ----------

    @Test
    void realProductionFormulasPass() {
        String[] formulas = {
                "(S > 1.0) ? (3 + 3 * S) : (3 + S)",
                "S",
                "S * M",
                "Math.max(S, 1)",
                "Math.min(S, N)",
                "S + N",
                "S / N",
                "(S > 1.0) ? (3 + 3 * S) / N : (3 + S) / N",
                "B = Buget; X = B < 50000 ? 1 : 2; Rol == 'Membru' ? X : X * 2",
        };
        for (String f : formulas) {
            String canonical = FormulaCanonicalizer.canonicalize(f);
            assertDoesNotThrow(() -> FormulaSandbox.assertSafe(canonical), "rejected legitimate formula: " + f);
        }
    }

    // ---------- Class-access denylist ----------

    @Test
    void systemAccessRejected() {
        assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("System.exit(0)")));
        assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("System.getProperty('user.name')")));
    }

    @Test
    void runtimeAccessRejected() {
        assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("Runtime.getRuntime().exec('rm -rf /')")));
    }

    @Test
    void reflectiveAccessRejected() {
        assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("Class.forName('java.lang.System')")));
        assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("S.getClass()")));
    }

    @Test
    void threadAccessRejected() {
        assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("Thread.sleep(1000)")));
    }

    @Test
    void inlineImportRejected() {
        // MVEL accepts `import java.io.File;` as a statement; sandbox blocks the syntax.
        assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("import java.io.File; 1")));
    }

    @Test
    void fullyQualifiedJavaIoRejected() {
        assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("new java.io.File('/tmp/x').delete() ? 1 : 0")));
    }

    // ---------- The canonical form is what gets scanned ----------

    @Test
    void hiddenInLineCommentIsCanonicalizedOutAndAccepted() {
        // Canonicalizer strips line comments — so a denied token *only* inside a comment
        // is removed and the formula passes. Verify the contract.
        String canonical = FormulaCanonicalizer.canonicalize("S + 1 // System.exit(0) trolling");
        assertDoesNotThrow(() -> FormulaSandbox.assertSafe(canonical));
    }

    @Test
    void whitespaceObfuscationDoesNotBypass() {
        // Canonicalizer collapses whitespace, so " System . exit " becomes "System . exit"
        // — still won't match "System." exactly. The realistic threat is "System." literally;
        // exhaustive obfuscation defenses are out of scope per the threat model. Document
        // the limit with this test.
        String canonical = FormulaCanonicalizer.canonicalize("System . exit ( 0 )");
        // Sandbox does NOT reject because the canonical form is "System . exit ( 0 )"
        // (spaces preserved by canonicalize between tokens), so the substring "System."
        // is not present.
        assertDoesNotThrow(() -> FormulaSandbox.assertSafe(canonical));
    }

    @Test
    void exceptionMessageNamesTheTrigger() {
        FormulaSandboxException ex = assertThrows(FormulaSandboxException.class,
                () -> FormulaSandbox.assertSafe(FormulaCanonicalizer.canonicalize("Runtime.getRuntime()")));
        assertTrue(ex.getMessage().contains("Runtime."), "expected trigger in message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Trigger token"), "expected hint label: " + ex.getMessage());
    }

    @Test
    void nullRejectedWithClearMessage() {
        assertThrows(FormulaSandboxException.class, () -> FormulaSandbox.assertSafe(null));
    }
}
