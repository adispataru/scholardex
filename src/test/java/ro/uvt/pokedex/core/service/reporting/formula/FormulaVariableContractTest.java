package ro.uvt.pokedex.core.service.reporting.formula;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H52 slice 12 — pins the per-kind variable contract enforced at indicator save.
 */
class FormulaVariableContractTest {

    private static Indicator pub(ScoringStrategy strategy, String formula) {
        Indicator ind = new Indicator();
        ind.setKind(new IndicatorKind.Publications(AuthorRole.ALL, strategy));
        ind.setFormula(formula);
        return ind;
    }

    private static Indicator citations(ScoringStrategy strategy, String formula) {
        Indicator ind = new Indicator();
        ind.setKind(new IndicatorKind.Citations(
                ro.uvt.pokedex.core.model.reporting.scoring.SelfCitationPolicy.NONE, strategy));
        ind.setFormula(formula);
        return ind;
    }

    // ---------- Accepts every production publication/citation formula ----------

    @Test
    void acceptsProductionPublicationAndCitationFormulas() {
        String[][] ok = {
                {"CS", "S/max(N-2, 1)"},
                {"CS", "(S >= 4) ? (S/max(N-2, 1)) : 0"},
                {"RIS", "S > 0.5 ? S/N : 0"},
                {"IMPACT_FACTOR", "S > 1 ? (3 + 3 * S) : (3 + S)"},
                {"IMPACT_FACTOR", "S > 1 ? (3 + (3 * S)/N) : (3 + S)/N"},
                {"IMPACT_FACTOR", "S > 0 ? 0.5 : 0.1"},
                {"AIS", "Q == 'Q1' ? 1 : Q == 'Q2' ? 0.75 : Q == 'Q3' ? 0.5 : Q == 'Q4' ? 0.25 : 0"},
                {"AIS", "Q == 'Q1' ? 1 : 0"},
                {"CS_JOURNAL", "Q.contains(\"Q\") ? 1 : 0"},
                {"CS_JOURNAL", "Q == \"Q1\" || Q == \"Q2\" ? 1 : 0"},
                {"CS_CONFERENCE", "S > 1 ? S/max(N-2, 1) : 0"},
                {"GENERIC_COUNT", "S"},
                {"CNCSIS", "S"},
                {"AIS", "S/Nef"},  // H65: physics I = ΣAIS/Nef — Nef is bound on every publication/citation score.
                // PD 2026 eligibility formulas: docType splits by WoS document type, category gates the
                // CORE A/A* conference equivalence. Both bound on every publication/citation score.
                {"PD_WOS", "(docType == \"ar\" || docType == \"re\") ? 1 : 0"},
                {"PD_WOS", "(docType == \"ar\" || docType == \"re\" || docType == \"cp\") && (Q == \"Q1\" || Q == \"Q2\") ? 1 : 0"},
                {"CS_CONFERENCE", "(category == \"A*\" || category == \"A\") ? 1 : 0"},
        };
        for (String[] row : ok) {
            Indicator ind = pub(ScoringStrategy.valueOf(row[0]), row[1]);
            assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(ind),
                    "should accept " + row[0] + " : " + row[1]);
        }
    }

    @Test
    void acceptsEconomicsFormulaUsingMultiplier() {
        // FEEA_P: M is bound only for ECONOMICS_JOURNAL_AIS.
        Indicator ind = pub(ScoringStrategy.ECONOMICS_JOURNAL_AIS, "M * (1 - (N-1) * 0.1) * S");
        assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(ind));
    }

    @Test
    void acceptsLocalAssignmentVariables() {
        // X is a local (LHS of =), S/N are contract vars.
        Indicator ind = pub(ScoringStrategy.CS, "X = S > 1 ? 2 : 1; X * N");
        assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(ind));
    }

    // ---------- Rejects undeclared variables ----------

    @Test
    void rejectsTypoVariable() {
        // "SS" is a typo for "S" — not in the publication contract.
        Indicator ind = pub(ScoringStrategy.CS, "SS / N");
        FormulaVariableException ex = assertThrows(FormulaVariableException.class,
                () -> FormulaVariableContract.assertVariablesDeclared(ind));
        assertTrue(ex.getMessage().contains("SS"), ex.getMessage());
        assertTrue(ex.getMessage().contains("undeclared"), ex.getMessage());
    }

    @Test
    void rejectsMultiplierOnNonEconomicsKind() {
        // M is only bound for economics. A CS publication referencing M is a bug.
        Indicator ind = pub(ScoringStrategy.CS, "M * S");
        assertThrows(FormulaVariableException.class,
                () -> FormulaVariableContract.assertVariablesDeclared(ind));
    }

    @Test
    void rejectsActivityFieldNameOnPublicationKind() {
        // 'Buget' is an activity field — meaningless on a publication indicator.
        Indicator ind = pub(ScoringStrategy.CS, "Buget > 50000 ? 1 : 0");
        assertThrows(FormulaVariableException.class,
                () -> FormulaVariableContract.assertVariablesDeclared(ind));
    }

    // ---------- Activity-shaped kinds are skipped (dynamic field surface) ----------

    @Test
    void skipsActivityShapedIndicators() {
        // Activity formulas reference user-defined field names; the contract can't
        // enumerate them at save time, so enforcement is skipped (no throw).
        Indicator forum = new Indicator();
        forum.setKind(new IndicatorKind.Activity(
                ro.uvt.pokedex.core.model.reporting.scoring.ActivityType.FORUM,
                ScoringStrategy.CS_CONFERENCE));
        forum.setFormula("S/max(N_editori-2, 1)"); // N_editori is an activity field
        assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(forum));

        Indicator generic = new Indicator();
        generic.setKind(new IndicatorKind.GenericActivity());
        generic.setFormula("X = Nivel == 'International' ? 4 : 1; X * N_ani");
        assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(generic));
    }

    // ---------- Defensive: no-ops ----------

    @Test
    void noOpOnBlankFormulaOrUnresolvedKind() {
        Indicator blank = pub(ScoringStrategy.CS, "");
        assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(blank));

        Indicator noKind = new Indicator();
        noKind.setFormula("S + N");
        assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(noKind));

        assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(null));
    }

    @Test
    void mathIsAlwaysAllowed() {
        // Math is injected by the evaluator for max/min — always in the contract.
        Indicator ind = citations(ScoringStrategy.CS, "Math.max(S, N)");
        assertDoesNotThrow(() -> FormulaVariableContract.assertVariablesDeclared(ind));
    }
}
