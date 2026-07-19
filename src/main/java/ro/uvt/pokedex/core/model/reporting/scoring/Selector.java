package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * Typed replacement for the pre-v1 {@code Indicator.selector} field. Two production shapes:
 * <ul>
 *   <li>{@code null} or {@code ALL} → {@link All} (40 / 42)</li>
 *   <li>{@code TOP_10} → {@link TopN}(10) (2 / 42 — both FEEA indicators)</li>
 * </ul>
 *
 * The {@code n} parameter on {@link TopN} is parameterised in v1 so the future "TOP_5" or
 * "TOP_20" methodology change is a data update, not a code change.
 */
public sealed interface Selector permits Selector.All, Selector.TopN, Selector.DistinctForums, Selector.PerForumCap {

    record All() implements Selector {}

    /** Keep only the top {@code n} items by author score. */
    record TopN(int n) implements Selector {
        public TopN {
            if (n < 1) throw new IllegalArgumentException("TopN.n must be >= 1; got " + n);
        }
    }

    /**
     * FSP I9/I10 ("se pot puncta cumulat cel mult două contribuţii/ediţie conferinţă"): keep at most
     * {@code n} positively-scored items <em>per forum</em> (each conference edition = its proceedings
     * forum), taking the highest-scoring ones; the total sums only the kept items. Unlike {@link TopN}
     * (a single global cap) this caps within each {@code forumId} group.
     */
    record PerForumCap(int n) implements Selector {
        public PerForumCap {
            if (n < 1) throw new IllegalArgumentException("PerForumCap.n must be >= 1; got " + n);
        }
    }

    /**
     * PD 2026 (mentor Q2-diversity rule): every positively-scored item stays in the map, but the
     * indicator TOTAL is the number of <em>distinct forums</em> among them rather than the sum —
     * "minimum 2 dintre aceste articole trebuie să fie din reviste diferite" counts venues, not works.
     */
    record DistinctForums() implements Selector {}

    /**
     * H52 slice 11d.5: name-based constructor matching the legacy enum names.
     * {@code null} / blank → {@link All}; {@code "TOP_10"} → {@link TopN}(10);
     * {@code "ALL"} → {@link All}; {@code "DISTINCT_FORUMS"} → {@link DistinctForums}.
     * Throws on anything else.
     */
    static Selector of(String legacyName) {
        if (legacyName == null || legacyName.isBlank()) return new All();
        return switch (legacyName) {
            case "ALL"             -> new All();
            case "TOP_10"          -> new TopN(10);
            case "DISTINCT_FORUMS" -> new DistinctForums();
            case "PER_FORUM_CAP_2" -> new PerForumCap(2);
            default -> throw new IllegalArgumentException("Unknown selector name: " + legacyName);
        };
    }

    /**
     * Returns the legacy enum-style name for this selector. {@code null} for
     * {@link All} matches the legacy persisted shape (40 / 42 indicators had
     * no selector field). Throws for {@code TopN(n)} with {@code n != 10}
     * until the methodology change ships.
     */
    default String legacyName() {
        return switch (this) {
            case All a -> null;
            case TopN top -> {
                if (top.n() == 10) yield "TOP_10";
                throw new IllegalStateException("TopN.n=" + top.n() + " has no legacy representation");
            }
            case DistinctForums df -> "DISTINCT_FORUMS";
            case PerForumCap cap -> {
                if (cap.n() == 2) yield "PER_FORUM_CAP_2";
                throw new IllegalStateException("PerForumCap.n=" + cap.n() + " has no legacy representation");
            }
        };
    }
}
