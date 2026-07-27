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
    /** 2026 COMISIA 27 residual tier ("Social Science & Science — toate cu excepția celor menționate"). */
    private static final int OTHER_ECONOMICS_MULTIPLIER_2026 = 3;

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
            final boolean m2026 = indicator.usesEconomicsM2026();
            computeScores(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    (ranking, year, category, rank) -> computeEconomicsScore(ranking, year, category, rank, m2026),
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

        final boolean m2026 = indicator.usesEconomicsM2026();
        computeScores(
                domain,
                forum,
                allowedYears,
                scoreResult,
                (ranking, year, category, rank) -> computeEconomicsScore(ranking, year, category, rank, m2026),
                this::compareScoresByPointsAndMultiplier
        );

        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  CS-specific scoring logic                                        */
    /* ------------------------------------------------------------------ */

    /**
     * {@code m2026} selects the COMISIA 27 2026 M table (OR&MS in Core, residual 3) over the frozen
     * 2016 Anexa 27 one — threaded from {@link Indicator#usesEconomicsM2026()} by the getScore lambdas.
     */
    private Optional<Score> computeEconomicsScore(WoSRanking ranking, int year, String category,
                                                  WoSRanking.Rank rank, boolean m2026) {

        Score returnScore = new Score();
        if( ranking.getScore() == null || ranking.getScore().getAis() == null || ranking.getScore().getAis().get(year) == null) {
            return Optional.empty();
        }
        returnScore.setScore(ranking.getScore().getAis().get(year));

        String cat = ScoringCategorySupport.extractCategoryName(category);
        String index = ScoringCategorySupport.extractCategoryIndex(category);
        int multiplier = resolveMultiplier(cat, index, m2026);
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

    private int resolveMultiplier(String categoryName, String categoryIndex, boolean m2026) {
        // The FEAA standard's Table 1 ("revistelor ISI ... conform JCR") lives entirely inside the
        // SCIE/SSCI universe — its residual tier is literally named "Social Science & Science". The named
        // Core-Economics/Infoeconomics tiers are subsets OF that universe, so an ESCI placement in e.g.
        // category "ECONOMICS" never qualifies for any multiplier (ESCI journals carry AIS values in the
        // data since ~2018, which made them silently match the name-only tiers before this gate).
        if (!othersIndices.contains(categoryIndex)) {
            return 0;
        }
        // 2026 COMISIA 27 table: OR&MS is listed under Core Economics (it sits in the 2016 Infoeconomics
        // list below), and the residual "Social Science & Science" tier drops from 6 to 3.
        if (m2026 && "OPERATIONS RESEARCH & MANAGEMENT SCIENCE".equals(categoryName)) {
            return CORE_ECONOMICS_MULTIPLIER;
        }
        if(CORE_ECONOMICS.contains(categoryName)) {
            return CORE_ECONOMICS_MULTIPLIER;
        }
        if (INFOECONOMICS.contains(categoryName)) {
            return INFOECONOMICS_MULTIPLIER;
        }
        return m2026 ? OTHER_ECONOMICS_MULTIPLIER_2026 : OTHER_ECONOMICS_MULTIPLIER;
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
