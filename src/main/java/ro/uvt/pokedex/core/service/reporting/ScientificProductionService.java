package ro.uvt.pokedex.core.service.reporting;

import org.mvel2.MVEL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ScientificProductionService {
    private static final Logger log = LoggerFactory.getLogger(ScientificProductionService.class);

    private final ScoringFactoryService scoringFactoryService;


    public Map<String, Score> calculateScientificProductionScore(List<Publication> publications, Indicator indicator) {

        if(indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_COUNT)) {
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
            for (Publication publication : publications) {
                Score score = calculatePublicationScore(publication, indicator, scoringService);
                if(score.getScore() + score.getAuthorScore() > 0.0) {
                    interResult.put(publication.getTitle(), score);
                    totalScore += score.getAuthorScore();
                }
            }
        }
        Map<String, Score> result = new HashMap<>();
        if(indicator.getSelector() != null && !indicator.getSelector().equals(Indicator.Selector.ALL)) {
            if(indicator.getSelector().equals(Indicator.Selector.TOP_10)) {
                totalScore = 0.0;
                if(publications.size() > 10) {
                    publications.sort((p1, p2) -> Double.compare(
                            interResult.get(p2.getTitle()) != null ? interResult.get(p2.getTitle()).getAuthorScore(): 0,
                            interResult.get(p1.getTitle()) != null ? interResult.get(p1.getTitle()).getAuthorScore() : 0));
                }
                int limit = Math.min(10, publications.size());
                for (int i = 0; i < limit; i++) {
                    Publication pub = publications.get(i);
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

    public Map<String, Score> calculateScientificImpactScore(Publication cited, List<Publication> publications, Indicator indicator) {
        return calculateScientificImpactScore(cited, publications, indicator, null);
    }

    public Map<String, Score> calculateScientificImpactScore(
            Publication cited,
            List<Publication> publications,
            Indicator indicator,
            Map<String, Score> cachedBaseScoresByCitingPublicationId
    ) {
        long totalStartedAtNanos = System.nanoTime();
        Map<String, Score> result = new HashMap<>();
        if(indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_COUNT)) {
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
            for (Publication publication : publications) {
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
                    "Scientific impact timings [citedId={}, strategy={}, outputType={}, citingPublications={}, matchedScores={}]: baseScoreLookupMs={}, formulaEvalMs={}, aggregationMs={}, totalMs={}",
                    citedId,
                    indicator.getScoringStrategy(),
                    indicator.getOutputType(),
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

    private Score calculatePublicationScore(Publication publication, Indicator indicator, ScoringService scoringService) {
        return getScore(publication, publication, indicator, scoringService, null, null);
    }

    private Score calculateCitationScore(Publication cited, Publication citing, Indicator indicator, ScoringService scoringService) {
        return getScore(cited, citing, indicator, scoringService, null, null);
    }

    private Score calculateCitationScore(
            Publication cited,
            Publication citing,
            Indicator indicator,
            ScoringService scoringService,
            Map<String, Score> cachedBaseScoresByCitingPublicationId,
            ScoreComputationTiming timing
    ) {
        return getScore(cited, citing, indicator, scoringService, cachedBaseScoresByCitingPublicationId, timing);
    }

    private Score getScore(
            Publication cited,
            Publication citing,
            Indicator indicator,
            ScoringService scoringService,
            Map<String, Score> cachedBaseScoresByCitingPublicationId,
            ScoreComputationTiming timing
    ) {
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
            int numberOfAuthors = cited.getAuthors().size();

            Map<String, Object> variables = new HashMap<>();
            variables.put("S", result.getScore());
            variables.put("N", numberOfAuthors);
            variables.put("Q", result.getQuarter());
            for (String key : result.getExtra().keySet()) {
                variables.put(key, result.getExtra().get(key));
            }

            String formula = indicator.getFormula();
            if (formula.contains("max")) {
                formula = formula.replaceAll("max", "Math.max");
                variables.put("Math", Math.class);
            }

            long formulaEvalStartedAtNanos = System.nanoTime();
            double finalScore = MVEL.eval(formula, variables, Double.class);
            long formulaEvalNanos = System.nanoTime() - formulaEvalStartedAtNanos;
            if (timing != null) {
                timing.addFormulaEvalNanos(formulaEvalNanos);
            }
            result.setAuthorScore(finalScore);
        }
        return result;
    }

    public Map<String, Score> precomputeCitationBaseScores(List<Publication> citingPublications, Indicator indicator) {
        if (citingPublications == null || citingPublications.isEmpty()) {
            return Map.of();
        }
        if (indicator == null || indicator.getScoringStrategy() == null || indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_COUNT)) {
            return Map.of();
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());
        if (scoringService == null) {
            return Map.of();
        }
        Map<String, Score> cached = new HashMap<>();
        for (Publication citingPublication : citingPublications) {
            if (citingPublication == null || citingPublication.getId() == null || cached.containsKey(citingPublication.getId())) {
                continue;
            }
            Score baseScore = scoringService.getScore(citingPublication, indicator);
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
        target.setCategory(source.getCategory());
        target.setQuarter(source.getQuarter());
        target.setAuthorScore(source.getAuthorScore());
        target.setDetails(source.getDetails());
        target.setErrors(new HashMap<>(source.getErrors() == null ? Map.of() : source.getErrors()));
        target.setExtra(new HashMap<>(source.getExtra() == null ? Map.of() : source.getExtra()));
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
