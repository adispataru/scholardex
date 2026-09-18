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
    private final ro.uvt.pokedex.core.service.issn.IssnVerificationService issnVerificationService;
    private final ro.uvt.pokedex.core.service.reporting.ReportingLookupPort reportingLookupPort;

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
        validateJournalIssns(activityInstance);
        return activityInstanceRepository.save(activityInstance);
    }

    /**
     * A journal named by ISSN must be a real journal: the check digit has to be right (typos are rejected), and for
     * a journal we do not hold — category D, outside WoS and Scopus — the international register is asked. A clear
     * "no such ISSN" rejects the entry; "could not ask" is accepted as unverified and retried nightly.
     *
     * @throws ro.uvt.pokedex.core.service.issn.InvalidIssnException with a message key for the form
     */
    void validateJournalIssns(ActivityInstance instance) {
        java.util.Map<Activity.ReferenceField, String> refs = instance.getReferenceFields();
        if (refs == null) {
            return;
        }
        java.util.List<String> issns = new java.util.ArrayList<>();
        for (Activity.ReferenceField key : java.util.List.of(
                Activity.ReferenceField.FORUM_ISSN, Activity.ReferenceField.FORUM_EISSN)) {
            String raw = refs.get(key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (!ro.uvt.pokedex.core.service.issn.IssnSupport.isValid(raw)) {
                throw new ro.uvt.pokedex.core.service.issn.InvalidIssnException(
                        "workspace.activities.issn.invalid", raw.trim());
            }
            String normalized = ro.uvt.pokedex.core.service.issn.IssnSupport.normalize(raw);
            refs.put(key, normalized);
            issns.add(normalized);
        }
        if (issns.isEmpty() || isKnownJournal(issns)) {
            return;
        }
        boolean allDenied = true;
        for (String issn : issns) {
            if (issnVerificationService.verify(issn).getStatus()
                    != ro.uvt.pokedex.core.model.issn.IssnVerification.Status.NOT_FOUND) {
                allDenied = false;
            }
        }
        if (allDenied) {
            throw new ro.uvt.pokedex.core.service.issn.InvalidIssnException(
                    "workspace.activities.issn.notFound", String.join(", ", issns));
        }
    }

    /** In the WoS lists or in our forum corpus — no need to ask the register. A lookup failure means "unknown". */
    private boolean isKnownJournal(java.util.List<String> issns) {
        try {
            for (String issn : issns) {
                if (!reportingLookupPort.getRankingsByIssn(issn).isEmpty()) {
                    return true;
                }
            }
            return !reportingLookupPort.findForumIdsByIssn(issns.get(0), issns.size() > 1 ? issns.get(1) : null).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void updateActivityInstance(ActivityInstance activityInstance) {
        Optional<ActivityInstance> byId = activityInstanceRepository.findById(activityInstance.getId());
        if (byId.isPresent()) {
            ActivityInstance existingInstance = byId.get();
            validateJournalIssns(activityInstance);
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
