package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;

import java.util.*;

@Data
public abstract class AbstractReport {
    @Id
    private String id;
    private String title;
    private String description;
    @DBRef
    private List<Indicator> indicators = new ArrayList<>();
    private List<Criterion> criteria;

    @Data
    public static class Criterion {
        private String name;
        private List<Integer> indicatorIndices = new ArrayList<>();
        private List<Threshold> thresholds = new ArrayList<>();
        /** When true this criterion's score is included in the report-level total. */
        private boolean contributesToTotal = false;
        /**
         * H65: optional per-indicator coefficients (indicatorIndex → weight) for weighted-sum criteria such as the
         * physics composite {@code T = A + P/2 + I/2 + C/20 + h/5}. Null or a missing entry means weight 1.0, so plain
         * sum-of-indicators criteria (every existing report) are unaffected.
         */
        private java.util.Map<Integer, Double> weights;
        /**
         * H68 slice 2: optional criterion-level cap (plafon). When set, the aggregated criterion score is clamped to
         * this maximum (e.g. "maximum 50 puncte for this criterion"). Null = no cap. Applied after the weighted sum,
         * so it composes with {@link #weights}. Per-indicator caps are separate ({@code Indicator.maxPoints}).
         */
        private Double maxTotal;
    }

    @Data
    public static class Threshold {
        private Position position;
        private Double value;
    }

}
