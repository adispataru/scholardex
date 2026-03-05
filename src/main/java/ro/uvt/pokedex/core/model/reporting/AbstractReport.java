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
    }

    @Data
    public static class Threshold {
        private Position position;
        private Double value;
    }

}
