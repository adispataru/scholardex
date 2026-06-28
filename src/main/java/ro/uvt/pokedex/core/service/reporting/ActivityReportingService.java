package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.service.application.ScholardexProjectReadPort;
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
    // H64 slice 4a: resolve a linked canonical project so indicators can prefer trusted values (A10 budget) / gate on funder.
    private final ScholardexProjectReadPort scholardexProjectReadPort;

    public Map<String, Score> calculateActivityScores(List<ActivityInstance> activities, Indicator indicator) {

        Map<String, Score> result = new HashMap<>();
        double totalScore = 0;

        for (ActivityInstance act : activities) {

            Score score = calculateActivityScore(act, indicator);
            // H52 slice 11d.1: typed-strategy check via the Indicator helper.
            // GENERIC_ACTIVITY contributes when its formula yields a positive
            // author score; every other kind needs a positive base score too.
            boolean hasScore = indicator.isGenericActivity()
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
        // H65: physics didactic activities (A1–A8) score k/Nef, where Nef = the effective author count bracket of the
        // entered N_autori. Bind it when the activity carries that field so the formula (e.g. "4/Nef") can divide.
        // A blank/0 author count falls back to Nef=1 (no divide-by-zero), since a manual item has at least one author.
        Object nAutori = variables.get("N_autori");
        if (nAutori instanceof Number n) {
            int rawAuthors = n.intValue();
            variables.put("Nef", rawAuthors >= 1 ? EffectiveAuthorCountSupport.computeNef(rawAuthors) : 1.0);
        }
        // H64 slice 4a: expose a linked canonical project's fields as proj_* so an opt-in indicator can prefer trusted
        // values (e.g. A10 budget: "proj_budget != null ? proj_budget/100000 : Buget/100000") or gate on funder. The
        // keys are ALWAYS bound (null when there is no/unresolved PROJECT_GRANT_ID reference) so a formula can null-check
        // them; formulas that don't reference proj_* are unaffected, so existing indicator totals do not change.
        injectLinkedProjectVariables(activity, variables);
        final String rawformula = indicator.getFormula();
        // H52 slice 11d.1: typed-strategy dispatch. GENERIC_ACTIVITY and
        // GENERIC_COUNT both short-circuit to a unit base score (1.0); only
        // the labels differ.
        if(indicator.isGenericActivity()) {
            result.setCoreRankingEquivalent("Generic Activity");
            result.setYear(activity.getYear());
            result.setScore(1.0);
            variables.put("S", 1.0);
        } else if(indicator.isGenericCount()) {
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
            // H52 slice 11c: typed multiplier propagates verbatim. The legacy
            // extra bag is gone; M is the only key it ever carried.
            result.setMultiplier(score.getMultiplier());
            variables.put("S", score.getScore());
            if (score.getMultiplier() != null) {
                variables.put("M", score.getMultiplier());
            }
        }
        if(result.getScore() > 0.0) {

            // H52 slice 11c: the debug breadcrumb that used to be written to
            // Score.details was never read by anything that mattered (no template,
            // no exporter). Dropping the field and its writer.
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

    /**
     * H64 slice 4a — bind {@code proj_*} formula variables from the canonical project linked via the activity's
     * {@code PROJECT_GRANT_ID} reference. Always binds the keys (null when no/unresolved reference) so opt-in formulas
     * can null-check; resolution failures degrade to null (never break scoring).
     */
    private void injectLinkedProjectVariables(ActivityInstance activity, Map<String, Object> variables) {
        variables.put("proj_budget", null);
        variables.put("proj_funder", null);
        variables.put("proj_code", null);
        variables.put("proj_title", null);
        variables.put("proj_director", null);
        variables.put("proj_startYear", null);
        variables.put("proj_endYear", null);

        Map<Activity.ReferenceField, String> refs = activity.getReferenceFields();
        String ref = refs == null ? null : refs.get(Activity.ReferenceField.PROJECT_GRANT_ID);
        if (ref == null || ref.isBlank()) {
            return;
        }
        ScholardexProjectListItemResponse project;
        try {
            project = scholardexProjectReadPort.findById(ref);
        } catch (RuntimeException ex) {
            log.warn("Linked project resolution failed for reference {} — proj_* left null", ref, ex);
            return;
        }
        if (project == null) {
            return;
        }
        if (project.budget() != null) {
            variables.put("proj_budget", project.budget().doubleValue());
        }
        variables.put("proj_funder", project.funder());
        variables.put("proj_code", project.code());
        variables.put("proj_title", project.title());
        variables.put("proj_director", project.director());
        if (project.startYear() != null) {
            variables.put("proj_startYear", project.startYear().doubleValue());
        }
        if (project.endYear() != null) {
            variables.put("proj_endYear", project.endYear().doubleValue());
        }
    }

}
