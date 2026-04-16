package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.workspace.WorkspacePreferences;
import ro.uvt.pokedex.core.repository.WorkspacePreferencesRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.service.application.PublicationWizardFacade;
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
    private final UserReportFacade userReportFacade;
    private final WorkspacePreferencesRepository workspacePreferencesRepository;
    private final PublicationWizardFacade publicationWizardFacade;

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
                userPublicationFacade.buildUserPublicationsView(u.getEmail())
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
            // Always return a Researcher object — even an empty stub — so the JS
            // form renders for new users who haven't saved their profile yet.
            Researcher researcher = Researcher.fromUser(freshUser);
            if (researcher == null) {
                researcher = new Researcher();   // empty stub; id is not set
            }
            int completeness = computeProfileCompleteness(researcher);
            var tasksVm = userScopusTaskFacade.buildTasksView(freshUser.getEmail(), freshUser.getEmail());
            return ResponseEntity.ok(new WorkspaceProfileViewModel(
                    researcher, completeness,
                    tasksVm.tasks(), tasksVm.citationsTasks()
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
        profile.setScholarId(request.scholarId() != null ? request.scholarId().trim() : null);
        profile.setScopusId(request.scopusId() != null
                ? new ArrayList<>(request.scopusId()) : new ArrayList<>());
        profile.setWosId(request.wosId() != null
                ? new ArrayList<>(request.wosId()) : new ArrayList<>());
        user.setResearcherProfile(profile);
        if (isNewProfile) {
            // Grant RESEARCHER role on first profile creation so the user gains
            // researcher-level access without needing an admin to intervene.
            user.getRoles().add(ro.uvt.pokedex.core.model.user.UserRole.RESEARCHER);
        }
        userRepository.save(user);
        return ResponseEntity.ok().build();
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
        ScopusCitationsUpdate saved =
                userScopusTaskFacade.createCitationTask(userOpt.get().getEmail(), draft);
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
        return userPublicationFacade.buildCitationsView(id)
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
        Optional<Researcher> researcherOpt = Optional.ofNullable(Researcher.fromUser(currentUser));

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

        String researcherName = researcherOpt.map(Researcher::getName).orElse(null);
        boolean hasProfile = researcherOpt.isPresent();
        int completeness = computeProfileCompleteness(researcherOpt.orElse(null));

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

        WorkspaceState state = determineWorkspaceState(
                researcherOpt.orElse(null), email, availableReportCount);

        // ── Chart data: publications and citations per year ──────────────
        List<String> chartYears = new ArrayList<>();
        List<Integer> chartPubs = new ArrayList<>();
        List<Integer> chartCites = new ArrayList<>();
        if (pubsOpt.isPresent()) {
            var byYear = new TreeMap<String, int[]>();
            for (var p : pubsOpt.get().publications()) {
                String date = p.getCoverDate();
                if (date == null || date.length() < 4) continue;
                String year = date.substring(0, 4);
                byYear.computeIfAbsent(year, k -> new int[]{0, 0});
                byYear.get(year)[0]++;
                byYear.get(year)[1] += p.getCitedbyCount();
            }
            for (var entry : byYear.entrySet()) {
                chartYears.add(entry.getKey());
                chartPubs.add(entry.getValue()[0]);
                chartCites.add(entry.getValue()[1]);
            }
        }
        var overviewCharts = new ResearcherWorkspaceViewModel.OverviewCharts(
                chartYears, chartPubs, chartCites,
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
            Researcher researcher, String researcherId, int availableReportCount) {
        if (researcherId == null || researcher == null) {
            return WorkspaceState.NEW_USER;
        }
        boolean hasScopus = researcher.getScopusId() != null && !researcher.getScopusId().isEmpty();
        boolean hasWos = researcher.getWosId() != null && !researcher.getWosId().isEmpty();
        boolean hasPrimaryAuthor = researcher.getPrimaryScholardexAuthorId() != null
                && !researcher.getPrimaryScholardexAuthorId().isBlank();
        if (!hasScopus && !hasWos && !hasPrimaryAuthor) {
            return WorkspaceState.INCOMPLETE_PROFILE;
        }
        if (availableReportCount > 0) {
            return WorkspaceState.REPORTING_SEASON;
        }
        return WorkspaceState.ACTIVE;
    }

    private int computeProfileCompleteness(Researcher researcher) {
        if (researcher == null) {
            return 0;
        }
        int score = 0;
        if (researcher.getFirstName() != null && !researcher.getFirstName().isBlank()) score += 25;
        if (researcher.getLastName() != null && !researcher.getLastName().isBlank()) score += 25;
        if (researcher.getScopusId() != null && !researcher.getScopusId().isEmpty()) score += 25;
        if (researcher.getWosId() != null && !researcher.getWosId().isEmpty()) score += 25;
        return score;
    }

    private List<WorkspaceSearchResult> performSearch(String q, String userEmail) {
        List<WorkspaceSearchResult> results = new ArrayList<>();
        if (userEmail == null) return results;

        final String qLower = q.toLowerCase();

        // Load the researcher's own publications once; reuse for pubs + citations
        var pubsOpt = userPublicationFacade.buildUserPublicationsView(userEmail);

        // Publications — researcher-scoped, title match, not yet cited, cap at 10
        pubsOpt.ifPresent(vm -> vm.publications().stream()
                .filter(p -> p.getTitle() != null
                        && p.getTitle().toLowerCase().contains(qLower)
                        && p.getCitedbyCount() == 0)
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

        // Citations — researcher's publications that have been cited, title match, cap at 10
        pubsOpt.ifPresent(vm -> vm.publications().stream()
                .filter(p -> p.getTitle() != null
                        && p.getTitle().toLowerCase().contains(qLower)
                        && p.getCitedbyCount() > 0)
                .limit(10)
                .map(p -> new WorkspaceSearchResult(
                        p.getId(),
                        EntityType.CITATION,
                        p.getTitle(),
                        p.getCoverDate(),
                        "/user/publications/citations?id=" + p.getId()))
                .forEach(results::add));

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
        Researcher researcher = Researcher.fromUser(user);
        WorkspaceState state = determineWorkspaceState(researcher, researcher != null ? email : null, 0);
        if (state == WorkspaceState.NEW_USER || state == WorkspaceState.INCOMPLETE_PROFILE) {
            notifications.add(new WorkspaceNotification(
                    "profile-incomplete",
                    NotificationType.PROFILE_INCOMPLETE,
                    "Profile incomplete",
                    "Add your Scopus IDs and WoS IDs to improve publication detection.",
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
        if (since != null && researcher != null) {
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
                    "/user/individual-reports"
            ));
        }

        if (!dismissed.isEmpty()) {
            notifications.removeIf(n -> dismissed.contains(n.id()));
        }
        return notifications;
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
                List.of(), 0, Map.of(), Map.of(), 0, null, List.of());
    }

    record WorkspacePreferencesRequest(List<String> cardOrder) {}

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

    record WorkspaceProfileViewModel(
            Researcher researcher,
            int completeness,
            List<ScopusPublicationUpdate> pubTasks,
            List<ScopusCitationsUpdate> citeTasks) {}

    record ProfileSaveRequest(
            String firstName,
            String lastName,
            String scholarId,
            List<String> scopusId,
            List<String> wosId) {}

    record SyncRequest(String scopusId) {}
}
