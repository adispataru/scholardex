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
    private Map<String, String> errors = new HashMap<>();

    /**
     * @deprecated H52 slice 9: open-bag holdover used historically to carry the
     * EconomicsJournal {@code "M"} multiplier and nothing else. The typed field
     * {@link #multiplier} replaces that contract for new writes; this map is kept
     * populated in parallel so historical persisted scores and H50 round-trips
     * still work. Slice 11 (Commit 3) deletes this field.
     */
    @Deprecated
    private Map<String, Object> extra = new HashMap<>();

    /**
     * H52 slice 9: typed multiplier slot. Only producer is
     * {@code EconomicsJournalScoringService} (writes the discipline-aware Economics
     * multiplier — 1, 2, or 3 depending on category). Consumers
     * ({@code ScientificProductionService}, {@code ActivityReportingService}) bind
     * it into the {@code FormulaContext} variable bag as {@code "M"}.
     *
     * <p>Read order in consumers: {@code multiplier} first, fall back to
     * {@code extra.get("M")} for any score loaded from historical Mongo data or
     * an H50 import file written before this slice landed.</p>
     */
    private Integer multiplier;

    private String details;
}
