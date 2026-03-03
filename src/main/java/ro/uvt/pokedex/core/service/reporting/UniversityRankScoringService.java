package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.CacheService;
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

    public UniversityRankScoringService(CacheService cacheService, URAPUniversityRankingService urapRankingService) {
        super(cacheService);
        this.urapRankingService = urapRankingService;
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(Publication publication, Indicator indicator) {
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
        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public String getDescription() {
        return "Returns URAP university rank-based score (lower rank value is better).\n";
    }
}
