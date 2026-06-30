package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.service.brainmap.ProjectCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * H78 — the researcher-facing project workspace service.
 *
 * <p>Slice 1: surface the canonical projects that may belong to a researcher by matching the researcher's name to a
 * project's <b>director</b> ({@link #myProjects}). Director-only: {@code ScholardexProjectFact} carries no participant
 * names (only affiliation ids), so participants aren't auto-surfaced — they self-serve via the project search when
 * adding a {@code Grant Cercetare} (slice 4). The match uses the same word-order/diacritic-insensitive name key the
 * canonicalizer uses for coordinators ({@link ProjectCanonicalizationService#signature}), compared by exact equality
 * against the projected {@code director_signature} column. Read-only surfacing — never a silent link.
 *
 * <p>Slice 2: turn a canonical project into a pre-filled {@code Grant Cercetare} activity instance ({@link
 * #importProject}). Idempotent on the {@code PROJECT_GRANT_ID} reference — a second import of the same project returns
 * the existing instance instead of duplicating. Field pre-fill is config-aware: only fields the activity actually
 * declares are written.
 */
@Service
@RequiredArgsConstructor
public class ResearcherProjectService {

    private final ScholardexProjectReadPort projectReadPort;
    private final ActivityRepository activityRepository;
    private final ActivityInstanceRepository activityInstanceRepository;

    /** The activity an imported project becomes (must declare the {@code PROJECT_GRANT_ID} reference). */
    @Value("${core.projects.import-activity-name:Grant Cercetare}")
    private String importActivityName;

    /** Canonical projects whose director name-signature equals the researcher's. Empty if the researcher has no name. */
    public List<ScholardexProjectListItemResponse> myProjects(User.ResearcherProfile profile) {
        if (profile == null) {
            return List.of();
        }
        String first = profile.getFirstName() == null ? "" : profile.getFirstName();
        String last = profile.getLastName() == null ? "" : profile.getLastName();
        String signature = ProjectCanonicalizationService.signature((first + " " + last).trim());
        if (signature == null || signature.isBlank()) {
            return List.of();
        }
        return projectReadPort.findByDirectorSignature(signature);
    }

    /** Outcome of an import/link. {@code status} ∈ CREATED / EXISTS / LINKED / PROJECT_NOT_FOUND /
     *  INSTANCE_NOT_FOUND / ACTIVITY_NOT_CONFIGURED. */
    public record ImportResult(String status, String instanceId, String projectTitle) {
        static ImportResult created(String id, String title) { return new ImportResult("CREATED", id, title); }
        static ImportResult exists(String id, String title) { return new ImportResult("EXISTS", id, title); }
        static ImportResult linked(String id, String title) { return new ImportResult("LINKED", id, title); }
        static ImportResult projectNotFound() { return new ImportResult("PROJECT_NOT_FOUND", null, null); }
        static ImportResult instanceNotFound() { return new ImportResult("INSTANCE_NOT_FOUND", null, null); }
        static ImportResult activityNotConfigured() { return new ImportResult("ACTIVITY_NOT_CONFIGURED", null, null); }
    }

    /**
     * Import a canonical project as a {@code Grant Cercetare} activity instance for the researcher. Pre-fills the
     * project title, budget, and an inferred director role into whichever of those fields the activity declares, plus
     * the {@code PROJECT_GRANT_ID} reference. Idempotent: if the researcher already has an instance of the import
     * activity carrying this {@code PROJECT_GRANT_ID}, returns it ({@code EXISTS}) without creating a duplicate.
     */
    public ImportResult importProject(String researcherEmail, String projectId) {
        return importProject(researcherEmail, projectId, false);
    }

    /**
     * @param asParticipant when true the imported instance's {@code Rol} is the participant value ("Membru") rather
     *        than an inferred director role — the search-driven entry point (slice 4) for someone who took part in a
     *        project they don't direct. Everything else (pre-fill, {@code PROJECT_GRANT_ID}, idempotency) is identical.
     */
    public ImportResult importProject(String researcherEmail, String projectId, boolean asParticipant) {
        ScholardexProjectListItemResponse project = projectReadPort.findById(projectId);
        if (project == null) {
            return ImportResult.projectNotFound();
        }
        // Idempotency — same researcher + same canonical project reference → no duplicate.
        Optional<ActivityInstance> existing = activityInstanceRepository.findAllByResearcherId(researcherEmail).stream()
                .filter(i -> i.getReferenceFields() != null
                        && projectId.equals(i.getReferenceFields().get(Activity.ReferenceField.PROJECT_GRANT_ID)))
                .findFirst();
        if (existing.isPresent()) {
            return ImportResult.exists(existing.get().getId(), project.title());
        }

        List<Activity> activities = activityRepository.findByName(importActivityName);
        if (activities.isEmpty()) {
            return ImportResult.activityNotConfigured();
        }
        Activity activity = activities.get(0);

        String displayName = displayName(project);
        ActivityInstance instance = new ActivityInstance();
        instance.setResearcherId(researcherEmail);
        instance.setActivity(activity);
        instance.setName(displayName);
        instance.setDate(project.startYear() != null ? String.valueOf(project.startYear()) : null);

        Map<String, String> fields = new LinkedHashMap<>();
        putIfDeclared(activity, fields, "Nume Proiect", displayName);
        if (project.budget() != null) {
            putIfDeclared(activity, fields, "Buget", String.valueOf(project.budget()));
        }
        String role = asParticipant ? participantRole(activity) : inferDirectorRole(activity, project.funder());
        putIfDeclared(activity, fields, "Rol", role);
        instance.setFields(fields);

        Map<Activity.ReferenceField, String> refs = new LinkedHashMap<>();
        refs.put(Activity.ReferenceField.PROJECT_GRANT_ID, projectId);
        instance.setReferenceFields(refs);

        ActivityInstance saved = activityInstanceRepository.save(instance);
        return ImportResult.created(saved.getId(), displayName);
    }

    /**
     * Link an existing activity instance to a canonical project: set its {@code PROJECT_GRANT_ID} reference (re-linking
     * overwrites a prior one) and back-fill ONLY the blank display fields the activity declares (never clobbering the
     * researcher's own values). Used for free-text activities entered before the picker existed. The instance must
     * belong to the researcher.
     */
    public ImportResult linkProject(String researcherEmail, String instanceId, String projectId) {
        ScholardexProjectListItemResponse project = projectReadPort.findById(projectId);
        if (project == null) {
            return ImportResult.projectNotFound();
        }
        Optional<ActivityInstance> opt = activityInstanceRepository.findById(instanceId);
        if (opt.isEmpty() || !researcherEmail.equals(opt.get().getResearcherId())) {
            return ImportResult.instanceNotFound();
        }
        ActivityInstance instance = opt.get();

        Map<Activity.ReferenceField, String> refs =
                instance.getReferenceFields() != null ? instance.getReferenceFields() : new LinkedHashMap<>();
        refs.put(Activity.ReferenceField.PROJECT_GRANT_ID, projectId);
        instance.setReferenceFields(refs);

        // Back-fill only blanks from the canonical record (preserve any value the researcher already entered).
        Activity activity = instance.getActivity();
        Map<String, String> fields = instance.getFields() != null ? instance.getFields() : new LinkedHashMap<>();
        backfillIfBlank(activity, fields, "Nume Proiect", displayName(project));
        if (project.budget() != null) {
            backfillIfBlank(activity, fields, "Buget", String.valueOf(project.budget()));
        }
        instance.setFields(fields);

        ActivityInstance saved = activityInstanceRepository.save(instance);
        return ImportResult.linked(saved.getId(), displayName(project));
    }

    private static String displayName(ScholardexProjectListItemResponse p) {
        return p.title() != null && !p.title().isBlank() ? p.title() : p.code();
    }

    /** Set {@code fieldName} only if the activity declares it, the value is non-blank, and the current value is blank. */
    private static void backfillIfBlank(Activity activity, Map<String, String> fields, String fieldName, String value) {
        if (value == null || value.isBlank() || activity == null || !declaresField(activity, fieldName)) {
            return;
        }
        String current = fields.get(fieldName);
        if (current == null || current.isBlank()) {
            fields.put(fieldName, value);
        }
    }

    /** Set {@code fieldName} only if the activity declares it (config-aware) and the value is non-blank. */
    private static void putIfDeclared(Activity activity, Map<String, String> fields, String fieldName, String value) {
        if (value == null || value.isBlank() || !declaresField(activity, fieldName)) {
            return;
        }
        fields.put(fieldName, value);
    }

    private static boolean declaresField(Activity activity, String fieldName) {
        return field(activity, fieldName) != null;
    }

    private static Activity.Field field(Activity activity, String fieldName) {
        if (activity.getFields() == null) {
            return null;
        }
        return activity.getFields().stream().filter(f -> fieldName.equalsIgnoreCase(f.getName())).findFirst().orElse(null);
    }

    /**
     * Pick a valid "Rol" value for a director import. The researcher reached this via a director match, so we choose a
     * director-scoped allowed value, international vs national by funder. Falls back to the first director value, then
     * the first allowed value. Returns null if the activity has no Rol field with allowed values (the field is skipped).
     */
    private static String inferDirectorRole(Activity activity, String funder) {
        Activity.Field rol = field(activity, "Rol");
        if (rol == null || rol.getAllowedValues() == null || rol.getAllowedValues().isEmpty()) {
            return null;
        }
        boolean international = funder != null && CanonicalizationSupport.normalizeName(funder) != null
                && CanonicalizationSupport.normalizeName(funder)
                        .matches(".*(\\bec\\b|horizon|fp7|h2020|erc|erasmus|cost|interreg|marie).*");
        List<String> directorValues = rol.getAllowedValues().stream()
                .filter(v -> contains(v, "director"))
                .toList();
        if (directorValues.isEmpty()) {
            return rol.getAllowedValues().get(0);
        }
        return directorValues.stream()
                .filter(v -> contains(v, "interna") == international)
                .findFirst()
                .orElse(directorValues.get(0));
    }

    private static boolean contains(String value, String needle) {
        String norm = CanonicalizationSupport.normalizeName(value);
        return norm != null && norm.contains(needle);
    }

    /** The participant "Rol" value ("Membru") for a search-driven participant import; falls back to the last allowed
     *  value (typically the non-director one), then null if the activity has no Rol allowed values. */
    private static String participantRole(Activity activity) {
        Activity.Field rol = field(activity, "Rol");
        if (rol == null || rol.getAllowedValues() == null || rol.getAllowedValues().isEmpty()) {
            return null;
        }
        return rol.getAllowedValues().stream()
                .filter(v -> contains(v, "membru"))
                .findFirst()
                .orElse(rol.getAllowedValues().get(rol.getAllowedValues().size() - 1));
    }
}
