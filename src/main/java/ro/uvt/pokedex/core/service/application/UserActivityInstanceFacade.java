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

    /**
     * Stores a new record. H145: only the values its type accepts ({@link ActivityRecordValidator}) — anything refused
     * stops the save; a head's decision on a request is never taken from the caller (a request is made through the
     * fields, decided only on the head's page).
     *
     * @throws ActivityValidationException when a value is refused
     */
    public ActivityInstance saveActivityInstance(ActivityInstance activityInstance) {
        refuseSecondRecord(activityInstance);
        applyChecked(activityInstance, activityInstance.getActivity(), activityInstance.getFields(),
                activityInstance.getReferenceFields());
        activityInstance.setPublisherClaim(null);
        validateJournalIssns(activityInstance);
        PublisherClaimSupport.reconcile(activityInstance, activityInstance.getResearcherId()); // H143
        return activityInstanceRepository.save(activityInstance);
    }

    /** H145: a type a researcher holds once (a Google Scholar profile) takes no second record. */
    private void refuseSecondRecord(ActivityInstance instance) {
        Activity type = instance.getActivity();
        if (type == null || !type.isSingle() || instance.getResearcherId() == null) {
            return;
        }
        boolean held = activityInstanceRepository.findAllByResearcherId(instance.getResearcherId()).stream()
                .anyMatch(r -> r.getActivity() != null && type.getId() != null && type.getId().equals(r.getActivity().getId()));
        if (held) {
            throw new ActivitySingleRecordException(type.getName());
        }
    }

    /** Puts the accepted values on the record, or refuses them all. */
    private static void applyChecked(ActivityInstance target, Activity type, Map<String, String> fields,
                                     Map<Activity.ReferenceField, String> references) {
        ActivityRecordValidator.Result checked = ActivityRecordValidator.validate(type, fields, references);
        if (!checked.valid()) {
            throw new ActivityValidationException(checked.problems());
        }
        target.setFields(new java.util.HashMap<>(checked.fields()));
        Map<Activity.ReferenceField, String> refs = new java.util.EnumMap<>(Activity.ReferenceField.class);
        refs.putAll(checked.references());
        target.setReferenceFields(refs);
    }

    /** H145: a record is changed or removed by the researcher it belongs to, nobody else. */
    private static boolean owns(ActivityInstance instance, String actorEmail) {
        return actorEmail != null && instance.getResearcherId() != null
                && actorEmail.trim().equalsIgnoreCase(instance.getResearcherId().trim());
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

    /**
     * Replaces a record's fields and references. H145: only the researcher the record belongs to; only values its type
     * accepts. False when there is no such record of theirs (nothing changed).
     *
     * @throws ActivityValidationException when a value is refused
     */
    public boolean updateActivityInstance(ActivityInstance activityInstance, String actorEmail) {
        Optional<ActivityInstance> byId = activityInstance.getId() == null
                ? Optional.empty() : activityInstanceRepository.findById(activityInstance.getId());
        if (byId.isEmpty() || !owns(byId.get(), actorEmail)) {
            return false;
        }
        ActivityInstance existingInstance = byId.get();
        applyChecked(existingInstance, existingInstance.getActivity(), activityInstance.getFields(),
                activityInstance.getReferenceFields());
        validateJournalIssns(existingInstance);
        if (Boolean.TRUE.equals(existingInstance.getNeedsReview())) {
            existingInstance.setNeedsReview(Boolean.FALSE); // H142 — saving an imported record is checking it
        }
        PublisherClaimSupport.reconcile(existingInstance, existingInstance.getResearcherId()); // H143
        activityInstanceRepository.save(existingInstance);
        return true;
    }

    public Optional<ActivityInstance> findActivityInstance(String id) {
        return activityInstanceRepository.findById(id);
    }

    /** Removes a record of the researcher's own (H145); false when there is no such record of theirs. */
    public boolean deleteActivityInstance(String id, String actorEmail) {
        Optional<ActivityInstance> byId = id == null ? Optional.empty() : activityInstanceRepository.findById(id);
        if (byId.isEmpty() || !owns(byId.get(), actorEmail)) {
            return false;
        }
        activityInstanceRepository.deleteById(id);
        return true;
    }

    public Optional<Activity> findActivity(String id) {
        return activityRepository.findById(id);
    }
}
