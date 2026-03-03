package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Optional;

/**
 * Scoring service that evaluates journals using the RIS metric.
 * The implementation mirrors the AIS‐pattern used in {@link AISJournalScoringService}.
 */
@Service
public class RISJournalScoringService extends AbstractWoSForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(RISJournalScoringService.class);

    @Autowired
    public RISJournalScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(Publication publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        Forum  forum  = lookupPort.getForum(publication.getForum());

        ScoreResult           scoreResult  = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);

        if (isArticleOrReview(publication)) {
            computeScores(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    // RIS specific extractor
                    (ranking, year, category, rank) -> {
                        if( ranking.getScore() == null || ranking.getScore().getRis() == null || ranking.getScore().getRis().get(year) == null) {
                            return Optional.empty();
                        }
                        Score score = new Score();
                        score.setScore(ranking.getScore().getRis().get(year));
                        score.setQuarter(rank.getQAis().get(year).toString());
                        return Optional.of(score);
                    },
                    this::compareScoresByPoints
            );
        }
        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        Domain domain = indicator.getDomain();
        Forum  forum  = getForumFromActivity(activity);

        ScoreResult           scoreResult   = initializeScoreResult();
        List<Integer> allowedYears =
                Indicator.parseYearRange(indicator.getScoreYearRange(), activity.getYear());

        computeScores(
                domain,
                forum,
                allowedYears,
                scoreResult,
                // RIS specific extractor
                (ranking, year, category, rank) -> {
                    if( ranking.getScore() == null || ranking.getScore().getRis() == null) {
                        return Optional.empty();
                    }
                    Score score = new Score();
                    score.setScore(ranking.getScore().getRis().get(year));
                    score.setQuarter(rank.getQAis().get(year).toString());
                    return Optional.of(score);
                },
                this::compareScoresByPoints
        );
        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public String getDescription() {
        return "Returns the RIS score\n";
    }
}