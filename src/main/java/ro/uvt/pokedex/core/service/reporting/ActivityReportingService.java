package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaContext;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

@Service
@RequiredArgsConstructor
public class ActivityReportingService {
    private static final Logger log = LoggerFactory.getLogger(ActivityReportingService.class);
    private final ScoringFactoryService scoringFactoryService;
    private final FormulaEvaluator formulaEvaluator;

    public Map<String, Score> calculateActivityScores(List<ActivityInstance> activities, Indicator indicator) {

        Map<String, Score> result = new HashMap<>();
        double totalScore = 0;

        for (ActivityInstance act : activities) {

            Score score = calculateActivityScore(act, indicator);
            boolean hasScore = indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_ACTIVITY)
                    ? score.getAuthorScore() > 0.0
                    : score.getScore() + score.getAuthorScore() > 0.0;
            if(hasScore) {
                totalScore += score.getAuthorScore();
                result.put(act.getId(), score);
            }
        }

        Score total = new Score();
        total.setAuthorScore(totalScore);

        result.put("total", total);
        return result;
    }

    private Score calculateActivityScore(ActivityInstance activity, Indicator indicator) {
        Score result = new Score();

        Map<String, Object> variables = new HashMap<>();
        if(activity.getActivity().getFields() != null) {
            for (Activity.Field key : activity.getActivity().getFields()) {
                String fieldName = key.getName();
                String value = activity.getFields().get(fieldName);
                if (key.isNumber()) {
                    variables.put(fieldName, Double.parseDouble(value));
                } else {
                    variables.put(fieldName, value);
                }
            }
        }
        final String rawformula = indicator.getFormula();
        if(indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_ACTIVITY)) {
            result.setCoreRankingEquivalent("Generic Activity");
            result.setYear(activity.getYear());
            result.setScore(1.0);
            variables.put("S", 1.0);
        } else if(indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_COUNT)) {
            result.setCoreRankingEquivalent("Generic Count");
            result.setYear(activity.getYear());
            result.setScore(1.0);
            variables.put("S", 1.0);
        } else {
            ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());
            Score score = scoringService.getScore(activity, indicator);
            result.setCoreRankingEquivalent(score.getCoreRankingEquivalent());
            result.setQuarter(score.getQuarter());
            result.setYear(score.getYear());
            result.setScore(score.getScore());
            result.setScoringSource(score.getScoringSource());
            result.setScoringInfo(new HashMap<>(score.getScoringInfo() == null ? Map.of() : score.getScoringInfo()));
            result.setExtra(score.getExtra());
            variables.put("S", score.getScore());
            for(String key: score.getExtra().keySet()){
                variables.put(key, result.getExtra().get(key));
            }
        }
        if(result.getScore() > 0.0) {

            StringBuilder sb = new StringBuilder();
            variables.forEach((k, v) -> {
                if (rawformula.contains(k))
                    sb.append(k).append(": ").append(v).append(", ");

            });
            result.setDetails(sb.toString());

            FormulaContext ctx = FormulaContext.builder().putAll(variables).build();
            OptionalDouble finalScore = formulaEvaluator.tryEval(rawformula, ctx);
            if (finalScore.isPresent()) {
                result.setAuthorScore(finalScore.getAsDouble());
            } else {
                // Preserves pre-v1 behavior: PropertyAccessException → 0.0. The evaluator
                // already logged the formula + error; add the indicator/activity context
                // we have here so the trace points to the failing row.
                log.error("Error evaluating formula for indicator {} and activity {}",
                        indicator.getId(), activity.getId());
                result.setAuthorScore(0.0);
            }
        }
        return result;
    }

}
