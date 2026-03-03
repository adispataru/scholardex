package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

public abstract class AbstractWoSForumScoringService implements ScoringService {

    protected final CacheService cacheService;
    private static final Logger logger = LoggerFactory.getLogger(AbstractWoSForumScoringService.class);

    protected AbstractWoSForumScoringService(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /* ------------------------------------------------------------------ */
    /*  Common helpers                                                    */
    /* ------------------------------------------------------------------ */

    protected Forum getForumFromActivity(ActivityInstance activity) {
        Forum forum = new Forum();
        if (activity.getReferenceFields().containsKey(Activity.ReferenceField.FORUM_NAME)) {
            forum.setPublicationName(activity.getReferenceFields().get(Activity.ReferenceField.FORUM_NAME));
        }
        if (activity.getReferenceFields().containsKey(Activity.ReferenceField.FORUM_ISSN)) {
            forum.setIssn(activity.getReferenceFields().get(Activity.ReferenceField.FORUM_ISSN));
        }
        if (activity.getReferenceFields().containsKey(Activity.ReferenceField.FORUM_EISSN)) {
            forum.setEIssn(activity.getReferenceFields().get(Activity.ReferenceField.FORUM_EISSN));
        }
        if (activity.getReferenceFields().containsKey(Activity.ReferenceField.FORUM_ISBN)) {
            forum.setIsbn(activity.getReferenceFields().get(Activity.ReferenceField.FORUM_ISBN));
        }
        if (activity.getReferenceFields().containsKey(Activity.ReferenceField.FORUM_PUBLISHER)) {
            forum.setPublisher(activity.getReferenceFields().get(Activity.ReferenceField.FORUM_PUBLISHER));
        }
        if (activity.getReferenceFields().containsKey(Activity.ReferenceField.EVENT_NAME)) {
            forum.setPublicationName(activity.getReferenceFields().get(Activity.ReferenceField.EVENT_NAME));
        }
        return forum;
    }

    /* ----------  Scoring utility objects & factory  ---------- */

    protected static class ScoreResult {
        final AtomicReference<Double> bestPoints  = new AtomicReference<>(0.0);
        final AtomicReference<Integer> bestYear   = new AtomicReference<>(0);
        final AtomicReference<CoreConferenceRanking.Rank> bestCategory =
                new AtomicReference<>(CoreConferenceRanking.Rank.NON_RANK);
        final AtomicReference<WoSRanking.Quarter> bestQuarter =
                new AtomicReference<>(WoSRanking.Quarter.NOT_FOUND);
        final Map<String, Object> extra = new HashMap<>();
    }

    protected ScoreResult initializeScoreResult() {
        return new ScoreResult();
    }

    protected Score createScore(ScoreResult r) {
        Score s = new Score();
        s.setScore(r.bestPoints.get());
        s.setYear(r.bestYear.get());
        s.setCategory(r.bestCategory.get().toString());
        s.setQuarter(r.bestQuarter.get().toString());
        s.setExtra(r.extra);
        return s;
    }

    /* ----------  Generic ranking helpers  ---------- */

    protected List<WoSRanking> getRankingsForForum(Forum forum) {
        List<WoSRanking> rankings = new ArrayList<>();
        if (forum.getIssn() != null) {
            rankings = cacheService.getCachedRankingsByIssn(forum.getIssn());
        }
        if (rankings.isEmpty()) {
            rankings = cacheService.getCachedRankingsByIssn(forum.getEIssn());
        }
        return rankings;
    }

    protected boolean isCategoryInDomain(Domain domain, String category) {
        return ScoringCategorySupport.isCategoryEligibleForDomain(domain, category);
    }

    /**
     * Generic score-computing routine – relies on a {@code scoreExtractor}
     * that tells how to pull the concrete score (AIS, RIS, IF, …)
     * from a ranking/year combination.
     */
    protected void computeScores(
            Domain domain,
            Forum forum,
            List<Integer> allowedYears,
            ScoreResult result,
            Func4Arity<WoSRanking, Integer, String, WoSRanking.Rank, Optional<Score>> scoreExtractor,
            BiFunction<Score, ScoreResult, Boolean> compareFunction) {

        if (forum == null) {
            return;
        }
        if(forum.getIssn() == null && forum.getEIssn() == null) {
            return;
        }

        for (WoSRanking ranking : getRankingsForForum(forum)) {
            ranking.getWebOfScienceCategoryIndex().forEach((category, rank) -> {
                if (isCategoryInDomain(domain, category)) {
                    for (int year : allowedYears) {
                        Optional<Score> points = scoreExtractor.apply(ranking, year, category, rank);
                        if (points.isPresent() && compareFunction.apply(points.get(), result)) {
                            result.bestPoints.set(points.get().getScore());
//                            result.bestQuarter.set(getBestQuarter(ranking));
                            result.bestQuarter.set(WoSRanking.Quarter.valueOf(points.get().getQuarter()));
                            result.bestYear.set(year);
                            result.extra.putAll(points.get().getExtra());
                        }
                    }
                }
            });
        }
    }

    protected boolean compareScoresByPoints(Score score, ScoreResult result) {
        if(Math.abs(score.getScore() - result.bestPoints.get()) < 0.00000001) {
            return score.getQuarter().compareTo(result.bestQuarter.toString()) < 0;
        }
        return score.getScore() > result.bestPoints.get();
    }

    protected CoreConferenceRanking.Rank getCategory(Double aDouble) {
        int points = aDouble.intValue();
        switch (points) {
            case 12 -> {
                return CoreConferenceRanking.Rank.A_STAR;
            }
            case 8 -> {
                return CoreConferenceRanking.Rank.A;
            }
            case 4 -> {
                return CoreConferenceRanking.Rank.B;
            }
            case 2 -> {
                return CoreConferenceRanking.Rank.C;
            }
            case 1 -> {
                return CoreConferenceRanking.Rank.D;
            }
            default -> {
                return CoreConferenceRanking.Rank.NON_RANK;
            }
        }
    }

    protected void computeScoresWithUniversity(
            URAPUniversityRanking ranking,
            List<Integer> allowedYears,
            ScoreResult result,
            BiFunction<URAPUniversityRanking, Integer, Optional<Double>> scoreExtractor) {


        for (int year : allowedYears) {
            Optional<Double> points = scoreExtractor.apply(ranking, year);
            if (points.isPresent()){
                if(result.bestPoints.get().equals(0.0)) {
                    result.bestPoints.set(points.get());
                    result.bestYear.set(year);
                }else if(points.get() < result.bestPoints.get()) {
                    result.bestPoints.set(points.get());
                    result.bestYear.set(year);
                }
            }
        }

    }

    protected boolean isArticleOrReview(Publication publication) {
        return PublicationSubtypeSupport.isSubtype(publication, "ar", "re");
    }

    protected List<Integer> getAllowedYearsForPublication(Publication publication,
                                                        Indicator indicator) {
        List<Integer> allowedYears = new ArrayList<>();
        try {
            int pubYear = Integer.parseInt(publication.getCoverDate().substring(0, 4));
            allowedYears.addAll(Indicator.parseYearRange(indicator.getScoreYearRange(), pubYear));
        } catch (Exception e) {
            logger.error("Could not parse year for publication: {}", publication.getCoverDate(), e);
        }
        if(allowedYears.isEmpty() || (allowedYears.stream().min(Integer::compareTo).get() > LAST_YEAR))
            allowedYears.add(LAST_YEAR);
        return allowedYears;
    }
}
