package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.service.model.URAPUniversityRankingService;

import java.util.List;
import java.util.Optional;

/**
 * Scoring service that evaluates journals using the Impact Factor metric.
 * The implementation follows the pattern used in {@link AISJournalScoringService}.
 */
@Service
public class UniversityRankScoringService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(UniversityRankScoringService.class);
    private final URAPUniversityRankingService urapRankingService;

    public UniversityRankScoringService(ReportingLookupPort lookupPort, URAPUniversityRankingService urapRankingService) {
        super(lookupPort);
        this.urapRankingService = urapRankingService;
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
        URAPUniversityRanking uniRank = urapRankingService.getURAPUniversityRankingByName(name);
        if(uniRank == null){
            logger.warn("No URAP ranking found for university: {}", name);
            return createScore(scoreResult);
        }
        List<Integer> allowedYears =
                Indicator.parseYearRange(indicator.getScoreYearRange(), activity.getYear());


        computeScoresWithUniversity(uniRank,
                allowedYears,
                scoreResult,
                // Impact Factor specific extractor
                (rank, year) ->
                        Optional.of(rank.getScores().get(year) != null ? (double)rank.getScores().get(year).getRank() : 0.0));
        scoreResult.bestCategory.set(rankToCategory(scoreResult.bestPoints.get()));
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
    public String getDescription() {
        return "Returns URAP university rank-based score (lower rank value is better).\n";
    }
}
