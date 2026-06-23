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
    /** How many years back the ItemYear carry-forward will look for the journal's last known ranking. */
    private static final int YEAR_CARRY_FORWARD_LIMIT = 30;

    protected void computeScores(
            Domain domain,
            ScholardexForumView forum,
            List<Integer> allowedYears,
            ScoreResult result,
            Func4Arity<WoSRanking, Integer, String, WoSRanking.Rank, Optional<Score>> scoreExtractor,
            BiFunction<Score, ScoreResult, Boolean> compareFunction) {
        computeScores(domain, forum, allowedYears, result, scoreExtractor, compareFunction, false);
    }

    /**
     * @param carryForwardToLatestAvailableYear when no ranking is found for the requested years, fall back to the most
     *   recent ranked year at or before the latest requested year. JCR/WoS rankings lag ~1–2 years, so an ItemYear
     *   indicator's single (recent) year often has no data yet; this carries the journal's last known quartile forward
     *   so current-year papers still score. Not "best across all years" — the latest applicable ranking (a journal can
     *   decline over time). No-op unless the primary pass found nothing.
     */
    protected void computeScores(
            Domain domain,
            ScholardexForumView forum,
            List<Integer> allowedYears,
            ScoreResult result,
            Func4Arity<WoSRanking, Integer, String, WoSRanking.Rank, Optional<Score>> scoreExtractor,
            BiFunction<Score, ScoreResult, Boolean> compareFunction,
            boolean carryForwardToLatestAvailableYear) {

        if (forum == null) {
            return;
        }
        if (forum.getIssn() == null && forum.getEIssn() == null) {
            return;
        }

        scoreOverYears(domain, forum, allowedYears, result, scoreExtractor, compareFunction);

        if (carryForwardToLatestAvailableYear
                && result.bestPoints.get() == 0
                && allowedYears != null && !allowedYears.isEmpty()) {
            int cap = java.util.Collections.max(allowedYears);
            for (int year = cap - 1; year >= cap - YEAR_CARRY_FORWARD_LIMIT && result.bestPoints.get() == 0; year--) {
                scoreOverYears(domain, forum, List.of(year), result, scoreExtractor, compareFunction);
            }
        }
    }

    private void scoreOverYears(
            Domain domain,
            ScholardexForumView forum,
            List<Integer> years,
            ScoreResult result,
            Func4Arity<WoSRanking, Integer, String, WoSRanking.Rank, Optional<Score>> scoreExtractor,
            BiFunction<Score, ScoreResult, Boolean> compareFunction) {
        for (WoSRanking ranking : getRankingsForForum(forum)) {
            ranking.getWebOfScienceCategoryIndex().forEach((category, rank) -> {
                if (isCategoryInDomain(domain, category)) {
                    for (int year : years) {
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
