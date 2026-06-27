package ro.uvt.pokedex.core.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * H64 slice 3b — enable the canonical-project picker on the project Activity definitions by ensuring the
 * {@link Activity.ReferenceField#PROJECT_GRANT_ID} reference field is present on a configured set of activities
 * (physics {@code Grant Cercetare}, CS {@code Granturi}, FEAA's project activity, …).
 *
 * <p><b>Additive + idempotent.</b> Only ADDS the reference field when absent; never removes fields, never touches
 * activities not named. Driven by {@code core.projects.reference-activity-names} (comma-separated); blank = no-op, so
 * this is inert until a deployment opts in. Runs in every profile (the feature is enabled the same way in prod).
 */
@Component
@RequiredArgsConstructor
@Order(20)
public class ProjectReferenceFieldSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProjectReferenceFieldSeedRunner.class);

    @Value("${core.projects.reference-activity-names:}")
    private String referenceActivityNames;

    private final ActivityRepository activityRepository;

    @Override
    public void run(String... args) {
        if (referenceActivityNames == null || referenceActivityNames.isBlank()) {
            return;
        }
        List<String> names = Arrays.stream(referenceActivityNames.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        int enabled = 0;
        int alreadyPresent = 0;
        for (String name : names) {
            List<Activity> activities = activityRepository.findByName(name);
            if (activities.isEmpty()) {
                log.warn("Project reference-field seed: no activity named '{}' — skipping", name);
                continue;
            }
            for (Activity activity : activities) {
                List<Activity.ReferenceField> refs = activity.getReferenceFields() == null
                        ? new ArrayList<>() : new ArrayList<>(activity.getReferenceFields());
                if (refs.contains(Activity.ReferenceField.PROJECT_GRANT_ID)) {
                    alreadyPresent++;
                    continue;
                }
                refs.add(Activity.ReferenceField.PROJECT_GRANT_ID);
                activity.setReferenceFields(refs);
                activityRepository.save(activity);
                enabled++;
                log.info("Project reference-field seed: enabled PROJECT_GRANT_ID on activity '{}' ({})",
                        name, activity.getId());
            }
        }
        log.info("Project reference-field seed complete: enabled={} alreadyPresent={} configured={}",
                enabled, alreadyPresent, names);
    }
}
