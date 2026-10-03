package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActivityManagementFacade {

    private final ActivityRepository activityRepository;

    public List<Activity> listActivities() {
        return activityRepository.findAll();
    }

    /**
     * H145: the admin form does not carry the type's scoring flags (one record per researcher, a declared publication);
     * an edit keeps the stored ones instead of wiping them.
     */
    public Activity saveActivity(Activity activity) {
        if (activity.getId() != null && !activity.getId().isBlank()) {
            activityRepository.findById(activity.getId()).ifPresent(stored -> {
                if (activity.getSinglePerResearcher() == null) {
                    activity.setSinglePerResearcher(stored.getSinglePerResearcher());
                }
                if (activity.getPublicationRecord() == null) {
                    activity.setPublicationRecord(stored.getPublicationRecord());
                }
            });
        }
        return activityRepository.save(activity);
    }

    public Optional<Activity> findActivity(String id) {
        return activityRepository.findById(id);
    }

    public Optional<Activity> duplicateActivity(String id) {
        return activityRepository.findById(id).map(activity -> {
            activity.setId(null);
            activity.setName(activity.getName() + " (copy)");
            return activityRepository.save(activity);
        });
    }

    public void deleteActivity(String id) {
        activityRepository.deleteById(id);
    }

    public Map<String, String> buildActivityDescriptions(List<Activity> activities) {
        return activities.stream()
                .collect(Collectors.toMap(
                        Activity::getId,
                        act -> {
                            String body = act.getFields().stream()
                                    .map(f -> f.getName()
                                            + (f.isNumber()
                                            ? " (numeric)"
                                            : f.getAllowedValues() != null ? " [" + String.join(", ", f.getAllowedValues()) + "]" : ""))
                                    .collect(Collectors.joining("; "));
                            return "Fields: " + body;
                        }
                ));
    }
}
