package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H142 slice 2 — the review of imported activities, many at once: set a field on every selected record whose type has
 * it ("all these are choir concerts I conducted": role and ensemble size), mark records as checked, or delete them.
 * Only the person's own records are touched; a value that is not an option of the field is refused, not stored.
 */
@Service
@RequiredArgsConstructor
public class ActivityReviewService {

    /**
     * How many records changed, how many were left as they were, and the values refused ("Rol: «Corist»": not one of
     * the field's options, or not a number); the page words the sentence around them.
     */
    public record BulkResult(int changed, int unchanged, List<String> problems) {
    }

    private final ActivityInstanceRepository activityInstanceRepository;

    public BulkResult setFields(String researcherEmail, List<String> ids, Map<String, String> values) {
        List<String> problems = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return new BulkResult(0, 0, List.of());
        }
        int changed = 0, unchanged = 0;
        List<ActivityInstance> toSave = new ArrayList<>();
        for (ActivityInstance instance : owned(researcherEmail, ids)) {
            Activity type = instance.getActivity();
            Map<String, String> fields = instance.getFields() == null ? new HashMap<>() : new HashMap<>(instance.getFields());
            boolean touched = false;
            for (Map.Entry<String, String> value : values.entrySet()) {
                Activity.Field field = field(type, value.getKey());
                if (field == null) {
                    continue; // the field belongs to other types of the selection
                }
                String v = value.getValue() == null ? "" : value.getValue().trim();
                if (v.isEmpty()) {
                    touched |= fields.remove(field.getName()) != null;
                    continue;
                }
                if (field.isNumber() && !isNumber(v)) {
                    addOnce(problems, field.getName() + ": «" + v + "»");
                    continue;
                }
                if (field.getAllowedValues() != null && !field.getAllowedValues().isEmpty() && !field.getAllowedValues().contains(v)) {
                    addOnce(problems, field.getName() + ": «" + v + "»");
                    continue;
                }
                if (!v.equals(fields.get(field.getName()))) {
                    fields.put(field.getName(), v);
                    touched = true;
                }
            }
            if (touched) {
                instance.setFields(fields);
                toSave.add(instance);
                changed++;
            } else {
                unchanged++;
            }
        }
        activityInstanceRepository.saveAll(toSave);
        return new BulkResult(changed, unchanged, problems);
    }

    public BulkResult markReviewed(String researcherEmail, List<String> ids) {
        List<ActivityInstance> toSave = new ArrayList<>();
        int unchanged = 0;
        for (ActivityInstance instance : owned(researcherEmail, ids)) {
            if (Boolean.TRUE.equals(instance.getNeedsReview())) {
                instance.setNeedsReview(Boolean.FALSE);
                toSave.add(instance);
            } else {
                unchanged++;
            }
        }
        activityInstanceRepository.saveAll(toSave);
        return new BulkResult(toSave.size(), unchanged, List.of());
    }

    public BulkResult delete(String researcherEmail, List<String> ids) {
        List<ActivityInstance> mine = owned(researcherEmail, ids);
        activityInstanceRepository.deleteAll(mine);
        return new BulkResult(mine.size(), 0, List.of());
    }

    private List<ActivityInstance> owned(String researcherEmail, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>(ids);
        List<ActivityInstance> out = new ArrayList<>();
        for (ActivityInstance instance : activityInstanceRepository.findAllById(wanted)) {
            if (instance != null && researcherEmail.equals(instance.getResearcherId())) {
                out.add(instance);
            }
        }
        return out;
    }

    private static Activity.Field field(Activity type, String name) {
        if (type == null || type.getFields() == null) {
            return null;
        }
        return type.getFields().stream().filter(f -> f.getName().equals(name)).findFirst().orElse(null);
    }

    private static boolean isNumber(String v) {
        try {
            Double.parseDouble(v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void addOnce(List<String> problems, String problem) {
        if (!problems.contains(problem)) {
            problems.add(problem);
        }
    }
}
