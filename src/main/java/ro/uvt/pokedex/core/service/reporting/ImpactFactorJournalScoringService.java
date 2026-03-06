package ro.uvt.pokedex.core.service.reporting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scoring service that evaluates journals using the Impact Factor metric.
 * The implementation follows the pattern used in {@link AISJournalScoringService}.
 */
@Service
public class ImpactFactorJournalScoringService extends AbstractWoSForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ImpactFactorJournalScoringService.class);
    private static final int LAST_YEAR = 2023;
    private final Counter requestsCounter;
    private final Counter successCounter;
    private final Counter missingCounter;

    @Autowired
    public ImpactFactorJournalScoringService(ReportingLookupPort lookupPort, MeterRegistry meterRegistry) {
        super(lookupPort);
        this.requestsCounter = meterRegistry.counter("pokedex.reporting.if.requests");
        this.successCounter = meterRegistry.counter("pokedex.reporting.if.success");
        this.missingCounter = meterRegistry.counter("pokedex.reporting.if.missing");
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(Publication publication, Indicator indicator) {
        requestsCounter.increment();
        Domain domain = indicator.getDomain();
        Forum forum = lookupPort.getForum(publication.getForum());

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);
        if(allowedYears.size() == 1 & allowedYears.getFirst() > LAST_YEAR){
            allowedYears.set(0, LAST_YEAR);
        }

        if (isArticleOrReview(publication)) {
            computeScores(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    // Impact Factor specific extractor
                    (ranking, year, category, rank) -> {
                        if( ranking.getScore() == null || ranking.getScore().getIF() == null || !ranking.getScore().getIF().containsKey(year)) {
                            return Optional.empty();
                        }
                        Score score = new Score();
                        score.setScore(ranking.getScore().getIF().get(year));
                        score.setQuarter(rank.getQIF().get(year).toString());
                        return Optional.of(score);
                    },
                    this::compareScoresByPoints
            );
        }
        return finalizeWithTelemetry(
                createScore(scoreResult),
                "publication",
                publication == null ? null : publication.getId(),
                forum == null ? null : forum.getPublicationName()
        );
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        requestsCounter.increment();
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
                // Impact Factor specific extractor
                (ranking, year, category ,rank) -> {
                    if( ranking.getScore() == null || ranking.getScore().getIF() == null || !ranking.getScore().getIF().containsKey(year)) {
                        return Optional.empty();
                    }
                    Score score = new Score();
                    score.setScore(ranking.getScore().getIF().get(year));
                    score.setQuarter(rank.getQIF().get(year).toString());
                    return Optional.of(score);
                },
                this::compareScoresByPoints
        );
        return finalizeWithTelemetry(
                createScore(scoreResult),
                "activity",
                activity == null ? null : activity.getId(),
                forum == null ? null : forum.getPublicationName()
        );
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public String getDescription() {
        return "Returns the impact factor\n";
    }

    private Score finalizeWithTelemetry(Score score, String context, String sourceId, String forumName) {
        if (score.getYear() > 0) {
            successCounter.increment();
            logger.info("IMPACT_FACTOR scoring resolved: strategy=IMPACT_FACTOR context={} sourceId={} year={} score={} quarter={} forum={}",
                    context, sourceId, score.getYear(), score.getScore(), score.getQuarter(), forumName);
        } else {
            missingCounter.increment();
            logger.info("IMPACT_FACTOR scoring missing: strategy=IMPACT_FACTOR context={} sourceId={} reason=if_missing_or_not_eligible forum={}",
                    context, sourceId, forumName);
        }
        return score;
    }
}
