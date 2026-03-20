package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Optional;

/**
 * Scoring service that evaluates Computer Science journals based on WoS quartiles.
 */
@Service
public class ComputerScienceJournalScoringService extends AbstractWoSForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ComputerScienceJournalScoringService.class);

    @Autowired
    public ComputerScienceJournalScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(Publication publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        Forum forum = lookupPort.getForum(publication.getForum());

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);

        if (isArticleOrReview(publication)) {
            computeScores(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    this::computeCSScore,
                    this::compareScoresByPoints
            );
            // Special case for SCOPUS-only journals
            if (scoreResult.bestPoints.get() == 0 &&
                    forum != null &&
                    "Journal".equals(forum.getAggregationType()) &&
                    publication.getEid() != null) {
                scoreResult.bestPoints.set(2.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
                scoreResult.bestYear.set(LAST_YEAR);
            }
        }

        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        Domain domain = indicator.getDomain();
        Forum forum = getForumFromActivity(activity);

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = 
                Indicator.parseYearRange(indicator.getScoreYearRange(), activity.getYear());

        computeScores(
                domain,
                forum,
                allowedYears,
                scoreResult,
                this::computeCSScore,
                this::compareScoresByPoints
        );

        // Special case for SCOPUS-only journals
        if (scoreResult.bestPoints.get() == 0 && 
            forum != null && 
            "Journal".equals(forum.getAggregationType())) {
            scoreResult.bestPoints.set(2.0);
            scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
            scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
            scoreResult.bestYear.set(LAST_YEAR);
        }

        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  CS-specific scoring logic                                        */
    /* ------------------------------------------------------------------ */

    private Optional<Score> computeCSScore(WoSRanking ranking, int year, String category, WoSRanking.Rank rank) {
        WoSRanking.Quarter quarter = rank.getQAis().get(year);
        if (quarter == null) {
            return Optional.empty();
        }

        Score score = new Score();

        int top = lookupPort.getTopRankings(category, year);
        int numTop = (int) (0.2 * top);
        int rankPosition = rank.getRankAis().getOrDefault(year, Integer.MAX_VALUE);

        double points;
        switch (quarter) {
            case Q1 -> points = (rankPosition < numTop) ? 12.0 : 8.0;
            case Q2 -> points = (rankPosition < numTop) ? 8.0 : 4.0;
            case Q3 -> points = (rankPosition < numTop) ? 4.0 : 2.0;
            case Q4 -> points = 2.0;
            default -> points = 0.0;
        }

        score.setScore(points);
        score.setQuarter(quarter.toString());
        score.setCategory(getCategory(points).toString());
        return Optional.of(score);
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public String getDescription() {
        return """
                Scoring strategy for CNATDCU's Computer Science domain.(Category translation from WoS quarters)
                x = 20% * num(Q1) in the same WoS category
                A* = 12p (first x in Q1)
                A = 8p (rest of Q1 + first x in Q2)
                B = 4p (rest of Q2 + first x in Q3)
                C = 2p (rest of Q3 and Q4)
                C = 2p (non WoS, but indexed by SCOPUS)
                D = 1p (non WoS, non SCOPUS)
                """;
    }
}
