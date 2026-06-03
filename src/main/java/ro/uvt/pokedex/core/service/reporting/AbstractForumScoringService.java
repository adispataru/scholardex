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
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;

public abstract class AbstractForumScoringService implements ScoringService {

    protected final ReportingLookupPort lookupPort;
    private static final Logger logger = LoggerFactory.getLogger(AbstractForumScoringService.class);

    protected AbstractForumScoringService(ReportingLookupPort lookupPort) {
        this.lookupPort = lookupPort;
    }

    /* ------------------------------------------------------------------ */
    /*  Common helpers                                                    */
    /* ------------------------------------------------------------------ */

    protected ScholardexForumView getForumFromActivity(ActivityInstance activity) {
        ScholardexForumView forum = new ScholardexForumView();
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
        final AtomicReference<String> scoringSource = new AtomicReference<>(null);
        final Map<String, Object> scoringInfo = new HashMap<>();
        /**
         * H52 slice 11: typed multiplier slot on the intermediate accumulator.
         * Mirrors {@link Score#getMultiplier()} so the per-candidate tie-break in
         * {@link EconomicsJournalScoringService#compareScoresByPointsAndMultiplier}
         * has a typed source of truth. Slice 11c removed the parallel
         * {@code extra} map that used to carry the same value as {@code extra["M"]}.
         */
        final AtomicReference<Integer> bestMultiplier = new AtomicReference<>(null);
    }

    protected ScoreResult initializeScoreResult() {
        return new ScoreResult();
    }

    protected Score createScore(ScoreResult r) {
        Score s = new Score();
        s.setScore(r.bestPoints.get());
        s.setYear(r.bestYear.get());
        s.setCoreRankingEquivalent(r.bestCategory.get().toString());
        s.setQuarter(r.bestQuarter.get().toString());
        s.setScoringSource(r.scoringSource.get());
        s.setScoringInfo(new HashMap<>(r.scoringInfo));
        s.setMultiplier(r.bestMultiplier.get());
        return s;
    }

    protected void copyProvenance(Score source, ScoreResult target) {
        if (source == null) {
            return;
        }
        target.scoringSource.set(source.getScoringSource());
        target.scoringInfo.clear();
        if (source.getScoringInfo() != null) {
            target.scoringInfo.putAll(source.getScoringInfo());
        }
    }

    protected void setProvenance(Score score, String scoringSource, Map<String, Object> scoringInfo) {
        if (score == null) {
            return;
        }
        score.setScoringSource(scoringSource);
        score.setScoringInfo(scoringInfo == null ? new HashMap<>() : new HashMap<>(scoringInfo));
    }

    /* ----------  Generic ranking helpers  ---------- */

    protected List<WoSRanking> getRankingsForForum(ScholardexForumView forum) {
        List<WoSRanking> rankings = new ArrayList<>();
        if (forum.getIssn() != null) {
            rankings = lookupPort.getRankingsByIssn(forum.getIssn());
        }
        if (rankings.isEmpty()) {
            rankings = lookupPort.getRankingsByIssn(forum.getEIssn());
        }
        return rankings;
    }

    protected boolean isCategoryInDomain(Domain domain, String category) {
        return ScoringCategorySupport.isCategoryEligibleForDomain(domain, category);
    }

    public WoSRanking.Quarter getBestQuarter(WoSRanking ranking){
        List<String> options = new ArrayList<>();
        for(String cat : ranking.getWebOfScienceCategoryIndex().keySet()){
            for(int year : ranking.getWebOfScienceCategoryIndex().get(cat).getQAis().keySet()){
                options.add(ranking.getWebOfScienceCategoryIndex().get(cat).getQAis().get(year).toString());
            }
            for(int year : ranking.getWebOfScienceCategoryIndex().get(cat).getQIF().keySet()){
                options.add(ranking.getWebOfScienceCategoryIndex().get(cat).getQIF().get(year).toString());
            }
        }
        options.sort(String::compareTo);
        if(options.isEmpty()){
            return WoSRanking.Quarter.NOT_FOUND;
        }
        return WoSRanking.Quarter.valueOf(options.getFirst());
    }

    protected void computeScoresWithForum(
            Domain domain,
            ScholardexForumView forum,
            List<Integer> allowedYears,
            ScoreResult result,
            BiFunction<ScholardexForumView, Integer, Optional<Score>> scoreExtractor) {

        if (forum == null) {
            return;
        }

        for (int year : allowedYears) {
            Optional<Score> points = scoreExtractor.apply(forum, year);
            if (points.isPresent() && points.get().getScore() > result.bestPoints.get()) {
                result.bestPoints.set(points.get().getScore());

                if(points.get().getCoreRankingEquivalent() != null) {
                    result.bestCategory.set(CoreConferenceRanking.Rank.valueOf(points.get().getCoreRankingEquivalent()));
                }
                result.bestYear.set(year);
                copyProvenance(points.get(), result);
                // H52 slice 11: accumulate the typed multiplier from the winning
                // candidate. EconomicsJournalScoringService is the only producer
                // today; every other strategy returns null which keeps the slot
                // null on the final Score.
                if (points.get().getMultiplier() != null) {
                    result.bestMultiplier.set(points.get().getMultiplier());
                }
            }
        }

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

    protected boolean isArticleOrReview(ScoringPublicationReadModel publication) {
        return PublicationSubtypeSupport.isSubtype(publication, "ar", "re");
    }

    protected List<Integer> getAllowedYearsForPublication(ScoringPublicationReadModel publication,
                                                         Indicator indicator) {
        List<Integer> allowedYears = new ArrayList<>();
        Optional<Integer> publicationYear = PersistenceYearSupport.extractYear(
                publication.getCoverDate(),
                publication.getId(),
                logger
        );
        publicationYear.ifPresent(pubYear -> allowedYears.addAll(indicator.getEffectiveScoreYearRange().allowedYears(pubYear)));
        int maxYear = lookupPort.maxAvailableYear();
        if (allowedYears.isEmpty()) {
            publicationYear.ifPresentOrElse(
                    allowedYears::add,
                    () -> allowedYears.add(maxYear)
            );
        } else if (allowedYears.stream().min(Integer::compareTo).orElse(maxYear) > maxYear) {
            allowedYears.clear();
            allowedYears.add(maxYear);
        }
        return allowedYears;
    }
}
