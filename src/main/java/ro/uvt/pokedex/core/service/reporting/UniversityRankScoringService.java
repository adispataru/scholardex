package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.service.model.UniversityRankingLookupService;

import java.util.List;
import java.util.Optional;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;

/**
 * Scoring service that evaluates journals using the Impact Factor metric.
 * The implementation follows the pattern used in {@link AISJournalScoringService}.
 */
@Service
public class UniversityRankScoringService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(UniversityRankScoringService.class);
    private final UniversityRankingLookupService rankingLookupService;

    public UniversityRankScoringService(ReportingLookupPort lookupPort,
                                        UniversityRankingLookupService rankingLookupService) {
        super(lookupPort);
        this.rankingLookupService = rankingLookupService;
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        ScoreResult scoreResult = initializeScoreResult();
        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        ScoreResult scoreResult = initializeScoreResult();
        if (!activity.getReferenceFields().containsKey(Activity.ReferenceField.UNIVERSITY_NAME)) {
            return createScore(scoreResult);
        }
        String name = activity.getReferenceFields().get(Activity.ReferenceField.UNIVERSITY_NAME);
        List<Integer> allowedYears =
                indicator.getEffectiveScoreYearRange().allowedYears(activity.getYear());

        // H83 S3 — best-of across URAP/ARWU/QS per the OM footnote ("cele mai bune poziții conform
        // clasamentelor"): each allowed year resolves to the minimum rank any source gives (per-source
        // closest data year), and the winning source's provenance is kept for the drilldown.
        UniversityRankingLookupService.BestRank winner = null;
        int winnerYear = 0;
        for (int year : allowedYears) {
            Optional<UniversityRankingLookupService.BestRank> best = rankingLookupService.bestRank(name, year);
            if (best.isPresent() && (winner == null || best.get().rank() < winner.rank())) {
                winner = best.get();
                winnerYear = year;
            }
        }
        if (winner == null) {
            logger.warn("No university ranking (URAP/ARWU/QS) found for: {}", name);
            return createScore(scoreResult);
        }
        scoreResult.bestPoints.set((double) winner.rank());
        scoreResult.bestYear.set(winnerYear);
        scoreResult.bestCategory.set(rankToCategory(winner.rank()));
        scoreResult.scoringSource.set(winner.source());
        scoreResult.scoringInfo.put("matchSource", winner.source());
        scoreResult.scoringInfo.put("universityName", name);
        scoreResult.scoringInfo.put("resolvedDataYear", winner.dataYear());
        scoreResult.scoringInfo.put("resolvedRank", winner.rank());
        scoreResult.scoringInfo.put("rankBand", winner.rankBand());
        return createScore(scoreResult);
    }

    /**
     * Maps a URAP rank value (lower = better) to a Core ranking equivalent so the report exporter's
     * H column gets a meaningful letter:
     * <ul>
     *     <li>rank &le; 20 → A* (top 20)</li>
     *     <li>rank &le; 100 → A (top 100)</li>
     *     <li>rank &le; 200 → B (top 200)</li>
     *     <li>rank &le; 500 → C (top 500)</li>
     *     <li>rank &gt; 500 → D</li>
     * </ul>
     * A 0 rank means "no URAP entry for any allowed year" — return {@code NON_RANK}.
     */
    private CoreConferenceRanking.Rank rankToCategory(double rank) {
        if (rank <= 0) return CoreConferenceRanking.Rank.NON_RANK;
        if (rank <= 20) return CoreConferenceRanking.Rank.A_STAR;
        if (rank <= 100) return CoreConferenceRanking.Rank.A;
        if (rank <= 200) return CoreConferenceRanking.Rank.B;
        if (rank <= 500) return CoreConferenceRanking.Rank.C;
        return CoreConferenceRanking.Rank.D;
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.UNI_RANKING;
    }

    @Override
    public String getDescription() {
        return "Best-of URAP/ARWU/QS university rank score (lower rank value is better).\n";
    }
}
