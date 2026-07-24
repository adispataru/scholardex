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
import java.util.Map;
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
                indicator.getEffectiveScoreYearRange().allowedYears(activity.getYear());


        computeScoresWithUniversity(uniRank,
                allowedYears,
                scoreResult,
                (rank, year) -> Optional.of(closestYearRank(rank, year)));
        scoreResult.bestCategory.set(rankToCategory(scoreResult.bestPoints.get()));
        return createScore(scoreResult);
    }

    /**
     * URAP rank for {@code year}, falling back to the closest loaded data year when the activity's
     * year has none — the URAP dataset starts at ~2018 while D_viii/D_ix visits can be far older, and
     * a resolved university's rank is a far better estimate than dropping to the formula's unranked
     * floor. Ties prefer the earlier year (closer to the visit's era). Returns 0 only when the
     * ranking carries no data at all, which the indicator formulas map to the standard's
     * {@code "> 500 → 1"} floor.
     */
    private static double closestYearRank(URAPUniversityRanking ranking, int year) {
        Map<Integer, URAPUniversityRanking.Score> scores = ranking.getScores();
        if (scores == null || scores.isEmpty()) {
            return 0.0;
        }
        URAPUniversityRanking.Score exact = scores.get(year);
        if (exact != null) {
            return exact.getRank();
        }
        Integer closestYear = null;
        for (Integer dataYear : scores.keySet()) {
            if (dataYear == null || scores.get(dataYear) == null) {
                continue;
            }
            if (closestYear == null
                    || Math.abs(dataYear - year) < Math.abs(closestYear - year)
                    || (Math.abs(dataYear - year) == Math.abs(closestYear - year) && dataYear < closestYear)) {
                closestYear = dataYear;
            }
        }
        return closestYear == null ? 0.0 : scores.get(closestYear).getRank();
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
        return "Returns URAP university rank-based score (lower rank value is better).\n";
    }
}
