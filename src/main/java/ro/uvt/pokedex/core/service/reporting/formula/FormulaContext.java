package ro.uvt.pokedex.core.service.reporting.formula;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * H52 slice 4: typed, immutable variable bag handed to {@link FormulaEvaluator}.
 *
 * <p>Replaces the ad-hoc {@code Map<String,Object>} the two pre-v1 call sites
 * ({@code ScientificProductionService} and {@code ActivityReportingService}) built
 * inline. Both sites consistently bound {@code S}, {@code N}, {@code Q}, and a flat
 * spread of the strategy-specific {@code Score.extra} map. The builder enforces:</p>
 *
 * <ul>
 *   <li>Keys are non-null, non-blank.</li>
 *   <li>The published map is wrapped {@link Collections#unmodifiableMap unmodifiable};
 *       the evaluator can hand it straight to MVEL without copying.</li>
 *   <li>{@code Math} is reserved — the evaluator injects it when the formula uses
 *       {@code max(...)}/{@code min(...)}, so user code may not bind it.</li>
 * </ul>
 *
 * <p>The contract is intentionally weak about which keys must be present; that's the
 * job of slice 5's variable-allowlist parser. Today: present, typed, immutable.</p>
 */
public final class FormulaContext {

    /** Reserved variable name; evaluator injects {@link Math} when rewriting. */
    public static final String RESERVED_MATH_KEY = "Math";

    private final Map<String, Object> variables;

    private FormulaContext(Map<String, Object> variables) {
        this.variables = Collections.unmodifiableMap(variables);
    }

    /** Live read of the variable map. Unmodifiable — safe to share. */
    public Map<String, Object> variables() {
        return variables;
    }

    /**
     * Returns a mutable copy of the variables map. Used by {@link FormulaEvaluator}
     * when it needs to inject {@code Math} for the {@code max}/{@code min} rewrite
     * without disturbing the shared immutable view.
     */
    Map<String, Object> mutableCopy() {
        return new HashMap<>(variables);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        // Insertion-ordered so debug logs print variables in the order they were bound.
        private final LinkedHashMap<String, Object> vars = new LinkedHashMap<>();

        public Builder put(String key, Object value) {
            Objects.requireNonNull(key, "key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("formula variable key must not be blank");
            }
            if (RESERVED_MATH_KEY.equals(key)) {
                throw new IllegalArgumentException("'" + RESERVED_MATH_KEY
                        + "' is reserved and injected by FormulaEvaluator when needed");
            }
            vars.put(key, value);
            return this;
        }

        /** Convenience for {@code Score.extra} dumps. {@code null} treated as empty. */
        public Builder putAll(Map<String, ?> entries) {
            if (entries == null) return this;
            for (Map.Entry<String, ?> e : entries.entrySet()) {
                put(e.getKey(), e.getValue());
            }
            return this;
        }

        public FormulaContext build() {
            return new FormulaContext(vars);
        }
    }
}
