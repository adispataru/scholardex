package ro.uvt.pokedex.core.service.reporting.formula;

import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * H52 slice 12: the per-kind variable contract. Enforces that an indicator formula only
 * references variables its {@code IndicatorKind} actually binds into the
 * {@link FormulaContext} at scoring time. Catches typos ({@code SS} for {@code S}) and
 * cross-kind mistakes (e.g. an activity field name on a Publications indicator) at
 * <em>save</em> time instead of letting them blow up — or silently mis-score — at eval time.
 *
 * <p>The variable surface, derived from what the two scoring consumers actually put into the
 * {@code FormulaContext}:</p>
 *
 * <table>
 *   <tr><th>Kind</th><th>Variables bound</th></tr>
 *   <tr><td>Publications / Citations (incl. GenericCount, which is PUBLICATIONS-shaped)</td>
 *       <td>{@code S}, {@code N}, {@code Q} (+ {@code M} if economics)</td></tr>
 *   <tr><td>Activity / GenericActivity</td>
 *       <td>{@code S} (+ {@code M} if economics) + the activity's field names</td></tr>
 * </table>
 *
 * <p>{@code Math} is always allowed — {@link FormulaEvaluator} injects it for the
 * {@code max}/{@code min} rewrite. The MVEL literal keywords {@code true}/{@code false}/
 * {@code null}/{@code empty} are allowed too.</p>
 *
 * <p><strong>Scope: publication/citation-shaped kinds only.</strong> Those have a fixed,
 * known variable surface ({@code S}/{@code N}/{@code Q}, {@code +M} for economics) so the
 * contract is unambiguous. Activity-shaped kinds reference dynamic, user-defined activity
 * field names ({@code Buget}, {@code N_editori}, {@code Nivel}, …) whose surface depends on
 * the linked {@link ro.uvt.pokedex.core.model.activities.Activity} — and at admin-form save
 * time the {@code activity} {@code @DBRef} is not resolved with its fields. Enforcing those
 * would risk false-rejecting valid indicators, so activity-shaped kinds are <em>skipped</em>;
 * their formula/field consistency belongs with the activity-definition UI, not formula save.</p>
 *
 * <p><strong>Local assignments.</strong> Even within the enforced kinds, formulas may use the
 * MVEL statement separator with intermediate assignments (e.g. {@code X = S > 1 ? 2 : 1; X * N}).
 * Identifiers appearing as the left side of a bare {@code =} are treated as locally declared
 * and allowed on the right side.</p>
 */
public final class FormulaVariableContract {

    private FormulaVariableContract() {}

    /** Always-available identifiers regardless of kind. */
    private static final Set<String> UNIVERSAL = Set.of(
            "Math",                       // injected by FormulaEvaluator for max/min
            "max", "min",                 // bare function names; FormulaEvaluator rewrites to Math.max/Math.min
            "true", "false", "null", "empty" // MVEL literal keywords
    );

    /**
     * Validates {@code indicator}'s formula against its kind's variable contract. No-op when
     * the formula is blank, the kind is unresolved, or the variable surface can't be
     * enumerated (unresolved activity).
     *
     * @throws FormulaVariableException if the formula references a variable not in the contract
     */
    public static void assertVariablesDeclared(Indicator indicator) {
        if (indicator == null) return;
        String formula = indicator.getFormula();
        if (formula == null || formula.isBlank()) return;

        Optional<Set<String>> allowedOpt = allowedVariables(indicator);
        if (allowedOpt.isEmpty()) return; // can't enumerate — skip rather than false-reject
        Set<String> allowed = allowedOpt.get();

        List<FormulaTokenizer.Token> tokens = FormulaTokenizer.tokenize(formula);

        // Pass 1: collect locally-assigned identifiers (IDENT immediately followed by a bare "=").
        // The tokenizer emits ==/!=/>=/<= as distinct multi-char OP tokens, so "=" here is only
        // ever an assignment.
        Set<String> locals = new HashSet<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            FormulaTokenizer.Token t = tokens.get(i);
            FormulaTokenizer.Token next = tokens.get(i + 1);
            if (t.type() == FormulaTokenizer.Type.IDENT
                    && next.type() == FormulaTokenizer.Type.OP
                    && "=".equals(next.text())) {
                locals.add(rootIdentifier(t.text()));
            }
        }

        // Pass 2: every IDENT root must be allowed, a declared local, or universal.
        for (FormulaTokenizer.Token t : tokens) {
            if (t.type() != FormulaTokenizer.Type.IDENT) continue;
            String root = rootIdentifier(t.text());
            if (allowed.contains(root) || locals.contains(root) || UNIVERSAL.contains(root)) {
                continue;
            }
            throw new FormulaVariableException(
                    "Formula references undeclared variable '" + root + "'. "
                            + "This indicator kind provides only: " + sortedForMessage(allowed)
                            + (locals.isEmpty() ? "" : " (plus locals " + sortedForMessage(locals) + ")")
                            + ". Check for a typo or a variable from a different indicator kind.");
        }
    }

    /**
     * The set of variable names the indicator's kind binds, or empty when enforcement is
     * skipped (unresolved kind, or activity-shaped — see class javadoc).
     */
    public static Optional<Set<String>> allowedVariables(Indicator indicator) {
        IndicatorKind kind = indicator.getEffectiveKind();
        if (kind == null) return Optional.empty();

        // Activity-shaped kinds have a dynamic, activity-defined variable surface that
        // isn't resolvable at save time — skip rather than risk a false rejection.
        if (indicator.isActivityOutput()) return Optional.empty();

        // Publication / citation shaped: fixed surface S, N, Q (+M if economics).
        Set<String> allowed = new HashSet<>();
        allowed.add("S");
        allowed.add("N");
        allowed.add("Q");
        // H65: Nef (effective author count) is bound on every publication/citation score, so physics indicators can use
        // S/Nef. Always allowed — it's a no-op divisor for indicators that don't reference it.
        allowed.add("Nef");
        // H79: feeJournal (boolean) is bound on every publication/citation score — true when the scored forum is a
        // DOAJ gold-OA (APC) journal. The 2026 Informatică indicators gate on it (`!feeJournal ? … : 0`); always allowed.
        allowed.add("feeJournal");
        // H79: topAB (boolean) — category-based eligibility for the 2026 "top A*/A/B" indicators. True when the scored
        // item's forum category is in {A*,A,B} (workshop-authoritative; S>=4 otherwise). Always allowed.
        allowed.add("topAB");
        // PD 2026: docType (string) — the resolved subtype code ("ar"/"re"/"cp"/…), for eligibility formulas that
        // split by WoS document type. category (string) — the scorer's class label ("A*"/"A"/"Q1"/…), for the CORE
        // A/A* equivalence gate. Both bound on every publication/citation score; ignored by existing formulas.
        allowed.add("docType");
        allowed.add("category");
        // S2 position-aware scoring: Poz (string) — the target position under evaluation ("CONF_UNIV"/…),
        // "" on the canonical pass. Publications kinds only: the per-position TOTAL aggregation lives in
        // calculateScientificProductionScoreDetailed's selector pass, which the citations paths do not
        // share — allowing Poz there would evaluate per-position item scores that never reach a total.
        if (kind instanceof IndicatorKind.Publications) {
            allowed.add("Poz");
            // FEAA point 6: author count restricted to Romania-affiliated authors, bound lazily by
            // ScientificProductionService only when the formula references it.
            allowed.add("N_ro");
        }
        if (kind.strategy() == ScoringStrategy.ECONOMICS_JOURNAL_AIS) {
            allowed.add("M");
        }
        return Optional.of(allowed);
    }

    /**
     * The identifier root: the segment before the first {@code .}. The tokenizer emits
     * dot-chains like {@code Math.max} as a single IDENT; the contract checks the root
     * ({@code Math}) since that's the bound variable. Activity field names with no dot
     * are returned unchanged (including ones with spaces, which MVEL can't reference anyway).
     */
    private static String rootIdentifier(String ident) {
        int dot = ident.indexOf('.');
        return dot < 0 ? ident : ident.substring(0, dot);
    }

    private static String sortedForMessage(Set<String> set) {
        return set.stream().sorted().toList().toString();
    }
}
