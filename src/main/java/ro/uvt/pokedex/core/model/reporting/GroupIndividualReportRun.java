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
@Document(collection = "groupIndividualReportRuns")
@CompoundIndex(name = "idx_group_report_created", def = "{'groupId': 1, 'reportDefinitionId': 1, 'createdAt': -1}")
public class GroupIndividualReportRun {

    @Id
    private String id;

    private String groupId;
    private String reportDefinitionId;

    /**
     * Persisted as a list of {userId, criterionScores} rather than a map keyed by user identifier.
     * Avoids the previous {@code mongoSafeReportKey} hack: dotted emails were illegal as Mongo
     * document keys and forced a fullwidth-dot substitution that leaked into every read site.
     */
    private List<ResearcherScore> researcherScores = new ArrayList<>();

    private Map<Integer, Map<String, Double>> criteriaThresholds = new HashMap<>();

    private Instant createdAt;
    private Status status;
    private List<String> buildErrors = new ArrayList<>();

    public enum Status {
        READY,
        PARTIAL,
        FAILED
    }

    @Data
    public static class ResearcherScore {
        private String userId;
        private Map<Integer, Double> criterionScores = new HashMap<>();

        public ResearcherScore() {}

        public ResearcherScore(String userId, Map<Integer, Double> criterionScores) {
            this.userId = userId;
            this.criterionScores = criterionScores;
        }
    }
}
