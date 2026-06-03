package ro.uvt.pokedex.core.service.reporting;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class Score {
    private double score;
    private int year;
    private String coreRankingEquivalent;
    private String quarter;
    private String scoringSource;
    private Map<String, Object> scoringInfo = new HashMap<>();
    private double authorScore;

    /**
     * H52 slice 9: typed multiplier slot. Only producer is
     * {@code EconomicsJournalScoringService} (writes the discipline-aware Economics
     * multiplier — 1, 2, or 3 depending on category). Consumers
     * ({@code ScientificProductionService}, {@code ActivityReportingService}) bind
     * it into the {@code FormulaContext} variable bag as {@code "M"}.
     *
     * <p>Slice 11c retired the {@code Score.extra["M"]} fallback path; the typed
     * slot is the only place the multiplier lives now. Pre-slice-9 cached blobs
     * lose this value on deserialization, but the cached {@code rawGraph} map
     * still carries the original number for the view layer to display.</p>
     */
    private Integer multiplier;
}
