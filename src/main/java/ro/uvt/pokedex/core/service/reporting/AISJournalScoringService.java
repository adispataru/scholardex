package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Optional;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

@Service
public class AISJournalScoringService extends AbstractWoSForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(AISJournalScoringService.class);

    @Autowired
    public AISJournalScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);

        if (isArticleOrReview(publication)) {
            computeScores(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    (ranking, year, category, rank) ->
                    {
                        if( ranking.getScore() == null || ranking.getScore().getAis() == null || ranking.getScore().getAis().get(year) == null) {
                            return Optional.empty();
                        }
                        Score score = new Score();
                        score.setScore(ranking.getScore().getAis().get(year));
                        WoSRanking.Quarter qAis = rank.getQAis() != null ? rank.getQAis().get(year) : null;
                        score.setQuarter(qAis != null ? qAis.toString() : null);
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
        ScholardexForumView forum = getForumFromActivity(activity);

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears =
                Indicator.parseYearRange(indicator.getScoreYearRange(), activity.getYear());

        computeScores(
                domain,
                forum,
                allowedYears,
                scoreResult,
                (ranking, year, category, rank) -> {
                    if( ranking.getScore() == null || ranking.getScore().getAis() == null || ranking.getScore().getAis().get(year) == null) {
                        return Optional.empty();
                    }
                    Score score = new Score();
                    score.setScore(ranking.getScore().getAis().get(year));
                    WoSRanking.Quarter qAis = rank.getQAis() != null ? rank.getQAis().get(year) : null;
                    score.setQuarter(qAis != null ? qAis.toString() : null);
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
    public ScoringStrategy strategy() {
        return ScoringStrategy.AIS;
    }

    @Override
    public String getDescription() {
        return "Returns the AIS score.\n";
    }
}
