package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.workspace.WorkspacePreferences;
import ro.uvt.pokedex.core.repository.WorkspacePreferencesRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.service.application.PublicationAuthorshipDecisionService;
import ro.uvt.pokedex.core.service.application.PublicationWizardFacade;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;
import ro.uvt.pokedex.core.service.application.model.ResearcherWorkspaceViewModel;
import ro.uvt.pokedex.core.service.application.model.WizardPublicationCommand;
import ro.uvt.pokedex.core.service.application.model.ResearcherWorkspaceViewModel.RecentActivityItem;
import ro.uvt.pokedex.core.service.application.model.ResearcherWorkspaceViewModel.WorkspaceState;
import ro.uvt.pokedex.core.service.application.model.TabDef;
import ro.uvt.pokedex.core.service.application.model.UserActivityInstancesViewModel;
import ro.uvt.pokedex.core.service.application.model.PublicationAuthorshipReviewState;
import ro.uvt.pokedex.core.service.application.model.PublicationMetadataPatch;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.WorkspaceNotification;
import ro.uvt.pokedex.core.service.application.model.WorkspaceNotification.NotificationType;
import ro.uvt.pokedex.core.service.application.model.WorkspaceSearchResult;
import ro.uvt.pokedex.core.service.application.model.WorkspaceSearchResult.EntityType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

@Controller
@RequestMapping("/user/workspace")
@RequiredArgsConstructor
public class ResearcherWorkspaceController {

    private final UserRepository userRepository;
    private final UserPublicationFacade userPublicationFacade;
    private final UserActivityInstanceFacade userActivityInstanceFacade;
    private final UserScopusTaskFacade userScopusTaskFacade;
    private final ro.uvt.pokedex.core.service.application.UserOpenAlexTaskFacade userOpenAlexTaskFacade;
    private final UserReportFacade userReportFacade;
    private final WorkspacePreferencesRepository workspacePreferencesRepository;
    private final PublicationWizardFacade publicationWizardFacade;
    private final PublicationAuthorshipDecisionService publicationAuthorshipDecisionService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ro.uvt.pokedex.core.service.application.ResearcherProjectService researcherProjectService;
    private final ro.uvt.pokedex.core.service.application.onboarding.ResearcherOnboardingService researcherOnboardingService;
    private final ro.uvt.pokedex.core.service.application.onboarding.OnboardingAuthorCandidateService onboardingAuthorCandidateService;
    private final ro.uvt.pokedex.core.service.application.onboarding.OnboardingClaimRecommendationService onboardingClaimRecommendationService;
    private final ro.uvt.pokedex.core.service.application.NudgeService nudgeService;

    // ── MVC ──────────────────────────────────────────────────────────────
    @GetMapping
    public String showWorkspace(Model model, Authentication authentication) {
        return currentUser(authentication).map(u -> {
            model.addAttribute("workspace", buildWorkspaceViewModel(u));
            updateLastVisit(u.getEmail());
            return "user/workspace";
        }).orElse("redirect:/login");
    }

    // ── JSON: publications ────────────────────────────────────────────────
    @GetMapping("/publications")
    @ResponseBody
    public ResponseEntity<UserPublicationsViewModel> getPublications(Authentication authentication) {
        return currentUser(authentication).map(u ->
                userPublicationFacade.buildWorkspacePublicationsView(u.getEmail())
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.ok(emptyPublicationsViewModel()))
        ).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: activities ──────────────────────────────────────────────────
    @GetMapping("/activities")
    @ResponseBody
    public ResponseEntity<UserActivityInstancesViewModel> getActivities(Authentication authentication) {
        return currentUser(authentication).map(u ->
                ResponseEntity.ok(userActivityInstanceFacade.buildActivityInstancesView(u.getEmail()))
        ).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: my projects (H78 — director-attributed canonical projects) ──
    @GetMapping("/projects/mine")
    @ResponseBody
    public ResponseEntity<List<ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse>> getMyProjects(
            Authentication authentication) {
        return currentUser(authentication)
                .map(u -> ResponseEntity.ok(researcherProjectService.myProjects(u.getResearcherProfile())))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: import a canonical project as a Grant Cercetare activity (H78 slice 2) ──
    @PostMapping("/activities/import-project/{projectId}")
    @ResponseBody
    public ResponseEntity<ro.uvt.pokedex.core.service.application.ResearcherProjectService.ImportResult> importProject(
            @PathVariable String projectId,
            @RequestParam(name = "role", defaultValue = "director") String role,
            Authentication authentication) {
        boolean asParticipant = "participant".equalsIgnoreCase(role);
        return currentUser(authentication).map(u -> {
            var result = researcherProjectService.importProject(u.getEmail(), projectId, asParticipant);
            return switch (result.status()) {
                case "CREATED", "EXISTS" -> ResponseEntity.ok(result);
                case "PROJECT_NOT_FOUND" -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
                default -> ResponseEntity.status(HttpStatus.CONFLICT).body(result); // ACTIVITY_NOT_CONFIGURED
            };
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: link an existing activity to a canonical project (H78 slice 3) ──
    @PostMapping("/activities/{instanceId}/link-project/{projectId}")
    @ResponseBody
    public ResponseEntity<ro.uvt.pokedex.core.service.application.ResearcherProjectService.ImportResult> linkProject(
            @PathVariable String instanceId, @PathVariable String projectId, Authentication authentication) {
        return currentUser(authentication).map(u -> {
            var result = researcherProjectService.linkProject(u.getEmail(), instanceId, projectId);
            return switch (result.status()) {
                case "LINKED" -> ResponseEntity.ok(result);
                default -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(result); // PROJECT/INSTANCE_NOT_FOUND
            };
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: search ──────────────────────────────────────────────────────
    @GetMapping("/search")
    @ResponseBody
    public ResponseEntity<List<WorkspaceSearchResult>> search(
            @RequestParam(name = "q", required = false) String q,
            Authentication authentication) {
        return currentUser(authentication).map(u -> {
            if (q == null || q.isBlank() || q.strip().length() < 2) {
                return ResponseEntity.ok(List.<WorkspaceSearchResult>of());
            }
            return ResponseEntity.ok(performSearch(q.strip(), u.getEmail()));
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: notifications ───────────────────────────────────────────────
    @GetMapping("/notifications")
    @ResponseBody
    public ResponseEntity<List<WorkspaceNotification>> getNotifications(Authentication authentication) {
        return currentUser(authentication).map(u -> {
            WorkspacePreferences prefs = loadOrCreatePreferences(u.getEmail());
            return ResponseEntity.ok(buildNotifications(u, prefs.getLastVisitAt(), dismissedSet(prefs)));
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: mark all notifications as read ─────────────────────────────
    @PostMapping("/notifications/mark-read")
    @ResponseBody
    public ResponseEntity<Void> markNotificationsRead(Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userOpt.get();
        Instant now = Instant.now();
        WorkspacePreferences prefs = loadOrCreatePreferences(user.getEmail());
        prefs.setLastVisitAt(now);
        // Add every currently-visible notification ID to the dismissed set so that
        // state-based notifications (PROFILE_INCOMPLETE, REPORT_AVAILABLE, SYNC_COMPLETED)
        // don't reappear on the next page load. Time-based notifications (NEW_CITATION)
        // are already handled by advancing lastVisitAt above.
        List<WorkspaceNotification> current = buildNotifications(user, now, Set.of());
        List<String> dismissed = prefs.getDismissedNotificationIds() != null
                ? new ArrayList<>(prefs.getDismissedNotificationIds())
                : new ArrayList<>();
        current.stream()
                .map(WorkspaceNotification::id)
                .filter(id -> !dismissed.contains(id))
                .forEach(dismissed::add);
        prefs.setDismissedNotificationIds(dismissed);
        prefs.setUpdatedAt(now);
        workspacePreferencesRepository.save(prefs);
        return ResponseEntity.ok().build();
    }

    // ── JSON: dismiss a single notification ───────────────────────────────
    @PostMapping("/notifications/dismiss")
    @ResponseBody
    public ResponseEntity<Void> dismissNotification(
            @RequestBody NotificationDismissRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request.id() == null || request.id().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        WorkspacePreferences prefs = loadOrCreatePreferences(userOpt.get().getEmail());
        List<String> dismissed = prefs.getDismissedNotificationIds() != null
                ? new ArrayList<>(prefs.getDismissedNotificationIds())
                : new ArrayList<>();
        if (!dismissed.contains(request.id())) {
            dismissed.add(request.id());
            prefs.setDismissedNotificationIds(dismissed);
            prefs.setUpdatedAt(Instant.now());
            workspacePreferencesRepository.save(prefs);
        }
        return ResponseEntity.ok().build();
    }

    // ── JSON: create activity instance ────────────────────────────────────
    @PostMapping("/activities/create")
    @ResponseBody
    public ResponseEntity<ActivityInstance> createActivityInstance(
            @RequestBody ActivityInstanceCreateRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Optional<Activity> activityOpt = userActivityInstanceFacade.findActivity(request.activityId());
        if (activityOpt.isEmpty()) return ResponseEntity.badRequest().build();
        ActivityInstance instance = new ActivityInstance();
        instance.setResearcherId(userOpt.get().getEmail());
        instance.setActivity(activityOpt.get());
        instance.setName(request.name() != null && !request.name().isBlank()
                ? request.name() : activityOpt.get().getName());
        instance.setDate(request.date());
        instance.setFields(request.fields());
        if (request.referenceFields() != null) {
            Map<Activity.ReferenceField, String> refMap = new EnumMap<>(Activity.ReferenceField.class);
            request.referenceFields().forEach((k, v) -> {
                try { refMap.put(Activity.ReferenceField.valueOf(k), v); }
                catch (IllegalArgumentException ignored) {}
            });
            instance.setReferenceFields(refMap);
        }
        return ResponseEntity.ok(userActivityInstanceFacade.saveActivityInstance(instance));
    }

    // ── JSON: update activity instance ────────────────────────────────────
    @PostMapping("/activities/update")
    @ResponseBody
    public ResponseEntity<Void> updateActivityInstance(
            @RequestBody ActivityInstanceUpdateRequest request,
            Authentication authentication) {
        if (currentUser(authentication).isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        ActivityInstance patch = new ActivityInstance();
        patch.setId(request.id());
        patch.setFields(request.fields());
        if (request.referenceFields() != null) {
            Map<Activity.ReferenceField, String> refMap = new EnumMap<>(Activity.ReferenceField.class);
            request.referenceFields().forEach((k, v) -> {
                try { refMap.put(Activity.ReferenceField.valueOf(k), v); }
                catch (IllegalArgumentException ignored) {}
            });
            patch.setReferenceFields(refMap);
        }
        userActivityInstanceFacade.updateActivityInstance(patch);
        return ResponseEntity.ok().build();
    }

    // ── JSON: delete activity instance ────────────────────────────────────
    @PostMapping("/activities/delete/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteActivityInstance(
            @PathVariable String id,
            Authentication authentication) {
        if (currentUser(authentication).isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        userActivityInstanceFacade.deleteActivityInstance(id);
        return ResponseEntity.ok().build();
    }

    // ── JSON: publication save (inline edit) ─────────────────────────────
    @PostMapping("/publications/save/{id}")
    @ResponseBody
    public ResponseEntity<Void> savePublication(
            @PathVariable String id,
            @RequestBody PublicationMetadataPatch patch,
            Authentication authentication) {
        if (currentUser(authentication).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        userPublicationFacade.updatePublicationMetadata(id, patch);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/publications/{id}/authorship/confirm")
    @ResponseBody
    public ResponseEntity<AuthorshipDecisionResponse> confirmPublicationAuthorship(
            @PathVariable String id,
            @RequestBody(required = false) AuthorshipDecisionRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var decision = publicationAuthorshipDecisionService.upsertDecision(
                userOpt.get().getEmail(),
                id,
                ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status.CONFIRMED,
                request != null ? request.reason() : null
        );
        return ResponseEntity.ok(new AuthorshipDecisionResponse(
                id,
                PublicationAuthorshipReviewState.Status.CONFIRMED,
                decision.getReason(),
                decision.getUpdatedAt()
        ));
    }

    @PostMapping("/publications/{id}/authorship/reject")
    @ResponseBody
    public ResponseEntity<AuthorshipDecisionResponse> rejectPublicationAuthorship(
            @PathVariable String id,
            @RequestBody(required = false) AuthorshipDecisionRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var decision = publicationAuthorshipDecisionService.upsertDecision(
                userOpt.get().getEmail(),
                id,
                ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status.REJECTED,
                request != null ? request.reason() : null
        );
        return ResponseEntity.ok(new AuthorshipDecisionResponse(
                id,
                PublicationAuthorshipReviewState.Status.REJECTED,
                decision.getReason(),
                decision.getUpdatedAt()
        ));
    }

    @DeleteMapping("/publications/{id}/authorship")
    @ResponseBody
    public ResponseEntity<AuthorshipDecisionResponse> clearPublicationAuthorshipDecision(
            @PathVariable String id,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        publicationAuthorshipDecisionService.clearDecision(userOpt.get().getEmail(), id);
        return ResponseEntity.ok(new AuthorshipDecisionResponse(
                id,
                PublicationAuthorshipReviewState.Status.PENDING,
                null,
                null
        ));
    }

    @PostMapping("/publications/authorship/bulk")
    @ResponseBody
    public ResponseEntity<BulkAuthorshipDecisionResponse> bulkPublicationAuthorshipDecision(
            @RequestBody BulkAuthorshipDecisionRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status actionStatus;
        try {
            actionStatus = switch (request.action()) {
                case CONFIRM -> ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status.CONFIRMED;
                case REJECT -> ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status.REJECTED;
            };
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(new BulkAuthorshipDecisionResponse(
                    List.of(),
                    List.of(new BulkAuthorshipDecisionFailure(null, "Invalid bulk review action.")),
                    List.of()
            ));
        }

        try {
            var result = publicationAuthorshipDecisionService.upsertBulkDecisions(
                    userOpt.get().getEmail(),
                    request.publicationIds(),
                    actionStatus,
                    request.reason()
            );
            List<AuthorshipDecisionResponse> successes = result.succeededByPublicationId().values().stream()
                    .map(decision -> new AuthorshipDecisionResponse(
                            decision.getPublicationId(),
                            decision.getStatus() == ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status.CONFIRMED
                                    ? PublicationAuthorshipReviewState.Status.CONFIRMED
                                    : PublicationAuthorshipReviewState.Status.REJECTED,
                            decision.getReason(),
                            decision.getUpdatedAt()
                    ))
                    .toList();
            List<BulkAuthorshipDecisionFailure> failures = result.failures().stream()
                    .map(failure -> new BulkAuthorshipDecisionFailure(failure.publicationId(), failure.message()))
                    .toList();
            return ResponseEntity.ok(new BulkAuthorshipDecisionResponse(
                    successes.stream().map(AuthorshipDecisionResponse::publicationId).toList(),
                    failures,
                    successes
            ));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new BulkAuthorshipDecisionResponse(
                    List.of(),
                    List.of(new BulkAuthorshipDecisionFailure(null, ex.getMessage())),
                    List.of()
            ));
        }
    }

    // ── JSON: preferences ─────────────────────────────────────────────────
    @PostMapping("/preferences")
    @ResponseBody
    public ResponseEntity<Void> savePreferences(
            @RequestBody WorkspacePreferencesRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        WorkspacePreferences prefs = loadOrCreatePreferences(userOpt.get().getEmail());
        prefs.setOverviewCardOrder(request.cardOrder() != null ? request.cardOrder() : List.of());
        prefs.setUpdatedAt(Instant.now());
        workspacePreferencesRepository.save(prefs);
        return ResponseEntity.ok().build();
    }

    // ── JSON: profile tab ─────────────────────────────────────────────────
    @GetMapping("/profile")
    @ResponseBody
    public ResponseEntity<WorkspaceProfileViewModel> getProfile(Authentication authentication) {
        return currentUser(authentication).map(u -> {
            // Re-load from DB — the session principal is stale after a profile save.
            User freshUser = userRepository.findById(u.getEmail()).orElse(u);
            User.ResearcherProfile profile = freshUser.getResearcherProfile();
            if (profile == null) {
                profile = new User.ResearcherProfile();
            }
            int completeness = computeProfileCompleteness(profile);
            var tasksVm = userScopusTaskFacade.buildTasksView(freshUser.getEmail(), freshUser.getEmail());
            List<ScholardexAffiliationView> observedAffiliations = loadObservedAffiliations(profile);
            return ResponseEntity.ok(new WorkspaceProfileViewModel(
                    profile, completeness,
                    tasksVm.tasks(), tasksVm.citationsTasks(),
                    userOpenAlexTaskFacade.findTasksForUser(freshUser.getEmail()),
                    observedAffiliations,
                    requiresAffiliationConfirmation(profile),
                    researcherOnboardingService.evaluate(profile)   // H70: drives the onboarding wizard
            ));
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: profile save ────────────────────────────────────────────────
    @PostMapping("/profile/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveProfile(
            @RequestBody ProfileSaveRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userOpt.get();
        // Re-load from DB so we don't operate on a stale auth principal
        user = userRepository.findById(user.getEmail()).orElse(user);
        // Auto-create the profile on first save — this is the path for brand-new users.
        boolean isNewProfile = user.getResearcherProfile() == null;
        User.ResearcherProfile profile = isNewProfile ? new User.ResearcherProfile() : user.getResearcherProfile();
        profile.setFirstName(request.firstName() != null ? request.firstName().trim() : "");
        profile.setLastName(request.lastName() != null ? request.lastName().trim() : "");
        // PhD award year is optional — a null clears it (treated as "no post-PhD anchor" by criteria).
        profile.setPhdAwardYear(request.phdAwardYear());
        // Google Scholar id is retired from the UI; preserve any existing value (it is still an author-resolution
        // lookup key in ResearcherAuthorLookupService) rather than wiping it when the form omits the field.
        if (request.scholarId() != null) {
            profile.setScholarId(request.scholarId().trim());
        }
        profile.setScopusId(request.scopusId() != null
                ? new ArrayList<>(request.scopusId()) : new ArrayList<>());
        profile.setWosId(request.wosId() != null
                ? new ArrayList<>(request.wosId()) : new ArrayList<>());
        // Reject rather than silently null an unparseable ORCID — a 200-with-dropped-value reads as
        // "my ORCID won't save" to the user (it showed "Not set" after an apparently successful save).
        String rawOrcid = request.orcid();
        String normalizedOrcid = ro.uvt.pokedex.core.service.openalex.OrcidSupport.normalize(rawOrcid);
        if (rawOrcid != null && !rawOrcid.isBlank() && normalizedOrcid == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "Invalid ORCID \"" + rawOrcid.trim()
                            + "\" — use the 0000-0002-1825-0097 form or your orcid.org profile URL."));
        }
        profile.setOrcid(normalizedOrcid);
        List<String> currentAffiliationIds = normalizeAffiliationIds(request.currentAffiliationIds());
        List<String> pastAffiliationIds = normalizeAffiliationIds(request.pastAffiliationIds());
        pastAffiliationIds.removeIf(currentAffiliationIds::contains);
        profile.setCurrentAffiliationIds(currentAffiliationIds);
        profile.setPastAffiliationIds(pastAffiliationIds);
        if (Boolean.TRUE.equals(request.confirmAffiliationScope())) {
            profile.setAffiliationsConfirmedAt(Instant.now());
        }
        user.setResearcherProfile(profile);
        if (isNewProfile) {
            // Grant RESEARCHER role on first profile creation so the user gains
            // researcher-level access without needing an admin to intervene. Copy before adding:
            // freshly-provisioned principals carry an immutable Set.of(...) roles set
            // (KeycloakOAuth2LoginSuccessHandler, agent-dev), and mutating it throws — which
            // failed the very first onboarding save with an opaque "Could not save".
            java.util.Set<ro.uvt.pokedex.core.model.user.UserRole> roles =
                    new java.util.HashSet<>(user.getRoles() != null ? user.getRoles() : java.util.Set.of());
            roles.add(ro.uvt.pokedex.core.model.user.UserRole.RESEARCHER);
            user.setRoles(roles);
        }
        userRepository.save(user);
        return ResponseEntity.ok().build();
    }

    // ── JSON: onboarding author-match (H70 step 4) ────────────────────────
    @GetMapping("/profile/author-candidates")
    @ResponseBody
    public ResponseEntity<List<ro.uvt.pokedex.core.service.application.onboarding.AuthorCandidate>> getAuthorCandidates(
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findById(userOpt.get().getEmail()).orElse(userOpt.get());
        return ResponseEntity.ok(onboardingAuthorCandidateService.candidatesFor(user.getResearcherProfile()));
    }

    @PostMapping("/profile/author-match")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveAuthorMatch(
            @RequestBody AuthorMatchRequest request, Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findById(userOpt.get().getEmail()).orElse(userOpt.get());
        User.ResearcherProfile profile = user.getResearcherProfile();
        if (profile == null) return ResponseEntity.unprocessableEntity().build();

        List<String> confirmed = request.confirmedAuthorIds() == null ? List.of()
                : request.confirmedAuthorIds().stream()
                        .filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        profile.setConfirmedScholardexAuthorIds(new ArrayList<>(confirmed));
        // The primary must be one of the confirmed set; fall back to the first confirmed, else clear it.
        String primary = request.primaryAuthorId() != null ? request.primaryAuthorId().trim() : null;
        if (primary == null || !confirmed.contains(primary)) {
            primary = confirmed.isEmpty() ? null : confirmed.get(0);
        }
        profile.setPrimaryScholardexAuthorId(primary);
        user.setResearcherProfile(profile);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("confirmed", confirmed.size(), "primary", primary == null ? "" : primary));
    }

    // ── JSON: onboarding publication auto-claim (H70 step 5, terminal) ────
    @GetMapping("/profile/claim-recommendations")
    @ResponseBody
    public ResponseEntity<ro.uvt.pokedex.core.service.application.onboarding.ClaimRecommendations> getClaimRecommendations(
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findById(userOpt.get().getEmail()).orElse(userOpt.get());
        return ResponseEntity.ok(
                onboardingClaimRecommendationService.recommend(user.getEmail(), user.getResearcherProfile()));
    }

    @PostMapping("/profile/claim-apply")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> applyClaimRecommendations(
            @RequestBody(required = false) ClaimApplyRequest request, Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findById(userOpt.get().getEmail()).orElse(userOpt.get());
        User.ResearcherProfile profile = user.getResearcherProfile();
        if (profile == null) return ResponseEntity.unprocessableEntity().build();

        boolean confirmRecommended = request == null || request.confirmRecommended() == null
                || request.confirmRecommended();
        int confirmed = 0;
        if (confirmRecommended) {
            var recommendations = onboardingClaimRecommendationService.recommend(user.getEmail(), profile);
            if (!recommendations.recommendedConfirmIds().isEmpty()) {
                publicationAuthorshipDecisionService.upsertBulkDecisions(
                        user.getEmail(), recommendations.recommendedConfirmIds(),
                        ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status.CONFIRMED,
                        "Onboarding auto-claim");
                confirmed = recommendations.recommendedConfirmIds().size();
            }
        }
        // Terminal step — mark onboarding finished.
        profile.setOnboardingCompletedAt(Instant.now());
        user.setResearcherProfile(profile);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("confirmed", confirmed));
    }

    // ── JSON: trigger Scopus publication sync ─────────────────────────────
    @PostMapping("/profile/sync/publications")
    @ResponseBody
    public ResponseEntity<ScopusPublicationUpdate> triggerSyncPublications(
            @RequestBody SyncRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        ScopusPublicationUpdate draft = new ScopusPublicationUpdate();
        draft.setScopusId(request.scopusId());
        draft.setSyncMode(request.syncMode() != null ? request.syncMode() : "SINCE_LAST_UPDATE");
        draft.setStartYear(request.startYear());
        draft.setEndYear(request.endYear());
        ScopusPublicationUpdate saved =
                userScopusTaskFacade.createPublicationTask(userOpt.get().getEmail(), draft);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ── JSON: trigger Scopus citations sync ───────────────────────────────
    @PostMapping("/profile/sync/citations")
    @ResponseBody
    public ResponseEntity<ScopusCitationsUpdate> triggerSyncCitations(
            @RequestBody SyncRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        ScopusCitationsUpdate draft = new ScopusCitationsUpdate();
        draft.setScopusId(request.scopusId());
        draft.setSyncMode(request.syncMode() != null ? request.syncMode() : "SINCE_LAST_UPDATE");
        draft.setStartYear(request.startYear());
        draft.setEndYear(request.endYear());
        ScopusCitationsUpdate saved =
                userScopusTaskFacade.createCitationTask(userOpt.get().getEmail(), draft);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ── JSON: lean Scopus task status (for the sync-history live poll) ────
    @GetMapping("/profile/sync/tasks")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSyncTasks(Authentication authentication) {
        return currentUser(authentication).map(u -> {
            var tasksVm = userScopusTaskFacade.buildTasksView(u.getEmail(), u.getEmail());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "publications", tasksVm.tasks(),
                    "citations", tasksVm.citationsTasks(),
                    "openalex", userOpenAlexTaskFacade.findTasksForUser(u.getEmail())));
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: trigger OpenAlex author sync (H66B Phase 4a) ────────────────
    @PostMapping("/profile/sync/openalex-authors")
    @ResponseBody
    public ResponseEntity<ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate> triggerSyncOpenAlexAuthors(
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findById(userOpt.get().getEmail()).orElse(userOpt.get());
        User.ResearcherProfile profile = user.getResearcherProfile();
        String orcid = profile == null ? null
                : ro.uvt.pokedex.core.service.openalex.OrcidSupport.normalize(profile.getOrcid());
        if (orcid == null) {
            // No (valid) ORCID on the profile — nothing to sync against.
            return ResponseEntity.unprocessableEntity().build();
        }
        ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate draft =
                new ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate();
        draft.setOrcid(orcid);
        draft.setResearcherAuthorId(profile.getPrimaryScholardexAuthorId());
        ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate saved =
                userOpenAlexTaskFacade.createAuthorTask(user.getEmail(), draft);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ── JSON: publication citations ───────────────────────────────────────
    @GetMapping("/publications/{id}/citations")
    @ResponseBody
    public ResponseEntity<ro.uvt.pokedex.core.service.application.model.UserPublicationCitationsViewModel> getPublicationCitations(
            @PathVariable String id,
            Authentication authentication) {
        if (currentUser(authentication).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userPublicationFacade.buildCitationsView(authentication.getName(), id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── JSON: wizard — co-author list for affiliation ─────────────────────
    @GetMapping("/publications/wizard-authors")
    @ResponseBody
    public ResponseEntity<List<ScholardexAuthorView>> getWizardAuthors(
            @RequestParam(name = "afid", required = false) String afid,
            Authentication authentication) {
        if (currentUser(authentication).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (afid == null || afid.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(publicationWizardFacade.findAuthorsForAffiliation(afid));
    }

    // ── JSON: wizard — submit new publication ─────────────────────────────
    @PostMapping("/publications/wizard")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> submitWizardPublication(
            @RequestBody WizardPublicationCommand command,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userOpt.get();
        // Creator is set server-side so the frontend never needs the researcher id
        String creator = user.getEmail();
        command.setCreator(creator);
        try {
            var result = publicationWizardFacade.submitPublication(command, user);
            return ResponseEntity.ok(Map.of(
                    "sourceRecordId", result.sourceRecordId(),
                    "eid", result.eid()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Optional<User> currentUser(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof User u)) {
            return Optional.empty();
        }
        return Optional.of(u);
    }

    private ResearcherWorkspaceViewModel buildWorkspaceViewModel(User currentUser) {
        String email = currentUser.getEmail();
        User.ResearcherProfile profile = currentUser.getResearcherProfile();

        Optional<UserPublicationsViewModel> pubsOpt =
                userPublicationFacade.buildUserPublicationsView(email);

        UserActivityInstancesViewModel activitiesVm =
                userActivityInstanceFacade.buildActivityInstancesView(email);

        int availableReportCount = userReportFacade.buildIndividualReportsListView(email)
                .individualReports().size();

        var tasksVm = userScopusTaskFacade.buildTasksView(email, email);
        int pendingScopusTaskCount = (int) tasksVm.tasks().stream()
                .filter(t -> t.getStatus() == Status.PENDING)
                .count();
        pendingScopusTaskCount += (int) tasksVm.citationsTasks().stream()
                .filter(t -> t.getStatus() == Status.PENDING)
                .count();

        WorkspacePreferences prefs = loadOrCreatePreferences(email);

        String researcherName = profile != null ? profile.getName() : null;
        boolean hasProfile = profile != null;
        int completeness = computeProfileCompleteness(profile);

        int publicationCount = pubsOpt.map(vm -> vm.publications().size()).orElse(0);
        int totalCitations = pubsOpt.map(UserPublicationsViewModel::numCitations).orElse(0);
        int hIndex = pubsOpt.map(UserPublicationsViewModel::hIndex).orElse(0);
        int activityInstanceCount = activitiesVm.activityInstances().size();

        List<RecentActivityItem> recentActivities = activitiesVm.activityInstances().stream()
                .filter(ai -> ai.getActivity() != null)
                .sorted(Comparator.comparing(ActivityInstance::getDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(ai -> new RecentActivityItem(
                        ai.getId(),
                        ai.getActivity().getName(),
                        ai.getDate()))
                .toList();

        WorkspaceState state = determineWorkspaceState(profile, email, availableReportCount);

        // ── Chart data ───────────────────────────────────────────────────
        // Publications per year: keyed by the researcher's own publication year.
        List<String> chartYears = new ArrayList<>();
        List<Integer> chartPubs = new ArrayList<>();
        if (pubsOpt.isPresent()) {
            var pubsByYear = new TreeMap<String, Integer>();
            for (var p : pubsOpt.get().publications()) {
                String date = p.getCoverDate();
                if (date == null || date.length() < 4) continue;
                pubsByYear.merge(date.substring(0, 4), 1, Integer::sum);
            }
            pubsByYear.forEach((year, count) -> { chartYears.add(year); chartPubs.add(count); });
        }
        // Citations per year: bucketed by the CITING paper's year (Google-Scholar semantics),
        // as two series — including and excluding the researcher's self-citations.
        Set<String> researcherAuthorIds = new HashSet<>();
        if (profile != null) {
            if (profile.getConfirmedScholardexAuthorIds() != null) {
                researcherAuthorIds.addAll(profile.getConfirmedScholardexAuthorIds());
            }
            if (profile.getPrimaryScholardexAuthorId() != null) {
                researcherAuthorIds.add(profile.getPrimaryScholardexAuthorId());
            }
        }
        UserPublicationFacade.CitationTimeline citeTimeline = pubsOpt
                .map(vm -> userPublicationFacade.buildCitationsPerYear(vm.publications(), researcherAuthorIds))
                .orElseGet(UserPublicationFacade.CitationTimeline::empty);
        // Headline citation count uses the same edge-based total as the chart (incl. self-citations),
        // so the stat tile and the per-year chart agree.
        totalCitations = citeTimeline.total();

        var overviewCharts = new ResearcherWorkspaceViewModel.OverviewCharts(
                chartYears, chartPubs,
                citeTimeline.years(), citeTimeline.inclSelf(), citeTimeline.exclSelf(),
                activitiesVm.activityLabels(), activitiesVm.activityData());

        List<WorkspaceNotification> notifications =
                buildNotifications(currentUser, prefs.getLastVisitAt(), dismissedSet(prefs));

        List<TabDef> tabs = List.of(
                new TabDef("overview",     "Overview",      "fa-solid fa-house",         true,  false),
                new TabDef("publications", "Publications",  "fa-solid fa-chart-area",    false, false),
                new TabDef("activities",   "Activities",    "fa-solid fa-pen-fancy",     false, false),
                new TabDef("profile",      "Profile & Sync","fa-solid fa-user-graduate", false, true)
        );

        return new ResearcherWorkspaceViewModel(
                researcherName,
                hasProfile,
                completeness,
                publicationCount,
                totalCitations,
                hIndex,
                activityInstanceCount,
                availableReportCount,
                notifications.size(),
                recentActivities,
                pendingScopusTaskCount,
                tabs,
                prefs.getOverviewCardOrder(),
                state,
                overviewCharts
        );
    }

    private WorkspaceState determineWorkspaceState(
            User.ResearcherProfile profile, String researcherId, int availableReportCount) {
        if (researcherId == null || profile == null) {
            return WorkspaceState.NEW_USER;
        }
        if (!hasLinkedResearchIdentity(profile) || requiresAffiliationConfirmation(profile)) {
            return WorkspaceState.INCOMPLETE_PROFILE;
        }
        if (availableReportCount > 0) {
            return WorkspaceState.REPORTING_SEASON;
        }
        return WorkspaceState.ACTIVE;
    }

    private int computeProfileCompleteness(User.ResearcherProfile profile) {
        if (profile == null) {
            return 0;
        }
        int score = 0;
        if (profile.getFirstName() != null && !profile.getFirstName().isBlank()) score += 25;
        if (profile.getLastName() != null && !profile.getLastName().isBlank()) score += 25;
        if (hasLinkedResearchIdentity(profile)) score += 25;
        if (!requiresAffiliationConfirmation(profile)) score += 25;
        return score;
    }

    private boolean hasLinkedResearchIdentity(User.ResearcherProfile profile) {
        if (profile == null) {
            return false;
        }
        boolean hasScopus = profile.getScopusId() != null && !profile.getScopusId().isEmpty();
        boolean hasWos = profile.getWosId() != null && !profile.getWosId().isEmpty();
        boolean hasPrimaryAuthor = profile.getPrimaryScholardexAuthorId() != null
                && !profile.getPrimaryScholardexAuthorId().isBlank();
        return hasScopus || hasWos || hasPrimaryAuthor;
    }

    private boolean requiresAffiliationConfirmation(User.ResearcherProfile profile) {
        return hasLinkedResearchIdentity(profile)
                && (profile == null || profile.getAffiliationsConfirmedAt() == null);
    }

    private List<ScholardexAffiliationView> loadObservedAffiliations(User.ResearcherProfile profile) {
        if (profile == null || !hasLinkedResearchIdentity(profile)) {
            return List.of();
        }
        List<String> authorLookupKeys = researcherAuthorLookupService.resolveAuthorLookupKeys(profile);
        if (authorLookupKeys.isEmpty()) {
            return List.of();
        }
        List<ScholardexAuthorView> authors = scholardexProjectionReadService.findAuthorsByIdIn(authorLookupKeys);
        Set<String> affiliationIds = authors.stream()
                .map(ScholardexAuthorView::getAffiliationIds)
                .filter(ids -> ids != null && !ids.isEmpty())
                .flatMap(List::stream)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (affiliationIds.isEmpty()) {
            return List.of();
        }
        return scholardexProjectionReadService.findAffiliationsByIdIn(affiliationIds).stream()
                .sorted(Comparator.comparing(
                        affiliation -> affiliation.getName() == null ? "" : affiliation.getName(),
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();
    }

    private List<String> normalizeAffiliationIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<WorkspaceSearchResult> performSearch(String q, String userEmail) {
        List<WorkspaceSearchResult> results = new ArrayList<>();
        if (userEmail == null) return results;

        final String qLower = q.toLowerCase();

        // Load the researcher's own publications once
        var pubsOpt = userPublicationFacade.buildUserPublicationsView(userEmail);

        // Publications — researcher-scoped, title match, cap at 10
        pubsOpt.ifPresent(vm -> vm.publications().stream()
                .filter(p -> p.getTitle() != null
                        && p.getTitle().toLowerCase().contains(qLower))
                .limit(10)
                .map(p -> new WorkspaceSearchResult(
                        p.getId(),
                        EntityType.PUBLICATION,
                        p.getTitle(),
                        p.getCoverDate(),
                        "/user/workspace#publications"))
                .forEach(results::add));

        // Activities — researcher-scoped, name match, cap at 10
        userActivityInstanceFacade.buildActivityInstancesView(userEmail)
                .activityInstances().stream()
                .filter(ai -> ai.getActivity() != null
                        && ai.getActivity().getName().toLowerCase().contains(qLower))
                .limit(10)
                .map(ai -> new WorkspaceSearchResult(
                        ai.getId(),
                        EntityType.ACTIVITY,
                        ai.getActivity().getName(),
                        ai.getDate(),
                        "/user/workspace#activities"))
                .forEach(results::add);

        return results;
    }

    private Set<String> dismissedSet(WorkspacePreferences prefs) {
        var ids = prefs.getDismissedNotificationIds();
        return (ids != null && !ids.isEmpty()) ? new HashSet<>(ids) : Set.of();
    }

    private List<WorkspaceNotification> buildNotifications(User user, Instant since, Set<String> dismissed) {
        List<WorkspaceNotification> notifications = new ArrayList<>();
        String email = user.getEmail();

        // Profile completeness
        User.ResearcherProfile userProfile = user.getResearcherProfile();
        WorkspaceState state = determineWorkspaceState(userProfile, userProfile != null ? email : null, 0);
        if (state == WorkspaceState.NEW_USER || state == WorkspaceState.INCOMPLETE_PROFILE) {
            notifications.add(new WorkspaceNotification(
                    "profile-incomplete",
                    NotificationType.PROFILE_INCOMPLETE,
                    "Profile incomplete",
                    "Add your identity links and confirm your current and past affiliations to enable publication review.",
                    null,
                    "/user/profile"
            ));
        }

        // Completed Scopus tasks
        var tasksVm = userScopusTaskFacade.buildTasksView(email, email);
        tasksVm.tasks().stream()
                .filter(t -> t.getStatus() == Status.COMPLETED
                        && t.getExecutionDate() != null)
                .forEach(t -> notifications.add(new WorkspaceNotification(
                        "sync-pub-" + t.getId(),
                        NotificationType.SYNC_COMPLETED,
                        "Scopus publication sync completed",
                        "Publication update for Scopus ID " + t.getScopusId() + " completed.",
                        t.getExecutionDate(),
                        null
                )));
        tasksVm.citationsTasks().stream()
                .filter(t -> t.getStatus() == Status.COMPLETED
                        && t.getExecutionDate() != null)
                .forEach(t -> notifications.add(new WorkspaceNotification(
                        "sync-cit-" + t.getId(),
                        NotificationType.SYNC_COMPLETED,
                        "Scopus citation sync completed",
                        "Citation update completed.",
                        t.getExecutionDate(),
                        null
                )));

        // New citations since last visit
        if (since != null && userProfile != null) {
            userPublicationFacade.buildUserPublicationsView(email)
                    .ifPresent(vm -> vm.publications().stream()
                            .filter(p -> p.getUpdatedAt() != null && p.getUpdatedAt().isAfter(since)
                                    && p.getCitedbyCount() > 0)
                            .forEach(p -> notifications.add(new WorkspaceNotification(
                                    "new-citation-" + p.getId(),
                                    NotificationType.NEW_CITATION,
                                    "Publication updated",
                                    "\"" + p.getTitle() + "\" has new citations since your last visit.",
                                    since.toString(),
                                    "/user/publications/citations?id=" + p.getId()
                            ))));
        }

        // Available reports
        int reportCount = userReportFacade.buildIndividualReportsListView(email)
                .individualReports().size();
        if (reportCount > 0) {
            notifications.add(new WorkspaceNotification(
                    "reports-available-" + reportCount,
                    NotificationType.REPORT_AVAILABLE,
                    "Individual reports available",
                    reportCount + " individual report(s) are available for your review.",
                    null,
                    "/user/evaluation"
            ));
        }

        // Supervisor nudges (persisted, addressed to this researcher). Surfaced in the same bell;
        // dismissal rides the shared dismissed-id filter below.
        nudgeService.activeFor(email).forEach(n -> notifications.add(new WorkspaceNotification(
                "nudge-" + n.getId(),
                NotificationType.NUDGE,
                nudgeTitle(n.getKind()),
                nudgeBody(n),
                n.getCreatedAt() != null ? n.getCreatedAt().toString() : null,
                nudgeActionUrl(n.getKind())
        )));

        if (!dismissed.isEmpty()) {
            notifications.removeIf(n -> dismissed.contains(n.id()));
        }
        return notifications;
    }

    private static String nudgeTitle(ro.uvt.pokedex.core.model.notification.DirectedNotification.NudgeKind kind) {
        return switch (kind) {
            case ONBOARD -> "Finish setting up your profile";
            case CONFIRM_PUBLICATIONS -> "Confirm your publications";
            case REFRESH_REPORT -> "Refresh your evaluation report";
        };
    }

    private static String nudgeBody(ro.uvt.pokedex.core.model.notification.DirectedNotification n) {
        String base = switch (n.getKind()) {
            case ONBOARD -> "Your supervisor asks you to complete onboarding so your work can be scored.";
            case CONFIRM_PUBLICATIONS -> "Your supervisor asks you to review and confirm your pending publications.";
            case REFRESH_REPORT -> "Your supervisor asks you to refresh your report so the latest numbers show.";
        };
        return (n.getNote() != null && !n.getNote().isBlank()) ? base + " — “" + n.getNote() + "”" : base;
    }

    private static String nudgeActionUrl(ro.uvt.pokedex.core.model.notification.DirectedNotification.NudgeKind kind) {
        return switch (kind) {
            case ONBOARD -> "/user/workspace";
            case CONFIRM_PUBLICATIONS -> "/user/workspace#publications";
            case REFRESH_REPORT -> "/user/evaluation";
        };
    }

    private WorkspacePreferences loadOrCreatePreferences(String userEmail) {
        return workspacePreferencesRepository.findById(userEmail)
                .orElseGet(() -> {
                    WorkspacePreferences p = new WorkspacePreferences();
                    p.setUserEmail(userEmail);
                    return p;
                });
    }

    private void updateLastVisit(String userEmail) {
        WorkspacePreferences prefs = loadOrCreatePreferences(userEmail);
        prefs.setLastVisitAt(Instant.now());
        workspacePreferencesRepository.save(prefs);
    }

    private UserPublicationsViewModel emptyPublicationsViewModel() {
        return new UserPublicationsViewModel(
                List.of(), 0, Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, null, List.of(),
                new ro.uvt.pokedex.core.service.application.HIndexCalculator.HIndexBreakdown(0, 0, 0, 0));
    }

    record WorkspacePreferencesRequest(List<String> cardOrder) {}
    record AuthorshipDecisionRequest(String reason) {}
    record BulkAuthorshipDecisionRequest(List<String> publicationIds,
                                         BulkAuthorshipAction action,
                                         String reason) {}
    record AuthorshipDecisionResponse(String publicationId,
                                      PublicationAuthorshipReviewState.Status status,
                                      String reason,
                                      Instant updatedAt) {}
    record BulkAuthorshipDecisionFailure(String publicationId,
                                         String message) {}
    record BulkAuthorshipDecisionResponse(List<String> succeededIds,
                                          List<BulkAuthorshipDecisionFailure> failures,
                                          List<AuthorshipDecisionResponse> updatedStates) {}
    enum BulkAuthorshipAction {
        CONFIRM,
        REJECT
    }

    record NotificationDismissRequest(String id) {}

    record ActivityInstanceCreateRequest(
            String activityId,
            String name,
            String date,
            Map<String, String> fields,
            Map<String, String> referenceFields) {}

    record ActivityInstanceUpdateRequest(
            String id,
            Map<String, String> fields,
            Map<String, String> referenceFields) {}

    /**
     * H70: the authorship-decision endpoints hit the affiliation-confirmation gate before onboarding is done.
     * Rather than a 500, return a structured 409 the claim UI uses to route the researcher into the wizard.
     */
    @ExceptionHandler(ro.uvt.pokedex.core.service.application.AffiliationConfirmationRequiredException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> handleAffiliationConfirmationRequired(
            ro.uvt.pokedex.core.service.application.AffiliationConfirmationRequiredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("requiresOnboarding", true, "message", ex.getMessage()));
    }

    record WorkspaceProfileViewModel(
            User.ResearcherProfile researcher,
            int completeness,
            List<ScopusPublicationUpdate> pubTasks,
            List<ScopusCitationsUpdate> citeTasks,
            List<ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate> openAlexTasks,
            List<ScholardexAffiliationView> observedAffiliations,
            boolean affiliationConfirmationRequired,
            ro.uvt.pokedex.core.service.application.onboarding.ResearcherOnboardingService.OnboardingStatus onboarding) {}

    record ProfileSaveRequest(
            String firstName,
            String lastName,
            Integer phdAwardYear,
            String scholarId,
            List<String> scopusId,
            List<String> wosId,
            String orcid,
            List<String> currentAffiliationIds,
            List<String> pastAffiliationIds,
            Boolean confirmAffiliationScope) {}

    record AuthorMatchRequest(
        List<String> confirmedAuthorIds,
        String primaryAuthorId) {}

    record ClaimApplyRequest(Boolean confirmRecommended) {}

    record SyncRequest(
        String scopusId,
        /** "SINCE_LAST_UPDATE" | "PERIOD" | "FULL"; null → SINCE_LAST_UPDATE */
        String syncMode,
        Integer startYear,
        Integer endYear
    ) {}
}
