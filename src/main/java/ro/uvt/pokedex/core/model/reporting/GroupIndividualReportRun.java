package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Document(collection = "groupIndividualReportRuns")
@CompoundIndex(name = "idx_group_report_created", def = "{'groupId': 1, 'reportDefinitionId': 1, 'createdAt': -1}")
public class GroupIndividualReportRun {

    @Id
    private String id;

    private String groupId;
    private String reportDefinitionId;

    private Map<String, Map<Integer, Double>> researcherScores = new HashMap<>();
    private Map<Integer, Map<String, Double>> criteriaThresholds = new HashMap<>();

    private Instant createdAt;
    private Status status;
    private java.util.List<String> buildErrors = new java.util.ArrayList<>();

    public enum Status {
        READY,
        PARTIAL,
        FAILED
    }
}
