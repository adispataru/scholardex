package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the script-set parts of a report alive across a save from the admin edit form.
 *
 * <p>The form manages title, indicators, export binding, and per criterion the name, indicators, thresholds
 * and the plafon. It has no inputs for {@code perspectives} nor for a criterion's {@code weights},
 * {@code maxPercentOfTotal} and {@code thresholdCapAdditions} — those are written by scripts. Saving the
 * bound form object as-is therefore wiped them (FV Fizică 2026 lost the weights of its total and its three
 * perspectives on any edit), so the save path first carries them over from the stored report.</p>
 *
 * <p><b>What the request states wins.</b> A field the request supplies (the binder accepts
 * {@code perspectives[0]…} and {@code criteria[0].weights[2]} although the page never sends them) is kept
 * as posted; only fields the request left out are carried over. Clearing one of these fields is therefore
 * a script's job, never the form's.</p>
 *
 * <p><b>Everything carried is keyed by position</b>: perspectives and {@code thresholdCriterionIndex} point
 * at criterion indices; weights, percent caps and {@code indicatorIndex} at report-level indicator indices.
 * The form can remove entries (closing the gap) and append new ones, but carries no identity for them, so
 * the carry-over is only done while every stored criterion and indicator is still at its own position —
 * additions at the end are fine. Otherwise the save is <b>refused</b> with the reason, rather than keeping
 * indices that now point at something else or dropping the fields silently.</p>
 */
public final class ReportFormCarryOver {

    private ReportFormCarryOver() {
    }

    /**
     * Carries the unmanaged fields from {@code stored} into {@code posted}.
     *
     * @return the reason the save must be refused, or empty when {@code posted} is ready to be saved
     */
    public static Optional<String> apply(AbstractReport stored, AbstractReport posted) {
        List<Criterion> storedCriteria = stored.getCriteria() == null ? List.of() : stored.getCriteria();
        List<Criterion> postedCriteria = posted.getCriteria() == null ? List.of() : posted.getCriteria();

        boolean carryPerspectives = posted.getPerspectives() == null && notEmpty(stored.getPerspectives());
        boolean carryCriterionFields = false;
        for (int i = 0; i < storedCriteria.size(); i++) {
            Criterion kept = storedCriteria.get(i);
            Criterion sent = i < postedCriteria.size() ? postedCriteria.get(i) : null;
            if (kept == null) {
                continue;
            }
            if ((notEmpty(kept.getWeights()) && (sent == null || sent.getWeights() == null))
                    || (notEmpty(kept.getMaxPercentOfTotal()) && (sent == null || sent.getMaxPercentOfTotal() == null))
                    || (notEmpty(kept.getThresholdCapAdditions())
                    && (sent == null || sent.getThresholdCapAdditions() == null))) {
                carryCriterionFields = true;
            }
        }
        if (!carryPerspectives && !carryCriterionFields) {
            return Optional.empty(); // nothing of the stored report depends on positions
        }

        Optional<String> moved = firstCriterionOutOfPlace(storedCriteria, postedCriteria);
        if (moved.isEmpty() && carryCriterionFields) {
            moved = firstIndicatorOutOfPlace(stored.getIndicators(), posted.getIndicators());
        }
        if (moved.isPresent()) {
            return Optional.of(refusal(moved.get()));
        }

        if (carryPerspectives) {
            posted.setPerspectives(stored.getPerspectives());
        }
        for (int i = 0; i < storedCriteria.size(); i++) {
            Criterion kept = storedCriteria.get(i);
            Criterion sent = postedCriteria.get(i);
            if (kept == null || sent == null) {
                continue;
            }
            if (sent.getWeights() == null && notEmpty(kept.getWeights())) {
                sent.setWeights(kept.getWeights());
            }
            if (sent.getMaxPercentOfTotal() == null && notEmpty(kept.getMaxPercentOfTotal())) {
                sent.setMaxPercentOfTotal(kept.getMaxPercentOfTotal());
            }
            if (sent.getThresholdCapAdditions() == null && notEmpty(kept.getThresholdCapAdditions())) {
                sent.setThresholdCapAdditions(kept.getThresholdCapAdditions());
            }
        }
        return Optional.empty();
    }

    /**
     * A stored criterion is still in place when the posted one at its position has the same name or sums the
     * same indicators — so a rename is fine, and so is changing which indicators it sums, but not both at
     * once: with neither in common nothing says it is the same criterion, which is exactly what a removal
     * looks like after the page has moved the following criteria up.
     */
    private static Optional<String> firstCriterionOutOfPlace(List<Criterion> stored, List<Criterion> posted) {
        for (int i = 0; i < stored.size(); i++) {
            Criterion kept = stored.get(i);
            if (kept == null) {
                continue;
            }
            String label = "criterion " + (i + 1) + " (\"" + Objects.toString(kept.getName(), "") + "\")";
            if (i >= posted.size() || posted.get(i) == null) {
                return Optional.of(label + " was removed");
            }
            Criterion sent = posted.get(i);
            boolean sameName = normalized(kept.getName()).equals(normalized(sent.getName()));
            boolean sameIndicators = indices(kept).equals(indices(sent));
            if (!sameName && !sameIndicators) {
                return Optional.of(label + " was removed or replaced: the criterion now at its position has "
                        + "neither its name nor its indicators");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstIndicatorOutOfPlace(List<Indicator> stored, List<Indicator> posted) {
        List<Indicator> kept = stored == null ? List.of() : stored;
        List<Indicator> sent = posted == null ? List.of() : posted;
        for (int i = 0; i < kept.size(); i++) {
            Indicator indicator = kept.get(i);
            String id = indicator == null ? null : indicator.getId();
            String sentId = i < sent.size() && sent.get(i) != null ? sent.get(i).getId() : null;
            if (!Objects.equals(id, sentId)) {
                String name = indicator == null || indicator.getName() == null ? String.valueOf(id) : indicator.getName();
                return Optional.of("indicator " + (i + 1) + " (\"" + name + "\") was removed, replaced or moved");
            }
        }
        return Optional.empty();
    }

    private static String refusal(String problem) {
        return "Nothing was saved. This report carries perspectives, weights or caps that are set by script and "
                + "point at criteria and indicators by position, so they can only be kept while the existing "
                + "criteria and indicators stay where they are (adding new ones at the end is fine). Problem: "
                + problem + ". Undo that change and save again, or change the report by script.";
    }

    private static List<Integer> indices(Criterion criterion) {
        return criterion.getIndicatorIndices() == null ? List.of() : new ArrayList<>(criterion.getIndicatorIndices());
    }

    private static String normalized(String name) {
        return name == null ? "" : name.trim();
    }

    private static boolean notEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean notEmpty(Map<?, ?> values) {
        return values != null && !values.isEmpty();
    }
}
