package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.mvel2.MVEL;
import org.mvel2.PropertyAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.ActivityIndicator;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ActivityReportingService {
    private static final Logger log = LoggerFactory.getLogger(ActivityReportingService.class);
    private final ScoringFactoryService scoringFactoryService;

    public Map<String, Score> calculateActivityScores(List<ActivityInstance> activities, Indicator indicator) {

        Map<String, Score> result = new HashMap<>();
        double totalScore = 0;

        for (ActivityInstance act : activities) {

            Score score = calculateActivityScore(act, indicator);
            if(score.getScore() + score.getAuthorScore() > 0.0) {
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
        if(!indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_ACTIVITY)) {
            if(!indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_COUNT)) {
                ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());
                Score score = scoringService.getScore(activity, indicator);
                result.setCategory(score.getCategory());
                result.setQuarter(score.getQuarter());
                result.setYear(score.getYear());
                result.setScore(score.getScore());
                result.setExtra(score.getExtra());
                variables.put("S", score.getScore());
                for(String key: score.getExtra().keySet()){
                    variables.put(key, result.getExtra().get(key));
                }
            }else{
                result.setCategory("Generic Count");
                result.setYear(activity.getYear());
                result.setScore(1.0);
                variables.put("S", 1.0);
            }
        }
        if(result.getScore() > 0.0) {


            StringBuilder sb = new StringBuilder();
            variables.forEach((k, v) -> {
                if (rawformula.contains(k))
                    sb.append(k).append(": ").append(v).append(", ");

            });
            result.setDetails(sb.toString());

            String formula = indicator.getFormula();

            if (formula.contains("max")) {
                formula = formula.replaceAll("max", "Math.max");
                variables.put("Math", Math.class);
            }
            if (formula.contains("min")) {
                formula = formula.replaceAll("min", "Math.min");
                variables.put("Math", Math.class);
            }
            try {
                double finalScore = MVEL.eval(formula, variables, Double.class);
                result.setAuthorScore(finalScore);
            } catch (PropertyAccessException ex) {
                log.error("Error evaluating formula for indicator {} and activity {}", indicator.getId(), activity.getId(), ex);
                result.setAuthorScore(0.0);
            }
        }
        return result;
    }


}
