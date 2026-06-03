package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

public abstract class AbstractWoSForumScoringService extends AbstractForumScoringService {

    protected AbstractWoSForumScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    /**
     * Generic score-computing routine for WoS rankings — relies on a {@code scoreExtractor}
     * that tells how to pull the concrete score (AIS, RIS, IF, …)
     * from a ranking/year/category combination.
     */
    protected void computeScores(
            Domain domain,
            ScholardexForumView forum,
            List<Integer> allowedYears,
            ScoreResult result,
            Func4Arity<WoSRanking, Integer, String, WoSRanking.Rank, Optional<Score>> scoreExtractor,
            BiFunction<Score, ScoreResult, Boolean> compareFunction) {

        if (forum == null) {
            return;
        }
        if (forum.getIssn() == null && forum.getEIssn() == null) {
            return;
        }

        for (WoSRanking ranking : getRankingsForForum(forum)) {
            ranking.getWebOfScienceCategoryIndex().forEach((category, rank) -> {
                if (isCategoryInDomain(domain, category)) {
                    for (int year : allowedYears) {
                        Optional<Score> points = scoreExtractor.apply(ranking, year, category, rank);
                        if (points.isPresent() && compareFunction.apply(points.get(), result)) {
                            result.bestPoints.set(points.get().getScore());
                            if (points.get().getCoreRankingEquivalent() != null) {
                                result.bestCategory.set(CoreConferenceRanking.Rank.valueOf(points.get().getCoreRankingEquivalent()));
                            }
                            if (points.get().getQuarter() != null) {
                                result.bestQuarter.set(WoSRanking.Quarter.valueOf(points.get().getQuarter()));
                            }
                            result.bestYear.set(year);
                            copyProvenance(points.get(), result);
                            // H52 slice 11: accumulate the typed multiplier from
                            // the winning candidate so EJSS's compare and the final
                            // createScore both see it without going through extra.
                            if (points.get().getMultiplier() != null) {
                                result.bestMultiplier.set(points.get().getMultiplier());
                            }
                        }
                    }
                }
            });
        }
    }

    protected boolean compareScoresByPoints(Score score, ScoreResult result) {
        if (Math.abs(score.getScore() - result.bestPoints.get()) < 0.00000001) {
            // If either quarter is unknown, don't displace an existing best
            if (score.getQuarter() == null || result.bestQuarter.get() == null) {
                return false;
            }
            return score.getQuarter().compareTo(result.bestQuarter.toString()) < 0;
        }
        return score.getScore() > result.bestPoints.get();
    }
}
