package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scoring service that evaluates Computer Science journals based on WoS quartiles.
 */
@Service
public class EconomicsJournalScoringService extends AbstractWoSForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(EconomicsJournalScoringService.class);
    private static final List<String> CORE_ECONOMICS = List.of(
                "ECONOMICS",
                "BUSINESS, FINANCE",
                "BUSINESS",
                "MANAGEMENT"
            );

            private static final List<String> INFOECONOMICS = List.of(
                "COMPUTER SCIENCE, ARTIFICIAL INTELLIGENCE",
                "COMPUTER SCIENCE, INFORMATION SYSTEMS",
                "COMPUTER SCIENCE, SOFTWARE ENGINEERING",
                "COMPUTER SCIENCE, THEORY & METHODS",
                "COMPUTER SCIENCE, CYBERNETICS",
                "COMPUTER SCIENCE, INTERDISCIPLINARY APPLICATIONS",
                "COMPUTER SCIENCE, HARDWARE & ARCHITECTURE",
                "OPERATIONS RESEARCH & MANAGEMENT SCIENCE",
                "STATISTICS & PROBABILITY",
                "CYBERNETICS"
            );
    private static final List<String> othersIndices = List.of("SSCI", "SCIE");
    private static final int CORE_ECONOMICS_MULTIPLIER = 10;
    private static final int INFOECONOMICS_MULTIPLIER = 8;
    private static final int OTHER_ECONOMICS_MULTIPLIER = 6;

    @Autowired
    public EconomicsJournalScoringService(ReportingLookupPort lookupPort) {
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
                    this::computeEconomicsScore,
                    this::compareScoresByPointsAndMultiplier
            );
        }

        return createScore(scoreResult);
    }

    private Boolean compareScoresByPointsAndMultiplier(Score score, ScoreResult scoreResult) {
        if(Math.abs(score.getScore() - scoreResult.bestPoints.get()) < 0.00000001) {
            if(score.getExtra().containsKey("M") && scoreResult.extra.get("M") != null) {
                return (int) score.getExtra().get("M") > (int) scoreResult.extra.get("M");
            } else {
                return true;
            }
        } else {
            return score.getScore() > scoreResult.bestPoints.get();
        }
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
                this::computeEconomicsScore,
                this::compareScoresByPointsAndMultiplier
        );

        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  CS-specific scoring logic                                        */
    /* ------------------------------------------------------------------ */

    private Optional<Score> computeEconomicsScore(WoSRanking ranking, int year, String category, WoSRanking.Rank rank) {

        Score returnScore = new Score();
        AtomicInteger multiplier = new AtomicInteger(0);
        if( ranking.getScore() == null || ranking.getScore().getAis() == null || ranking.getScore().getAis().get(year) == null) {
            return Optional.empty();
        }
        returnScore.setScore(ranking.getScore().getAis().get(year));

        String cat = ScoringCategorySupport.extractCategoryName(category);
        String index = ScoringCategorySupport.extractCategoryIndex(category);
        if(CORE_ECONOMICS.contains(cat)) {
            multiplier.getAndSet(CORE_ECONOMICS_MULTIPLIER);
            returnScore.setQuarter(rank.getQAis().get(year).toString());
        } else if (INFOECONOMICS.contains(cat) && multiplier.get() < INFOECONOMICS_MULTIPLIER) {
            multiplier.getAndSet(INFOECONOMICS_MULTIPLIER);
            returnScore.setQuarter(rank.getQAis().get(year).toString());
        } else if (othersIndices.contains(index) && multiplier.get() < OTHER_ECONOMICS_MULTIPLIER) {
            multiplier.getAndSet(OTHER_ECONOMICS_MULTIPLIER);
            returnScore.setQuarter(rank.getQAis().get(year).toString());
        }

        returnScore.getExtra().put("M", multiplier.get());

        return Optional.of(returnScore);
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
