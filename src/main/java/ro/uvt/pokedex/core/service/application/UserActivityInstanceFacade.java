package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.service.application.model.UserActivityInstancesViewModel;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserActivityInstanceFacade {

    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityRepository activityRepository;

    public UserActivityInstancesViewModel buildActivityInstancesView(String researcherId) {
        List<Activity> activities = activityRepository.findAll();
        ActivityInstance newInstance = new ActivityInstance();
        List<ActivityInstance> activityInstances = Collections.emptyList();
        List<String> activityLabels = Collections.emptyList();
        List<Integer> activityData = Collections.emptyList();

        if (researcherId != null) {
            newInstance.setResearcherId(researcherId);
            activityInstances = activityInstanceRepository.findAllByResearcherId(researcherId);
            Iterator<ActivityInstance> iterator = activityInstances.iterator();
            while (iterator.hasNext()) {
                ActivityInstance instance = iterator.next();
                if (instance.getActivity() == null) {
                    activityInstanceRepository.deleteById(instance.getId());
                    iterator.remove();
                }
            }

            Map<String, List<ActivityInstance>> byActivityName =
                    activityInstances.stream().collect(Collectors.groupingBy(x -> x.getActivity().getName()));
            activityLabels = byActivityName.keySet().stream().toList();
            activityData = activityLabels.stream().map(label -> byActivityName.get(label).size()).toList();
        }

        return new UserActivityInstancesViewModel(
                activities,
                Activity.ReferenceField.values(),
                newInstance,
                activityInstances,
                activityLabels,
                activityData
        );
    }

    public ActivityInstance saveActivityInstance(ActivityInstance activityInstance) {
        return activityInstanceRepository.save(activityInstance);
    }

    public void updateActivityInstance(ActivityInstance activityInstance) {
        Optional<ActivityInstance> byId = activityInstanceRepository.findById(activityInstance.getId());
        if (byId.isPresent()) {
            ActivityInstance existingInstance = byId.get();
            existingInstance.setFields(activityInstance.getFields());
            existingInstance.setReferenceFields(activityInstance.getReferenceFields());
            activityInstanceRepository.save(existingInstance);
        }
    }

    public Optional<ActivityInstance> findActivityInstance(String id) {
        return activityInstanceRepository.findById(id);
    }

    public void deleteActivityInstance(String id) {
        activityInstanceRepository.deleteById(id);
    }

    public Optional<Activity> findActivity(String id) {
        return activityRepository.findById(id);
    }
}
