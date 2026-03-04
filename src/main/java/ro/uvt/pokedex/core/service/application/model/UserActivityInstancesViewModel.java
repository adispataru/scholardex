package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;

import java.util.List;

public record UserActivityInstancesViewModel(
        List<Activity> activities,
        Activity.ReferenceField[] referenceTypes,
        ActivityInstance newActivityInstance,
        List<ActivityInstance> activityInstances,
        List<String> activityLabels,
        List<Integer> activityData
) {
}
