package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaContext;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ScientificProductionService {
    private static final Logger log = LoggerFactory.getLogger(ScientificProductionService.class);

    private final ScoringFactoryService scoringFactoryService;
    private final FormulaEvaluator formulaEvaluator;


    public Map<String, Score> calculateScientificProductionScore(List<? extends ScoringPublicationReadModel> publications, Indicator indicator) {

        // H52 slice 11d.1: typed-strategy dispatch. The legacy GENERIC_COUNT
        // short-circuit reads the strategy off the {@link IndicatorKind} now;
        // the {@code IndicatorKind.GenericCount} record exists but isn't reachable
        // through {@code fromLegacy}, because the legacy {@code (PUBLICATIONS,
        // GENERIC_COUNT)} pairing maps to {@code Publications(ALL, GENERIC_COUNT)}.
        if(indicator.isGenericCount()) {
            Map<String, Score> result = new HashMap<>();
            publications.forEach(pub -> {
                Score score = new Score();
                score.setScore(1.0);
                score.setAuthorScore(1.0);
                result.put(pub.getTitle(), score);
            });
            Score total = new Score();
            total.setAuthorScore(publications.size());
            result.put("total", total);
            return result;
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());

        double totalScore = 0;
        Map<String, Score> interResult = new HashMap<>();
        if(scoringService != null) {
            for (ScoringPublicationReadModel publication : publications) {
                Score score = calculatePublicationScore(publication, indicator, scoringService);
                if(score.getScore() + score.getAuthorScore() > 0.0) {
                    interResult.put(publication.getTitle(), score);
                    totalScore += score.getAuthorScore();
                }
            }
        }
        Map<String, Score> result = new HashMap<>();
        // H52 slice 11d.3: typed-selector check via the Indicator helpers. The
        // legacy {@code Selector.TOP_10} maps to {@code TopN(10)} so the limit is
        // read from the typed slot instead of hardcoding 10.
        if (indicator.isTopNSelector()) {
            int topLimit = indicator.topNLimit();
            {
                totalScore = 0.0;
                if(publications.size() > topLimit) {
                    publications = new ArrayList<>(publications);
                    publications.sort((p1, p2) -> Double.compare(
                            interResult.get(p2.getTitle()) != null ? interResult.get(p2.getTitle()).getAuthorScore(): 0,
                            interResult.get(p1.getTitle()) != null ? interResult.get(p1.getTitle()).getAuthorScore() : 0));
                }
                int limit = Math.min(topLimit, publications.size());
                for (int i = 0; i < limit; i++) {
                    ScoringPublicationReadModel pub = publications.get(i);
                    Score score = interResult.get(pub.getTitle());
                    if(score != null) {
                        result.put(pub.getTitle(), score);
                        totalScore += score.getAuthorScore();
                    }
                }
            }
        }else{
            result = interResult;
        }
        Score total = new Score();
        total.setAuthorScore(totalScore);
        result.put("total", total);

        return result;
    }

    public Map<String, Score> calculateScientificImpactScore(
            ScoringPublicationReadModel cited,
            List<? extends ScoringPublicationReadModel> publications,
            Indicator indicator
    ) {
        return calculateScientificImpactScore(cited, publications, indicator, null);
    }

    public Map<String, Score> calculateScientificImpactScore(
            ScoringPublicationReadModel cited,
            List<? extends ScoringPublicationReadModel> publications,
            Indicator indicator,
            Map<String, Score> cachedBaseScoresByCitingPublicationId
    ) {
        long totalStartedAtNanos = System.nanoTime();
        Map<String, Score> result = new HashMap<>();
        // H52 slice 11d.1: same GenericCount short-circuit as the production
        // path; CITATIONS shows up as {@code IndicatorKind.Citations(excludeSelf, …)}.
        if(indicator.isGenericCount()) {
            publications.forEach(pub -> {
                Score score = new Score();
                score.setScore(1.0);
                score.setAuthorScore(1.0);
                result.put(pub.getTitle(), score);
            });
            Score total = new Score();
            total.setAuthorScore(publications.size());
            result.put("total", total);
            return result;
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());
        double totalAuthorScore = 0;
        double totalScore = 0;
        long baseScoreLookupNanos = 0L;
        long formulaEvalNanos = 0L;
        long aggregationNanos = 0L;
        int positiveScores = 0;
        if(scoringService != null) {
            for (ScoringPublicationReadModel publication : publications) {
                ScoreComputationTiming scoreTiming = new ScoreComputationTiming();
                Score score = calculateCitationScore(
                        cited,
                        publication,
                        indicator,
                        scoringService,
                        cachedBaseScoresByCitingPublicationId,
                        scoreTiming
                );
                baseScoreLookupNanos += scoreTiming.baseScoreLookupNanos();
                formulaEvalNanos += scoreTiming.formulaEvalNanos();

                long aggregationStartedAtNanos = System.nanoTime();
                if(score.getScore() + score.getAuthorScore() > 0.0) {
                    totalAuthorScore += score.getAuthorScore();
                    totalScore += score.getScore();
                    result.put(publication.getTitle(), score);
                    positiveScores++;
                }
                aggregationNanos += (System.nanoTime() - aggregationStartedAtNanos);
            }
        }
        Score total = new Score();
        total.setAuthorScore(totalAuthorScore);
        total.setScore(totalScore);

        result.put("total", total);
        if (log.isDebugEnabled()) {
            String citedId = cited == null ? "null" : cited.getId();
            log.debug(
                    "Scientific impact timings [citedId={}, kind={}, citingPublications={}, matchedScores={}]: baseScoreLookupMs={}, formulaEvalMs={}, aggregationMs={}, totalMs={}",
                    citedId,
                    indicator.getEffectiveKind(),
                    publications.size(),
                    positiveScores,
                    nanosToMillis(baseScoreLookupNanos),
                    nanosToMillis(formulaEvalNanos),
                    nanosToMillis(aggregationNanos),
                    nanosToMillis(System.nanoTime() - totalStartedAtNanos)
            );
        }
        return result;
    }

    private Score calculatePublicationScore(ScoringPublicationReadModel publication, Indicator indicator, ScoringService scoringService) {
        return getScore(publication, publication, indicator, scoringService, null, null);
    }

    private Score calculateCitationScore(ScoringPublicationReadModel cited, ScoringPublicationReadModel citing, Indicator indicator, ScoringService scoringService) {
        return getScore(cited, citing, indicator, scoringService, null, null);
    }

    private Score calculateCitationScore(
            ScoringPublicationReadModel cited,
            ScoringPublicationReadModel citing,
            Indicator indicator,
            ScoringService scoringService,
            Map<String, Score> cachedBaseScoresByCitingPublicationId,
            ScoreComputationTiming timing
    ) {
        return getScore(cited, citing, indicator, scoringService, cachedBaseScoresByCitingPublicationId, timing);
    }

    private Score getScore(
            ScoringPublicationReadModel cited,
            ScoringPublicationReadModel citing,
            Indicator indicator,
            ScoringService scoringService,
            Map<String, Score> cachedBaseScoresByCitingPublicationId,
            ScoreComputationTiming timing
    ) {
        // Universal subtype gate: only original research contributions carry forum points,
        // across every domain/strategy. Non-research subtypes (editorial, erratum, note,
        // letter, …) score nothing whether they are the candidate's own publication
        // (perspective b) or a citing publication (perspective c).
        if (!PublicationSubtypeSupport.isResearchContribution(citing)) {
            return new Score();
        }

        Score baseScore = null;
        long baseScoreLookupNanos = 0L;
        if (cachedBaseScoresByCitingPublicationId != null && citing != null && citing.getId() != null) {
            baseScore = cachedBaseScoresByCitingPublicationId.get(citing.getId());
        }
        if (baseScore == null) {
            long baseScoreLookupStartedAtNanos = System.nanoTime();
            baseScore = scoringService.getScore(citing, indicator);
            baseScoreLookupNanos = System.nanoTime() - baseScoreLookupStartedAtNanos;
        }
        if (timing != null) {
            timing.addBaseScoreLookupNanos(baseScoreLookupNanos);
        }
        Score result = copyScore(baseScore);
        if(result.getScore() > 0) {
            int numberOfAuthors = cited.getAuthorCount();

            // H52 slice 11c: typed-only path. M comes from the typed multiplier slot
            // (populated by EconomicsJournalScoringService); the legacy
            // {@code extra["M"]} bag is gone.
            FormulaContext.Builder builder = FormulaContext.builder()
                    .put("S", result.getScore())
                    .put("N", numberOfAuthors)
                    .put("Q", result.getQuarter());
            if (result.getMultiplier() != null) {
                builder.put("M", result.getMultiplier());
            }
            FormulaContext ctx = builder.build();

            long formulaEvalStartedAtNanos = System.nanoTime();
            double finalScore = formulaEvaluator.eval(indicator.getFormula(), ctx);
            long formulaEvalNanos = System.nanoTime() - formulaEvalStartedAtNanos;
            if (timing != null) {
                timing.addFormulaEvalNanos(formulaEvalNanos);
            }
            result.setAuthorScore(finalScore);
        }
        return result;
    }

    public Map<String, Score> precomputeCitationBaseScores(List<? extends ScoringPublicationReadModel> citingPublications, Indicator indicator) {
        if (citingPublications == null || citingPublications.isEmpty()) {
            return Map.of();
        }
        // H52 slice 11d.1: typed-strategy check. GenericCount has no per-publication
        // base scoring, so the citation cache stays empty. Also guards an indicator
        // with no resolvable kind (legacy strategy=null was a real guarded case).
        if (indicator == null || indicator.getEffectiveKind() == null || indicator.isGenericCount()) {
            return Map.of();
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());
        if (scoringService == null) {
            return Map.of();
        }
        Map<String, Score> cached = new HashMap<>();
        for (ScoringPublicationReadModel citingPublication : citingPublications) {
            if (citingPublication == null || citingPublication.getId() == null || cached.containsKey(citingPublication.getId())) {
                continue;
            }
            // Mirror the universal subtype gate so non-research citing publications are
            // cached as zero rather than scored.
            Score baseScore = PublicationSubtypeSupport.isResearchContribution(citingPublication)
                    ? scoringService.getScore(citingPublication, indicator)
                    : new Score();
            cached.put(citingPublication.getId(), copyScore(baseScore));
        }
        return cached;
    }

    private Score copyScore(Score source) {
        if (source == null) {
            return new Score();
        }
        Score target = new Score();
        target.setScore(source.getScore());
        target.setYear(source.getYear());
        target.setCoreRankingEquivalent(source.getCoreRankingEquivalent());
        target.setQuarter(source.getQuarter());
        target.setScoringSource(source.getScoringSource());
        target.setScoringInfo(new HashMap<>(source.getScoringInfo() == null ? Map.of() : source.getScoringInfo()));
        target.setAuthorScore(source.getAuthorScore());
        // H52 slice 11c: typed multiplier propagates; extra/errors/details are gone.
        target.setMultiplier(source.getMultiplier());
        return target;
    }

    private long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private static class ScoreComputationTiming {
        private long baseScoreLookupNanos;
        private long formulaEvalNanos;

        void addBaseScoreLookupNanos(long value) {
            baseScoreLookupNanos += value;
        }

        void addFormulaEvalNanos(long value) {
            formulaEvalNanos += value;
        }

        long baseScoreLookupNanos() {
            return baseScoreLookupNanos;
        }

        long formulaEvalNanos() {
            return formulaEvalNanos;
        }
    }


}
