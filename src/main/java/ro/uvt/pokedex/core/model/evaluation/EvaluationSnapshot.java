package ro.uvt.pokedex.core.model.evaluation;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Document(collection = "evaluationSnapshots")
@CompoundIndex(name = "idx_snapshot_user_report_created", def = "{'userEmail': 1, 'reportId': 1, 'createdAt': -1}")
public class EvaluationSnapshot {

    @Id
    private String id;

    private String userEmail;
    private String researcherId;
    private String reportId;
    private String name;
    private Instant createdAt;

    private Map<String, Double> indicatorScores = new HashMap<>();
    private Map<Integer, Double> criteriaScores = new HashMap<>();
}
