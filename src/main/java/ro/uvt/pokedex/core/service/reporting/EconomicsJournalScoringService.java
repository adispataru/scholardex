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
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;

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
                    this::computeEconomicsScore,
                    this::compareScoresByPointsAndMultiplier
            );
        }

        return createScore(scoreResult);
    }

    private Boolean compareScoresByPointsAndMultiplier(Score score, ScoreResult scoreResult) {
        if(Math.abs(score.getScore() - scoreResult.bestPoints.get()) < 0.00000001) {
            // H52 slice 11: typed-only read on both sides. The intermediate
            // ScoreResult exposes bestMultiplier now; EJSS writes via setMultiplier
            // exclusively (no more extra["M"] dual-write).
            Integer scoreM = readMultiplier(score);
            Integer resultM = scoreResult.bestMultiplier.get();
            if (scoreM != null && resultM != null) {
                return scoreM > resultM;
            }
            return false;
        } else {
            return score.getScore() > scoreResult.bestPoints.get();
        }
    }

    /**
     * H52 slice 11c: typed-only read. The {@code Score.extra} bag is gone; pre-slice-9
     * cached blobs lose their M value here but the cached {@code rawGraph} map still
     * carries the original number for the view layer.
     */
    private static Integer readMultiplier(Score score) {
        return score.getMultiplier();
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
                indicator.getEffectiveScoreYearRange().allowedYears(activity.getYear());

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
        if( ranking.getScore() == null || ranking.getScore().getAis() == null || ranking.getScore().getAis().get(year) == null) {
            return Optional.empty();
        }
        returnScore.setScore(ranking.getScore().getAis().get(year));

        String cat = ScoringCategorySupport.extractCategoryName(category);
        String index = ScoringCategorySupport.extractCategoryIndex(category);
        int multiplier = resolveMultiplier(cat, index);
        WoSRanking.Quarter qAis = rank.getQAis() != null ? rank.getQAis().get(year) : null;
        String quarterStr = qAis != null ? qAis.toString() : null;
        if(multiplier > 0) {
            returnScore.setQuarter(quarterStr);
        }

        // H52 slice 11: typed-only write. Slice 9 had to dual-write extra["M"] for
        // back-compat; slice 11a switched all consumers to read from the typed slot
        // and slice 11b stops populating the legacy bag. Historical scores still
        // round-trip via the readMultiplier extra-fallback in this class.
        returnScore.setMultiplier(multiplier);

        return Optional.of(returnScore);
    }

    private int resolveMultiplier(String categoryName, String categoryIndex) {
        if(CORE_ECONOMICS.contains(categoryName)) {
            return CORE_ECONOMICS_MULTIPLIER;
        }
        if (INFOECONOMICS.contains(categoryName)) {
            return INFOECONOMICS_MULTIPLIER;
        }
        if (othersIndices.contains(categoryIndex)) {
            return OTHER_ECONOMICS_MULTIPLIER;
        }
        return 0;
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.ECONOMICS_JOURNAL_AIS;
    }

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
