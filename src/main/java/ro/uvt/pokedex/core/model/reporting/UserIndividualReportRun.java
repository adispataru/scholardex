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
    /**
     * H60: the evaluation anchor that relative year specs ({@code PreviousNYears}, {@code LatestNRankings}) resolve
     * against — captured when the run is generated so replay/export is deterministic across later ranking imports.
     * Defaults to the run's creation year; may later be a configurable evaluation year. Null on pre-H60 runs.
     */
    private Integer referenceYear;
    private Status status;
    private List<String> buildErrors = new ArrayList<>();

    /**
     * Email of the principal that triggered this run. Equals {@code userEmail} for a researcher's
     * own refresh; differs when an admin or supervisor refreshed on the researcher's behalf
     * (H59 delegated refresh). Null on historical runs created before provenance was tracked.
     */
    private String triggeredByEmail;

    /**
     * H77: true when this run was produced by an admin provisional scoring pass — scored from a DECLARED authorship
     * identity (canonical authors resolved from the org roster / declared source ids) rather than the researcher's
     * own confirmed publication decisions. Read-only; never mistaken for a validated run. Defaults to false on the
     * normal (CONFIRMED) self-scoring path and on all pre-H77 runs.
     */
    private boolean provisional;

    public enum Status {
        READY,
        PARTIAL,
        FAILED
    }
}
