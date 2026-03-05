package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Document(collection = "userIndicatorResults")
@CompoundIndex(name = "uniq_user_indicator_mode", def = "{'userEmail': 1, 'indicatorId': 1, 'mode': 1}", unique = true)
public class UserIndicatorResult {

    @Id
    private String id;

    private String userEmail;
    private String researcherId;
    private String indicatorId;

    private Mode mode;
    private SourceType sourceType;
    private String sourceReportId;

    private String fingerprint;
    private String viewName;
    private String rawGraph;

    private Double totalScore;
    private Integer totalCount;
    private List<String> quarterLabels;
    private List<Integer> quarterValues;

    private Instant createdAt;
    private Instant updatedAt;
    private int refreshVersion;

    public enum Mode {
        LATEST,
        SNAPSHOT
    }

    public enum SourceType {
        APPLY_PAGE,
        REPORT_RUN
    }
}
