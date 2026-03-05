package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Document(collection = "userIndividualReportRuns")
@CompoundIndex(name = "idx_user_report_created", def = "{'userEmail': 1, 'reportDefinitionId': 1, 'createdAt': -1}")
public class UserIndividualReportRun {

    @Id
    private String id;

    private String userEmail;
    private String researcherId;
    private String reportDefinitionId;

    private List<String> indicatorResultIds = new ArrayList<>();
    private Map<String, Double> indicatorScoresByIndicatorId = new HashMap<>();
    private Map<Integer, Double> criteriaScores = new HashMap<>();

    private Instant createdAt;
    private Status status;
    private List<String> buildErrors = new ArrayList<>();

    public enum Status {
        READY,
        PARTIAL,
        FAILED
    }
}
