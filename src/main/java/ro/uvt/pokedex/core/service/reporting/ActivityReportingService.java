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
        return calculateActivityScoresDetailed(activities, indicator).scores();
    }

    /**
     * H99 item 4: scores plus the zero-scored instances the totals exclude. A declared activity whose
     * formula yields 0 (GoveIN: a bracket-1 grant under D_v's {@code Interval_buget >= 2} gate) used to
     * vanish from every drilldown — the researcher saw their entry "missing" and read it as data loss.
     * The detail paths surface {@code excludedItems} as zero rows with a reason, exactly like the
     * publications side; the {@code scores} map (and every total/export consumer of
     * {@link #calculateActivityScores}) is unchanged.
     */
    public ScoredActivityResult calculateActivityScoresDetailed(List<ActivityInstance> activities, Indicator indicator) {

        Map<String, Score> result = new HashMap<>();
        Map<String, Score> excluded = new HashMap<>();
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
            } else {
                excluded.put(act.getId(), score);
            }
        }

        Score total = new Score();
        total.setAuthorScore(totalScore);

        result.put("total", total);
        return new ScoredActivityResult(result, excluded);
    }

    /** The activity analogue of the publications {@code ScoredProductionResult}: totals-contributing scores + zero rows. */
    public record ScoredActivityResult(Map<String, Score> scores, Map<String, Score> excludedItems) {}

    private Score calculateActivityScore(ActivityInstance activity, Indicator indicator) {
        Score result = new Score();

        Map<String, Object> variables = new HashMap<>();
        if(activity.getActivity().getFields() != null) {
            for (Activity.Field key : activity.getActivity().getFields()) {
                String fieldName = key.getName();
                String value = activity.getFields().get(fieldName);
                if (key.isNumber()) {
                    // Optional/blank number fields bind null instead of crashing: Double.parseDouble
                    // used to NPE on an absent value and throw on "" — one budget-less grant entry
                    // took down the whole indicator computation for that researcher's report.
                    variables.put(fieldName, parseNumberOrNull(fieldName, value, activity));
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
        // The platform's canonical grant-budget bracket (GrantBudgetBracket, EUR): derived here, once,
        // with trusted-first precedence — CORDIS proj_budget, else the declared exact budget, else the
        // researcher's self-declared interval select, else 0 (unknown). Budget-aware formulas consume
        // Interval_buget (1–5) instead of re-encoding the threshold bounds per indicator.
        injectBudgetBracketVariable(activity, variables);
        // H99/physics A10: continuous EUR budget with the same trusted-first spirit — see the method doc.
        boolean budgetEurDerivedFromInterval = injectBudgetEurVariable(activity, variables);
        // Editions multiplier for recurring roles (20 years on the SYNASC committee = ONE entry):
        // N_editii = An_sfarsit - An_inceput + 1 from the optional year-pair fields, else 1. Rank-
        // sensitive formulas (D_vi S/2) value the whole range at the entry-year rank — when the
        // conference's category differed across the period the researcher splits entries per category
        // period (user decision; true per-year expansion deferred, the year pair enables it later).
        injectEditionsVariable(variables);
        // H142: N_ani (int >= 1) — the years of a function held "per year" (Music RIA 1.1, 3.2: 10 points a year):
        // An_inceput to An_sfarsit, or to the reference year while the function is still held (no end year).
        injectYearsVariable(variables);
        // H136: An_activitate (int) — the year of the declared activity (its date), so a formula can apply a
        // standard's window ("în perioada 2015–2026") without a second, typed year field. Activities are not
        // filtered by the indicator's year range the way publications are. 0 when the entry has no date.
        variables.put("An_activitate", activity.getYear());
        // H137: An_doctorat (int or null) — the subject's first PhD year, for "după obținerea titlului de doctor"
        // windows on declared items (null when the profile has none: the formula keeps the plain window).
        variables.put("An_doctorat", ScoringSubjectContext.phdAwardYear());
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
        // H136: Editura_7c (boolean) — the typed publisher is on Anexa 7c of the PN-IV PD/TE 2026 packages, the
        // only publishers whose books and chapters count for the social/economic eligibility. Bound whenever the
        // activity has an Editura field; false when the list is not loaded or the name is not on it. After the
        // strategy branch, which replaces scoringInfo, so the miss note survives.
        injectAnexa7cPublisherVariable(variables, result, rawformula);
        // H142: one declared artistic performance feeds the Music standard and CNFIS Anexa 5.1. For an activity
        // type that references an event: Nivel_eveniment (the registry's rank or null), Vizibilitate_varf (CS 1.1
        // vs 1.2), Rezultat_eveniment (PARTICIPARE / NOMINALIZARE / PREMIU) and Rol_eligibil (the roles the
        // standard counts) — see ArtisticPerformanceSupport.
        injectArtisticPerformanceVariables(activity, variables, result, rawformula);
        // H143: Categorie_editura — the category of a declared book's publisher under the indicator's standard, from
        // the lists that standard names, or from a request a head approved (PublisherRules); null when nothing counts.
        injectPublisherCategoryVariable(activity, indicator, variables, result, rawformula);
        if (budgetEurDerivedFromInterval) {
            // Surfaced on the drilldown row: the amount was inferred from the declared interval's lower
            // bound, not taken from the (missing or interval-contradicting) numeric Buget.
            result.getScoringInfo().put("budgetEurDerived", "DECLARED_INTERVAL_LOWER_BOUND");
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

    /** Null-safe numeric field binding; a malformed value logs and binds null rather than failing the row. */
    private Double parseNumberOrNull(String fieldName, String value, ActivityInstance activity) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Activity {} field '{}' has non-numeric value '{}' — binding null", activity.getId(), fieldName, value);
            return null;
        }
    }

    /**
     * Binds {@code Interval_buget} (int 1–5 per {@link GrantBudgetBracket}, 0 = unknown), derived
     * trusted-first: CORDIS {@code proj_budget} → the researcher's declared interval select
     * (label-matched against the bracket scale) → the declared exact {@code Buget} → 0. Always
     * bound, so formulas can reference it without null checks; overwrites the raw select label the
     * generic field loop bound under the same name.
     *
     * <p>H99 item 5 (Florin Fortis): the declared interval now OUTRANKS the raw {@code Buget}.
     * {@code Buget} carries no currency — real prod entries hold lei amounts ("565600" for a
     * 100–199k EUR project → bracket 5 instead of 3) and locale-formatted strings ("156.491"
     * parsing as 156 EUR → bracket 1 instead of 3) — while the interval options are explicitly
     * EUR-labeled, so when a researcher picked one it is the reliable statement of the bracket.
     * CORDIS stays on top: it is authoritative EUR from the funder.
     */
    private void injectBudgetBracketVariable(ActivityInstance activity, Map<String, Object> variables) {
        Object projBudget = variables.get("proj_budget");
        String declared = activity.getFields() == null ? null : activity.getFields().get("Interval_buget");
        int declaredBracket = GrantBudgetBracket.indexFromLabel(declared);
        Object exactBudget = variables.get("Buget");
        int bracket;
        if (projBudget instanceof Number n) {
            bracket = GrantBudgetBracket.fromAmount(n.doubleValue()).index;
        } else if (declaredBracket > 0) {
            bracket = declaredBracket;
        } else if (exactBudget instanceof Number n) {
            bracket = GrantBudgetBracket.fromAmount(n.doubleValue()).index;
        } else {
            bracket = 0;
        }
        variables.put("Interval_buget", bracket);
    }

    /**
     * Binds {@code Buget_eur} — a continuous EUR amount for formulas that divide by a euro threshold
     * (physics A10: Vᵢ/50.000 EUR), where the bracketed {@code Interval_buget} cannot serve. Same
     * trusted-first spirit, adapted to a continuous value: CORDIS {@code proj_budget} (authoritative
     * EUR) → the raw {@code Buget} when it agrees with the researcher's declared interval (the honest
     * "this really is EUR" signal) → the declared interval's LOWER bound when {@code Buget} is missing
     * or contradicts it (conservative: real prod entries hold lei amounts and locale-formatted strings,
     * and an eligibility instrument must under-promise on contradictory data) → the raw {@code Buget}
     * alone when nothing else exists (irreducible without currency information) → null. Always bound,
     * so formulas can null-check without exploding.
     *
     * @return true when the interval-lower-bound fallback fired (surfaced as a drilldown note)
     */
    private boolean injectBudgetEurVariable(ActivityInstance activity, Map<String, Object> variables) {
        Object projBudget = variables.get("proj_budget");
        String declared = activity.getFields() == null ? null : activity.getFields().get("Interval_buget");
        GrantBudgetBracket declaredBracket = GrantBudgetBracket.byIndex(GrantBudgetBracket.indexFromLabel(declared));
        Object exactBudget = variables.get("Buget");
        Object eur;
        boolean derivedFromInterval = false;
        if (projBudget instanceof Number n) {
            eur = n.doubleValue();
        } else if (declaredBracket != null) {
            if (exactBudget instanceof Number n && GrantBudgetBracket.fromAmount(n.doubleValue()) == declaredBracket) {
                eur = n.doubleValue();
            } else {
                eur = (double) declaredBracket.lowerBoundEur;
                derivedFromInterval = true;
            }
        } else if (exactBudget instanceof Number n) {
            eur = n.doubleValue();
        } else {
            eur = null;
        }
        variables.put("Buget_eur", eur);
        return derivedFromInterval;
    }

    /**
     * See the call site: {@code Editura_7c} from {@link UefiscdiPublisherSupport}. The variable is bound for any
     * activity with an Editura field (harmless: formulas that do not name it never see it); the miss is noted on
     * the row ONLY for formulas that gate on it, so the book activities of the CNATDCU standards (their own
     * publisher lists) carry no stray note.
     */
    private void injectAnexa7cPublisherVariable(Map<String, Object> variables, Score result, String formula) {
        if (!variables.containsKey("Editura")) {
            return;
        }
        Object editura = variables.get("Editura");
        boolean onList = editura instanceof String name && UefiscdiPublisherSupport.isOnAnexa7c(name);
        variables.put("Editura_7c", onList);
        if (!onList && formula != null && formula.contains("Editura_7c")) {
            result.getScoringInfo().put("anexa7c", "NOT_ON_LIST");
        }
    }

    /**
     * See the call site. Bound for an activity whose type has a typed {@code Editura} and whose indicator follows a
     * standard with publisher rules (its 2026 flag); the row notes the category and where it comes from, and a formula
     * that gates on the category explains its zero.
     */
    private void injectPublisherCategoryVariable(ActivityInstance activity, Indicator indicator,
                                                 Map<String, Object> variables, Score result, String formula) {
        java.util.Optional<PublisherRules> rules = PublisherRules.of(indicator);
        if (rules.isEmpty() || !variables.containsKey(PublisherRules.FIELD_PUBLISHER)) {
            return;
        }
        Object typed = variables.get(PublisherRules.FIELD_PUBLISHER);
        PublisherCategorySupport.Outcome outcome = PublisherCategorySupport.outcome(rules.get(),
                typed instanceof String name ? name : null, activity.getDate(), activity.getPublisherClaim(),
                activity.getFields());
        variables.put(PublisherRules.VARIABLE, outcome.category());
        result.getScoringInfo().put("publisherCategory", outcome.category() == null ? "NONE" : outcome.category());
        result.getScoringInfo().put("publisherBasis", outcome.basis());
        if (outcome.category() == null && formula != null && formula.contains(PublisherRules.VARIABLE)) {
            result.getScoringInfo().put("zeroReason", "PUBLISHER_NOT_CLASSIFIED");
        }
    }

    /**
     * H142 — binds {@code N_ani} (int >= 1) when the activity has a numeric {@code An_inceput}: the years from it to
     * {@code An_sfarsit}, or to the reference year of the run (else the current year) when no end year is given —
     * a function still held. 1 otherwise, and for an inverted pair.
     */
    private void injectYearsVariable(Map<String, Object> variables) {
        if (!(variables.get("An_inceput") instanceof Number start)) {
            variables.put("N_ani", 1);
            return;
        }
        Integer reference = ScoringReferenceYearContext.current();
        double end = variables.get("An_sfarsit") instanceof Number e ? e.doubleValue()
                : (reference != null ? reference : java.time.Year.now().getValue());
        int span = (int) (end - start.doubleValue()) + 1;
        variables.put("N_ani", Math.max(span, 1));
    }

    /**
     * H142 — see the call site. Bound only for activity types that declare an EVENT_NAME reference; the visibility
     * basis is noted on the row only for formulas that read {@code Vizibilitate_varf}, so the CNFIS-only uses of
     * the type carry no stray note.
     */
    private void injectArtisticPerformanceVariables(ActivityInstance activity, Map<String, Object> variables,
                                                    Score result, String formula) {
        Activity type = activity.getActivity();
        if (type == null || type.getReferenceFields() == null
                || !type.getReferenceFields().contains(Activity.ReferenceField.EVENT_NAME)) {
            return;
        }
        Map<String, String> fields = activity.getFields() == null ? Map.of() : activity.getFields();
        String event = activity.getReferenceFields() == null ? null
                : activity.getReferenceFields().get(Activity.ReferenceField.EVENT_NAME);
        var rank = ArtisticEventRankSupport.rankOf(event);
        var visibility = ArtisticPerformanceSupport.visibility(rank, event != null && !event.isBlank());
        variables.put("Nivel_eveniment", rank.map(Enum::name).orElse(null));
        variables.put("Vizibilitate_varf", visibility.top());
        variables.put("Rezultat_eveniment", ArtisticPerformanceSupport.result(fields));
        variables.put("Rol_eligibil", ArtisticPerformanceSupport.roleCounts(fields));
        if (formula != null && formula.contains("Vizibilitate_varf")) {
            result.getScoringInfo().put("eventLevel", rank.map(Enum::name).orElse(
                    ArtisticEventRankSupport.statusOf(event).map(Enum::name).orElse("NO_EVENT")));
            result.getScoringInfo().put("visibilityBasis", visibility.basis().name());
        }
    }

    /**
     * Binds {@code N_editii} (int ≥ 1): the editions count from the optional {@code An_inceput}/
     * {@code An_sfarsit} numeric fields, else 1. A partial or inverted pair degrades to 1 rather
     * than failing the row.
     */
    private void injectEditionsVariable(Map<String, Object> variables) {
        Object start = variables.get("An_inceput");
        Object end = variables.get("An_sfarsit");
        int editions = 1;
        if (start instanceof Number s && end instanceof Number e) {
            int span = (int) (e.doubleValue() - s.doubleValue()) + 1;
            if (span >= 1) {
                editions = span;
            }
        }
        variables.put("N_editii", editions);
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
