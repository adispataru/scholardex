package ro.uvt.pokedex.core.model.reporting.scoring;

import ro.uvt.pokedex.core.model.reporting.Indicator;

/**
 * Typed replacement for {@code Indicator.selector}. Two production shapes:
 * <ul>
 *   <li>{@code null} or {@code ALL} → {@link All} (40 / 42)</li>
 *   <li>{@code TOP_10} → {@link TopN}(10) (2 / 42 — both FEEA indicators)</li>
 * </ul>
 *
 * The {@code n} parameter on {@link TopN} is parameterised in v1 so the future "TOP_5" or
 * "TOP_20" methodology change is a data update, not a code change.
 */
public sealed interface Selector permits Selector.All, Selector.TopN {

    record All() implements Selector {}

    /** Keep only the top {@code n} items by author score. */
    record TopN(int n) implements Selector {
        public TopN {
            if (n < 1) throw new IllegalArgumentException("TopN.n must be >= 1; got " + n);
        }
    }

    /**
     * Parse the legacy nested {@code Indicator.Selector} enum.
     * Null is the dominant production value and maps to {@link All}.
     */
    static Selector fromLegacy(Indicator.Selector legacy) {
        if (legacy == null) return new All();
        return switch (legacy) {
            case ALL    -> new All();
            case TOP_10 -> new TopN(10);
        };
    }

    /**
     * Reverse of {@link #fromLegacy}. Returns null for {@link All} so the persisted shape
     * matches the legacy convention; returns {@link Indicator.Selector#TOP_10} for
     * {@link TopN}(10). Throws for unsupported TopN values until they become real.
     */
    default Indicator.Selector toLegacy() {
        return switch (this) {
            case All a -> null;
            case TopN top -> {
                if (top.n() == 10) yield Indicator.Selector.TOP_10;
                throw new IllegalStateException("TopN.n=" + top.n() + " has no legacy representation");
            }
        };
    }
}
