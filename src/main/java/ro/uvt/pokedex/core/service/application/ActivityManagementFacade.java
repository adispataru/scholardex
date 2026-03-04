package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.ActivityIndicator;
import ro.uvt.pokedex.core.repository.ActivityIndicatorRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActivityManagementFacade {

    private final ActivityRepository activityRepository;
    private final ActivityIndicatorRepository activityIndicatorRepository;

    public List<Activity> listActivities() {
        return activityRepository.findAll();
    }

    public Activity saveActivity(Activity activity) {
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

    public List<ActivityIndicator> listActivityIndicators() {
        return activityIndicatorRepository.findAll();
    }

    public ActivityIndicator saveActivityIndicator(ActivityIndicator activityIndicator) {
        return activityIndicatorRepository.save(activityIndicator);
    }

    public Optional<ActivityIndicator> findActivityIndicator(String id) {
        return activityIndicatorRepository.findById(id);
    }

    public void deleteActivityIndicator(String id) {
        activityIndicatorRepository.deleteById(id);
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
