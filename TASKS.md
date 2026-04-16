# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H36` Researcher Workspace — adaptive research hub consolidating dashboard, profile, publications, and activities into a single intelligent workspace with master-detail interaction, unified search, notification center, and inline workflows.
  Goal: replace four fragmented user pages (`user/dashboard`, `user/profile`, `user/publications`, `user/activities`) plus their sub-pages (`user/citations`, `user/tasks`, `user/publications-edit`, `user/activities-edit`) with one integrated workspace at `/user/workspace` that serves as the researcher's complete home base — adaptive overview, tabbed data views with inline detail panels, cross-entity search, change notifications, inline publication creation, and personalizable layout.
  Design reference: `docs/tasks/active/ux-redesign-plan.md` §1.1 Option C.
  UX guide reference: `docs/ux-design-guide.md` §1.2, §4.3, §6.2, §6.3, §6.6, §6.7, §7.1, §7.2, §7.4, §8.1, §8.2, §8.3.
  Exit criteria: researcher lands on a single adaptive workspace after login; overview adapts to researcher state (new user → onboarding, active → recent changes, reporting season → readiness status); all data previously spread across dashboard/profile/publications/activities is reachable via tabs without full-page navigation; master-detail works for publications and activities; unified search spans publications, activities, and citations; notification center shows changes since last visit; publication creation wizard runs inline without leaving the workspace; overview card order is user-customizable and persisted; skeleton loading on all async content; keyboard shortcuts for tab navigation; old URLs redirect to the workspace with the correct tab active; sidebar collapses from 4+ items to 1; all work passes `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`.

  Subtasks:

  - [x] `H36.1` **Shared tab-bar component.**
    Build a reusable tab-bar fragment in `fragments.html` and a supporting JS module in `frontend/src/modules/shared/`. Must support: labeled tabs, URL hash state preservation (`#publications`, `#activities`, etc.), lazy-load callback per tab, active-tab visual state, keyboard navigation (arrow keys between tabs, Enter to activate), ARIA `role="tablist"/"tab"/"tabpanel"` and `aria-selected` attributes, smooth transitions per §8.1, keyboard shortcuts (e.g. `Ctrl+1`..`Ctrl+4` to jump to tab by index). Both themes. No jQuery dependency.
    Exit criteria: fragment renders a functional tab bar in any template; hash changes on tab switch; browser back/forward navigates tabs; keyboard shortcuts work; focus ring visible in both themes; `npm run build` passes.
    Completed: 2026-04-05.
    Handover:
    - `frontend/src/styles/shared-tabs.css` — BEM `.app-tab-bar` block; two-phase transition classes (`--leaving` 150ms / `--active` 200ms); `:focus-visible` focus ring using `--app-color-focus`; horizontal-scroll responsive behaviour. Both themes handled entirely through CSS variables.
    - `frontend/src/modules/shared/workspaceTabs.js` — `initWorkspaceTabs()` export; roving-tabindex arrow-key navigation (manual activation); `history.pushState` / `popstate` for hash nav; lazy-load callbacks via `window[data-app-tab-bar-callbacks]` fired once per tab; `Ctrl+1`..`Ctrl+4` shortcuts guarded against input focus; `window.appWorkspaceTabs.activateTab(hash)` public API.
    - `fragments.html` — `tab-bar(tabs, callbacksRef)` fragment added; `TabDef` shape: `.id`, `.label`, `.iconClass`. Panels are empty shells — content is JS-populated via the callback map.
    - `app.js` — CSS import and `initWorkspaceTabs()` call added.
    - All three verification scripts passed: `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`.

  - [x] `H36.2` **Workspace controller, aggregated view model, and JSON API layer.**
    Create a `ResearcherWorkspaceController` (or extend `UserViewController`) with:
    — `GET /user/workspace` — serves the workspace page with eagerly-loaded overview data (stat cards, recent activity, notification summary, profile completeness).
    — `GET /user/workspace/publications` — JSON endpoint for lazy-loaded publication list with citations and author mappings.
    — `GET /user/workspace/activities` — JSON endpoint for lazy-loaded activity instances with chart data.
    — `GET /user/workspace/search?q=…` — JSON endpoint for unified cross-entity search (publications, activities, citations).
    — `GET /user/workspace/notifications` — JSON endpoint returning changes since the researcher's last visit (new citations found, sync completions, report availability).
    — `POST /user/workspace/preferences` — JSON endpoint to persist overview card ordering (stored per-user, e.g. in a simple key-value table or researcher metadata).
    Build a `ResearcherWorkspaceViewModel` that bundles overview tab data eagerly; other tabs lazy-load via the JSON endpoints.
    Aggregates from existing facades: `UserPublicationFacade`, `UserActivityInstanceFacade`, `ResearcherService`, `UserScopusTaskFacade`. Does not modify existing services.
    Dependency: none (uses existing facades).
    Exit criteria: `/user/workspace` returns a model with overview data; all JSON endpoints respond correctly; search returns results across publications, activities, and citations; notification endpoint returns change list; preferences endpoint persists and retrieves card order; existing services are not modified.
    Completed: 2026-04-05.
    Handover:
    - `model/workspace/WorkspacePreferences.java` — MongoDB doc (`scholardex.workspacePreferences`) keyed by `userEmail`; stores `overviewCardOrder` (List<String>) and `lastVisitAt` (Instant).
    - `repository/WorkspacePreferencesRepository.java` — Spring Data Mongo repo; `findById` / `save` are the only operations used.
    - `service/application/model/TabDef.java` — reusable tab descriptor record (`id`, `label`, `iconClass`, `defaultTab`).
    - `service/application/model/WorkspaceNotification.java` — notification DTO with four `NotificationType` values: `NEW_CITATION`, `SYNC_COMPLETED`, `REPORT_AVAILABLE`, `PROFILE_INCOMPLETE`.
    - `service/application/model/WorkspaceSearchResult.java` — search result DTO with `EntityType` (`PUBLICATION`, `ACTIVITY`, `CITATION`).
    - `service/application/model/ResearcherWorkspaceViewModel.java` — overview view model record; nested `RecentActivityItem` and `WorkspaceState` enum (`NEW_USER`, `INCOMPLETE_PROFILE`, `REPORTING_SEASON`, `ACTIVE`).
    - `view/user/ResearcherWorkspaceController.java` — `@Controller @RequestMapping("/user/workspace")`; 6 endpoints (1 MVC + 5 `@ResponseBody` JSON); aggregates from all four facades; no existing services modified. `lastVisitAt` updated after building the view model so the notification count reflects the previous visit.
    - `templates/user/workspace.html` — 4-tab workspace page; overview panel server-rendered with stat cards and adaptive state blocks; publications/activities/profile panels are empty lazy-load shells with `data-workspace-lazy-panel` + `data-src` hooks for H36.3/H36.7/H36.8/H36.10.
    - `gradlew compileJava` passes clean.

  - [x] `H36.3` **Skeleton loading system and smooth tab transitions.** *(completed 2026-04-05)*
    - `frontend/src/styles/shared-skeleton.css` — `.app-skeleton-block` pulse animation; `.app-skeleton-table` (header bar + rows with width-variant cells); `.app-skeleton-chart` (180px fill block); `.app-panel-error` wrapper. All colours use existing CSS custom properties — automatic light/dark support.
    - `frontend/src/modules/shared/workspacePanelLoader.js` — `initWorkspacePanelLoader()` exported; document-level event delegation for `[data-retry-panel]` retry clicks; `_loadPanel(panel)` shows skeleton immediately, fetches `data-src`, replaces with minimal content or error+retry block; `window.appWorkspacePanelLoader = { loadPanel }` public API for callback wiring.
    - `frontend/src/app.js` — added `shared-skeleton.css` import and `initWorkspacePanelLoader()` call.
    - `templates/user/workspace.html` — replaced stub callbacks with `window.appWorkspacePanelLoader?.loadPanel(panel)` for publications and activities; profile stub preserved for H36.10.
    - `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.4` **Adaptive overview tab.** *(completed 2026-04-05)*
    - `ResearcherWorkspaceViewModel` — added `OverviewCharts` nested record (years, pubsPerYear, citesPerYear, activityLabels, activityCounts) as field 15.
    - `ResearcherWorkspaceController` — computes per-year publication and citation counts via `TreeMap` from `UserPublicationsViewModel.publications()`, reads activityLabels/activityData from `UserActivityInstancesViewModel`, passes `OverviewCharts` to the ViewModel constructor.
    - `frontend/src/styles/shared-dashboard.css` — appended: `.app-overview-panels` flex column, `.app-drag-handle` grab cursor, `.app-overview-panel--dragging/--drag-over` states, `.app-onboarding-card/progress/list` onboarding components, `.app-charts-row` two-column mini-chart grid, `.app-mini-chart` 160px height container.
    - `frontend/src/modules/workspace/workspaceOverview.js` — new module; `initWorkspaceOverview()` applies saved card order, initialises Chart.js v2 bar chart (publications per year) and doughnut chart (activity distribution) using resolved CSS custom property colours (theme-aware), sets up native HTML5 drag-and-drop with mousedown-guard so drag only activates from `.app-drag-handle`, persists order via `POST /user/workspace/preferences`.
    - `frontend/src/app.js` — added import and `initWorkspaceOverview()` call.
    - `templates/user/workspace.html` — overview panel fully rewritten: NEW_USER shows onboarding checklist + progress bar; INCOMPLETE_PROFILE shows attention banner + progress bar + draggable stat-grid; ACTIVE/REPORTING_SEASON shows identity card, stat-grid, charts, quick-actions, recent-activity (all draggable); REPORTING_SEASON adds report-readiness panel. Inline JS block extended with `window.wsOverviewCardOrder` and `window.wsOverviewChartData`.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.5` **Unified cross-entity search.** *(completed 2026-04-05)*
    - `frontend/src/styles/shared-search.css` — `.app-ws-search` combobox widget; `.app-ws-search__field` focus ring; `.app-ws-search__hint` kbd shortcut hint (hidden when focused); `.app-ws-search__results` dropdown with max-height + scroll; `.app-ws-search__group-header` section labels; `.app-ws-search__result` rows with hover + `--active` highlight; `.app-ws-search__badge--pub/act/cite` coloured entity tags; `.app-ws-search__live` screen-reader region.
    - `frontend/src/modules/workspace/workspaceSearch.js` — `initWorkspaceSearch()` exported; global `keydown` handler for `/` and `Ctrl+K` (guards against active text fields); 300ms debounced input → `GET /user/workspace/search?q=`; results grouped by `entityType` (PUBLICATION → publications tab, ACTIVITY → activities tab, CITATION → publications tab); ArrowDown/Up navigation with DOM-index tracking, Enter selects, Escape closes; `aria-expanded` + `aria-activedescendant` + `aria-live` announcements; `window.appWorkspaceTabs?.activateTab()` for tab switching; cross-page navigation (`result.url` not starting with `/user/workspace`) after 80ms delay.
    - `templates/user/workspace.html` — search widget inserted above `[data-app-tab-bar]` inside `.app-dashboard`; `role="combobox"` on input, `role="listbox"` on results `<ul>`, `aria-haspopup/controls/autocomplete` wired correctly.
    - `frontend/src/app.js` — added `shared-search.css` import and `initWorkspaceSearch()` call.
    - `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.6` **Notification center.** *(completed 2026-04-05)*
    - `WorkspacePreferences` — added `dismissedNotificationIds: List<String>` field (MongoDB document); default empty list.
    - `ResearcherWorkspaceController` — `buildNotifications(user, since, dismissed)` now accepts a `Set<String>` and filters out dismissed IDs at the end; added `dismissedSet(prefs)` helper; `buildWorkspaceViewModel` and `getNotifications()` both pass the dismissed set; added `POST /user/workspace/notifications/mark-read` (sets `lastVisitAt=now`, clears dismissed list); added `POST /user/workspace/notifications/dismiss` (appends ID to dismissed list); added `NotificationDismissRequest` record.
    - `frontend/src/styles/shared-notifications.css` — `.app-ws-header` flex row (overrides search margin); `.app-ws-notif__bell` icon button with focus ring and `[aria-expanded='true']` state; `.app-ws-notif__badge` red pill badge (top-right, 99+ cap); `.app-ws-notif__panel` right-aligned dropdown with shadow; panel header (title + "Mark all as read" link); scrollable `.app-ws-notif__list`; `.app-ws-notif__item` rows with per-type icon modifiers (citation/sync/report/profile); dismiss X button visible on row hover; `notif-dismiss` keyframe for slide-out; empty/loading/error states; `.app-ws-notif__live` clipped live region.
    - `frontend/src/modules/workspace/workspaceNotifications.js` — `initWorkspaceNotifications()` exported; bell toggles panel; lazy-fetches `GET /user/workspace/notifications` on first open; renders grouped list with type icons, body text, relative timestamps (just now / N minutes/hours/days ago), "View →" action links; dismiss POST with 230ms slide-out animation and badge decrement; mark-all-read POST clears badge and replaces list with empty state; Escape key closes panel and returns focus to bell; `aria-expanded` + `aria-live` region maintained.
    - `templates/user/workspace.html` — search bar and bell wrapped in `.app-ws-header`; bell has server-rendered `aria-label` and badge from `workspace.unreadNotificationCount`; `window.wsNotifCount` added to inline script.
    - `frontend/src/app.js` — added `shared-notifications.css` import and `initWorkspaceNotifications()` call.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.7` **Publications tab with master-detail.** *(completed 2026-04-05)*
    - `ResearcherWorkspaceController` — added `POST /user/workspace/publications/save/{id}` endpoint (`@ResponseBody`, accepts `@RequestBody PublicationMetadataPatch`) that delegates to `userPublicationFacade.updatePublicationMetadata(id, patch)` and returns `200 OK`; added `PublicationMetadataPatch` import.
    - `frontend/src/styles/workspace-publications.css` — `.app-ws-pubs` container; `.app-ws-pubs__toolbar` action bar; `.app-ws-pubs__stats` stat strip with `--success`/`--warning` modifiers; `.app-ws-pubs__table-wrap` + `.app-ws-pubs__table` (no vertical borders, thead styled as label row, row hover with primary-tinted bg); `.app-ws-pubs__row--active` highlighted state; `.app-ws-pubs__type-badge` with `--article/--conference/--review/--book` modifiers; `.app-ws-pubs__action-btn` compact icon button; `.app-ws-pubs__detail-row` + `.app-ws-pubs__detail-panel` two-column grid (responsive to 1-col on narrow screens); citations sub-list + edit form inside detail; save feedback span; `.app-ws-pubs__pagination` bottom strip with page buttons (active + disabled states); `.app-ws-pubs__empty` centered empty state.
    - `frontend/src/modules/workspace/workspacePublications.js` — `initWorkspacePublications()` exported; registers `window.appWorkspacePublications = { init }`; `_init(panel)` shows skeleton then fetches `data-src`; `_renderAll()` inserts toolbar + stats + table-wrap, adds Scopus-Updates button click listener (`appWorkspaceTabs.activateTab('profile')`), registers Escape key handler; `_renderPage()` renders current page of rows into `<tbody>`, appends pagination strip; row click → `_toggleDetail()` (one-at-a-time, close on second click); `_insertDetailRow(pub, tr)` creates `<tr>` spanning all 6 cols with citation preview (up to 5 citing IDs resolved against loaded publications) + `<a href=/user/publications/citations?id=…>` link + edit form (subtype `<select>` + subtypeDescription `<input>`); save button → `_savePub()` POSTs JSON to `/user/workspace/publications/save/{id}`, updates in-memory data, refreshes row badge, shows inline feedback; Escape handler closes detail + returns focus to action button; pagination shows prev/page-numbers/next, page clicks re-render; empty state with CTA; error block with retry.
    - `templates/user/workspace.html` — publications callback changed from `appWorkspacePanelLoader.loadPanel` to `appWorkspacePublications.init`.
    - `frontend/src/app.js` — added `workspace-publications.css` import and `initWorkspacePublications()` call.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.8` **Activities tab with master-detail.** *(completed 2026-04-05)*
    - `ResearcherWorkspaceController` — added `POST /user/workspace/activities/create` (builds `ActivityInstance` from `ActivityInstanceCreateRequest`, maps `Map<String,String> referenceFields` to `EnumMap<Activity.ReferenceField,String>`, delegates to `saveActivityInstance`, returns saved instance as JSON); `POST /user/workspace/activities/update` (`ActivityInstanceUpdateRequest` → same enum conversion → `updateActivityInstance`); `POST /user/workspace/activities/delete/{id}` → `deleteActivityInstance`; added `Activity` import and `EnumMap` import; added two request records.
    - `frontend/src/styles/workspace-activities.css` — `.app-ws-acts` container; toolbar; stats strip (`--primary` variant); chart card (13rem fixed width, 7.5rem canvas + empty ring fallback); create panel with header, dynamic field grid, feedback span; shared field/input/select styles; table (no vertical borders, row hover, `--active` state, type badge); compact action buttons (`--danger` hover variant); detail row with 2-col grid (info + edit); two-click delete confirmation with inline warning strip; pagination; empty state.
    - `frontend/src/modules/workspace/workspaceActivities.js` — `initWorkspaceActivities()` exported; registers `window.appWorkspaceActivities = { init }`; skeleton + fetch lifecycle; Chart.js v2 doughnut with CSS-custom-property colours (cycles palette if more types than 5); stats strip with live `id="ws-acts-stat-count"` updated after create/delete; paginated table (20/page); row click → `_toggleDetail` (one-at-a-time); detail shows activity info + editable fields (custom fields as input/select based on `allowedValues`, reference fields as text inputs with human-readable labels); Save → `POST /user/workspace/activities/update`; Delete uses two-click confirmation (4s reset timer) → `POST /user/workspace/activities/delete/{id}` → removes from in-memory list, re-renders; "Add Activity" toggles inline create panel above table; activity type `<select>` → resolves fields from `_data.activities` (local first) or fetches `GET /user/activities/activity/{id}/fields`; create Save → `POST /user/workspace/activities/create` → prepends new instance, re-renders; Escape closes create panel first, then detail; retry button wired.
    - `templates/user/workspace.html` — activities callback changed from `appWorkspacePanelLoader.loadPanel` to `appWorkspaceActivities.init`.
    - `frontend/src/app.js` — added `workspace-activities.css` import and `initWorkspaceActivities()` call.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.9` **Inline publication creation wizard.** *(completed 2026-04-15)*
    - `ResearcherWorkspaceController` — added `PublicationWizardFacade` dependency; `GET /user/workspace/publications/wizard-authors?afid={afid}` returns `List<ScholardexAuthorView>` (delegates to `findAuthorsForAffiliation`); `POST /user/workspace/publications/wizard` accepts `WizardPublicationCommand`, sets `creator` server-side from authenticated user, delegates to `submitPublication`, returns `{sourceRecordId, eid}` on success or `{error}` on 400.
    - `frontend/src/styles/workspace-publications.css` — appended: `.app-ws-pubs__wizard` panel shell with header/close/body; `.app-ws-pubs__wizard-steps/step/step-dot` step indicator (active, done, pending states with connector lines); `.app-ws-pubs__wiz-search-input` forum search; `.app-ws-pubs__wiz-forum-list/item/item--selected/name/meta` forum results; `.app-ws-pubs__wiz-new-forum` details disclosure with field grid inside; `.app-ws-pubs__wiz-author-cols/col-title/author-list/author-item` two-column author staging; `.app-ws-pubs__wiz-fields/field/field--full/label/label--required/input/select` metadata grid; `.app-ws-pubs__wiz-nav/feedback` navigation strip; `.app-ws-pubs__wiz-loading/empty-authors` states.
    - `frontend/src/modules/workspace/workspacePublications.js` — added wizard state (`_wizardOpen`, `_wizardStep`, `_wForumId`, `_wNewForum`, `_wForumFilter`, `_wAuthorIds`, `_wAuthors`, `_wAuthorsLoading`, metadata fields); changed "Add Publication" link href to `/user/publications/add` (progressive fallback) with `id="ws-pubs-add-btn"` and JS click intercept; wizard placeholder `#ws-pubs-wizard` inserted between stats and table-wrap; `_openWizard()` resets state and renders panel; `_closeWizard(force)` dirty-checks with confirm; `_renderStep1()` client-side filters `_data.forumMap` (max 20), click-selects, `<details>` for new-forum creation; `_renderStep2()` fetches authors lazily via `wizard-authors?afid=` using `_data.affiliations[0].afid`, two-column add/remove staging; `_renderStep3()` metadata fields grid; `_wizardNext()` validates step 1 (forum or new-forum required) and advances; `_captureStep3()` + `_validateStep3()` validate title/date/subtypeDescription; `_submitWizard()` POSTs JSON command, on success calls `_init(_panel)` to reload; `_handleEscape` updated to close wizard before detail panel.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.10` **Profile & Sync tab.** *(completed 2026-04-15)*
    - `frontend/src/styles/workspace-profile.css` — full BEM stylesheet (`.app-ws-prof`): completeness card + progress bar + checklist, section cards (header/body), readonly info grid + ID pills, inline edit form with dynamic ID entry rows, sync section with sync-id-rows, status badges (`--pending/completed/failed/muted`), task history tables, no-profile state.
    - `frontend/src/modules/workspace/workspaceProfile.js` — `initWorkspaceProfile()` exports `window.appWorkspaceProfile.init(panel)`; fetches `GET /user/workspace/profile`; renders completeness card, identity section (readonly + inline edit toggle), and Scopus sync section with task history tables; `POST /user/workspace/profile/save` on save; `POST /user/workspace/profile/sync/{publications,citations}` on sync trigger with optimistic row prepend; no-profile state when researcher is null.
    - `frontend/src/app.js` — added `workspace-profile.css` import and `initWorkspaceProfile()` call.
    - `src/main/resources/templates/user/workspace.html` — added `data-src="/user/workspace/profile"` to `#ws-profile-mount`; updated profile callback to `window.appWorkspaceProfile?.init(panel)`.
    - `ResearcherWorkspaceController.java` — 4 new endpoints: `GET /profile`, `POST /profile/save`, `POST /profile/sync/publications`, `POST /profile/sync/citations`; inner records `WorkspaceProfileViewModel`, `ProfileSaveRequest`, `SyncRequest`.
    - `gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.11` **Keyboard shortcuts and navigation.** *(completed 2026-04-15)*
    - `frontend/src/styles/shared-shortcuts.css` — cheat sheet overlay styles (`app-shortcuts-overlay`, dialog, header, section, row, `app-shortcuts-kbd`) + `app-row--kb-focused` indicator for focused table rows.
    - `frontend/src/modules/shared/workspaceShortcuts.js` — `initWorkspaceShortcuts()`: builds + appends cheat sheet overlay; capture-phase `?` handler (toggle overlay, skips inputs) and Escape handler (closes overlay with `stopPropagation`); bubble-phase table nav handler (ArrowUp/Down moves focus between data rows, Enter clicks row); MutationObserver on workspace root watches for `#ws-pubs-tbody` / `#ws-acts-tbody` to appear and then observes each for row changes, maintaining roving `tabindex` (first row = 0, rest = -1) with focus/blur class management.
    - `frontend/src/app.js` — added `shared-shortcuts.css` import and `initWorkspaceShortcuts()` call (after `initWorkspaceTabs`).
    - Ctrl+1..4, /, Ctrl+K, and module-level Escape already handled by existing modules; all listed in cheat sheet for discoverability.
    - `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H36.12` **Sidebar navigation update and URL redirects.** *(completed 2026-04-15)*
    - `fragments.html` — workspace switcher link updated from `/user/dashboard` to `/user/workspace`; `user-sidebar` Workspace section collapsed from 3 items (Profile, Publications, Activities) to a single "My Workspace" entry (`fa-house-user` icon, `th:href="@{/user/workspace}"`, active when `activeSection == 'workspace'`).
    - `UserViewController.java` — `GET /user` and `GET /user/dashboard` → `redirect:/user/workspace`; `GET /user/profile` → `redirect:/user/workspace#profile`; `GET /user/publications` → `redirect:/user/workspace#publications`; `GET /user/publications/scopus-tasks` → `redirect:/user/workspace#profile`; `GET /user/publications/citations` → `redirect:/user/workspace#publications`; `/user/authors/view/{id}` preserved as standalone page.
    - `ActivityInstanceController.java` — `GET /user/activities` → `redirect:/user/workspace#activities`.
    - `scripts/verify-route-guardrails.js` — required marker updated from `href="/user/dashboard"` to `href="/user/workspace"`.
    - `./gradlew compileJava`, `npm run build`, `npm run verify-route-guardrails` all pass clean.

  - [x] `H36.13` **Responsive behavior and accessibility audit.** *(completed 2026-04-15)*
    - `workspaceTabs.js` — added `role="status" aria-live="polite"` live region injected into every tab bar; updates with `"{Label} tab"` on each activation so screen readers announce the switch.
    - `workspacePublications.js` / `workspaceActivities.js` — `aria-busy="true"` set on mount during skeleton phase, removed when panel renders; detail-row toggle buttons get `aria-expanded="false/true"` updated on open/close; `_closeDetail()` returns focus to the trigger button when focus was inside the detail panel.
    - `workspaceActivities.js` — "Add Activity" button gets `aria-expanded`; `_toggleCreate()` moves focus to first form field on open; `_closeCreate()` returns focus to the Add button.
    - `workspaceOverview.js` — `_initDragDrop()` bails out early on `(pointer: coarse)` devices; drag-and-drop silently degrades to a fixed layout on touch screens.
    - `shared-dashboard.css` — `@media (pointer: coarse)` hides `.app-drag-handle` on touch/stylus devices (paired with JS guard above).
    - `shared-search.css` — `@media (max-width: 576px)` removes the `max-width: 38rem` cap so the search bar goes full-width on mobile.
    - `workspace-publications.css` / `workspace-activities.css` — `@media (max-width: 576px)` adds `overflow-x: auto` to each table-wrap so wide tables scroll horizontally on small screens without breaking the card's border-radius.
    - Pre-existing: tab bar already uses `overflow-x: auto; flex-wrap: nowrap` (scrollable pill row); detail panels already reflow to single column at 640px; notifications panel already has `max-width: calc(100vw - 1.5rem)`.
    - `npm run build`, `verify-route-guardrails` pass clean.

  - [x] `H36.14` **Legacy template cleanup and verification.**
    Completed: 2026-04-15.
    Handover:
    - Deleted 7 dead templates: `user/dashboard.html`, `user/profile.html`, `user/activities.html`, `user/citations.html`, `user/tasks.html`, `user/activities-edit.html`, `user/publications-edit.html`. Kept: `user/publications.html` (used by `/user/authors/view/{id}`), `user/publications-add-step*.html` (used by wizard), `user/workspace.html`.
    - `UserViewController.java` — `GET /user/publications/edit/{eid}` → redirect `/user/workspace#publications`; removed dead `POST /user/publications/save/{eid}` and `POST /user/profile/save` handlers.
    - `ActivityInstanceController.java` — `GET /user/activities/edit/{id}` → redirect `/user/workspace#activities`; removed unused `Model`, `User`, `UserActivityInstancesViewModel` imports.
    - `verify-template-assets.js` — removed `user/citations.html` and `user/profile.html` from `allowlistedInlineScriptFiles`.
    - `verify-route-guardrails.js` — removed file-specific checks for now-deleted `activities-edit.html` and `tasks.html`.
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

- [ ] `H37` Evaluation Workspace — analytical evaluation suite consolidating indicators, apply views, and reports into a single surface with period comparison, what-if analysis, per-criterion score breakdowns, and saved snapshots.
  Goal: replace the fragmented indicator/report pages (`user/indicators`, `user/indicators-apply-publications`, `user/indicators-apply-activities`, `user/indicators-apply-citations`, `user/individual-reports`, `user/individual-report-view`) with one integrated evaluation workspace where researchers can see their report, drill into each criterion's scored data inline, compare results across periods, run what-if scenarios, explore per-criterion contribution charts, and save/bookmark report states for later comparison.
  Design reference: `docs/tasks/active/ux-redesign-plan.md` §1.2 Option C (scoped to period comparison, what-if analysis, per-criterion score breakdown charts, and saved snapshots — PDF export and admin group report alignment deferred).
  UX guide reference: `docs/ux-design-guide.md` §6.2, §6.3, §6.9, §7.5, §8.1, §8.2.
  Exit criteria: researcher reaches the full evaluation workflow from a single entry point; the three `indicators-apply-*` templates are consolidated into one that dynamically renders columns based on `indicator.outputType`; the indicator catalog and reports catalog pages are either eliminated or reduced to selectors within the report view; criterion cards expand inline to show their scored data without page navigation; aggregate score and career-level progress are visible at the top of the report view; period comparison UI shows score deltas between runs; what-if panel allows hypothetical additions and recalculates scores client-side or via a scoring preview endpoint; per-criterion breakdown charts show contribution by publication/activity; snapshots can be saved, named, listed, and compared; Excel export of the full report works; old URLs redirect appropriately; all work passes `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`.

  Subtasks:

  - [x] `H37.1` **Consolidate `indicators-apply-*` templates into a single template.**
    Completed: 2026-04-15.
    Handover:
    - Created `user/indicators-apply.html` — single template driven by `outputMode` model attribute (`"publications"` / `"activities"` / `"citations"`). All three output-type layouts are present as `th:if` branches. Filter controls, sort options, table columns, JS script tag, and `data-*` dashboard ID all vary by `outputMode`. Empty-state messages and chart description IDs are also type-specific.
    - `UserReportFacade.java` — `handlePublications`, `handleActivities`, `handleCitations` each add `attrs.put("outputMode", ...)` and return `"user/indicators-apply"` as the view name.
    - `ReportScopedIndicatorScoringSupport.viewNameFor()` — simplified to always return `"user/indicators-apply"`.
    - Deleted 3 old templates: `indicators-apply-publications.html`, `indicators-apply-activities.html`, `indicators-apply-citations.html`.
    - `verify-datatables-optin.js` — updated `datatablesFreePages` to reference the new consolidated file.
    - `verify-route-guardrails.js` — updated citations-specific check to target `indicators-apply.html`.
    - `UserViewControllerContractTest` — updated all three apply-tests to use `"user/indicators-apply"` view name and include `outputMode` in raw graph; fixed 7 other stale tests whose assertions referenced removed routes or templates.
    - `./gradlew compileJava`, `cleanTest test --tests UserViewControllerContractTest`, `npm run build`, all verify scripts pass clean.

  - [x] `H37.2` **Evaluation workspace controller and JSON API layer.**
    Create an `EvaluationWorkspaceController` (or extend `UserViewController`) providing:
    — `GET /user/evaluation` (or `/user/reports/view/{id}`) — serves the report view with eagerly-loaded report metadata, criterion summaries, and current indicator scores.
    — `GET /user/evaluation/indicator/{id}/detail` — JSON endpoint returning the scored data (publications/activities/citations), filter options, chart data, and total for a given indicator, used by inline criterion expansion.
    — `GET /user/evaluation/compare?runA={id}&runB={id}` — JSON endpoint returning score deltas between two report runs (per indicator, per criterion, and aggregate).
    — `POST /user/evaluation/what-if` — JSON endpoint accepting hypothetical inputs (e.g. add one Q1 publication to indicator X) and returning recalculated scores using the existing scoring strategy (no persistence).
    — `GET /user/evaluation/breakdown/{indicatorId}` — JSON endpoint returning per-item contribution data for the breakdown chart.
    — `POST /user/evaluation/snapshots` / `GET /user/evaluation/snapshots` / `GET /user/evaluation/snapshots/{id}` / `DELETE /user/evaluation/snapshots/{id}` — CRUD endpoints for saved report snapshots (name, timestamp, full score state).
    Aggregates from existing facades/services (`IndicatorScoringService`, `IndividualReportService`, etc.); does not modify them.
    Exit criteria: all endpoints respond with correct shapes; existing scoring logic is reused, not duplicated; no regression in existing report rendering.
    Completed: 2026-04-15.
    Handover:
    - `model/evaluation/EvaluationSnapshot.java` — MongoDB doc (`evaluationSnapshots`) with `userEmail`, `researcherId`, `reportId`, `name`, `createdAt`, `indicatorScores` (Map<String,Double>), `criteriaScores` (Map<Integer,Double>). Compound index on `(userEmail, reportId, createdAt desc)`.
    - `repository/EvaluationSnapshotRepository.java` — Spring Data Mongo repo; `findByUserEmailAndReportIdOrderByCreatedAtDesc`, `countByUserEmailAndReportId`, `findByIdAndUserEmail`.
    - `repository/reporting/UserIndividualReportRunRepository.java` — added `findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc` for the compare endpoint to load run history.
    - `view/user/EvaluationWorkspaceController.java` — 10 endpoints: MVC `GET /user/evaluation` (renders `user/individual-report-view`), 4 JSON read endpoints (indicator detail, compare, what-if, breakdown), 4 snapshot CRUD endpoints. Score extraction from `rawGraph` via reflection (`getAuthorScore`, `getScore`, `getQuarter`). SNAPSHOT_CAP = 50. Inner records: `IndicatorDetailResponse`, `ScoredItem`, `RunSummary`, `ScoreDelta`, `ComparisonResponse`, `WhatIfRequest/Item/Response`, `BreakdownItem/Response`, `SnapshotRequest/Summary/Detail`.
    - Test fixes: `ActivityInstanceControllerContractTest` updated for redirect-to-workspace behaviour; `UserReportFacadeTest` view name updated to `user/indicators-apply`; `RankingViewControllerContractTest` sidebar link assertion updated from `/user/profile` to `/user/workspace`.
    - `compileJava` clean; all three verification scripts pass; 763 tests pass (10 pre-existing failures in Postgres integration / ScopusProjectionBuilder / actuator health that require external infrastructure).

  - [x] `H37.3` **Report view as central hub with inline criterion expansion.**
    Rework `user/individual-report-view.html` (or replace it with a new workspace template) so the report is the central surface for all evaluation work:
    — Aggregate score panel at the top: overall score, criteria-met / criteria-total, career-level threshold progress bar per §7.5.
    — Criterion grid: each criterion card shows its score with the existing threshold visualization. Clicking a card expands it inline to reveal the embedded indicator detail (filter panel, chart, scored-item table) — the content rendered by the consolidated `H37.1` fragments — without leaving the page.
    — Only one criterion expanded at a time by default; user can override to expand multiple. Expansion state preserved in URL hash (e.g. `#criterion-3`) so deep links work.
    — Per-criterion refresh action and global "Refresh All" action with loading feedback (skeleton or spinner per §8.1).
    — Excel export action for the full report (reusing existing export machinery).
    Dependency: `H37.1`, `H37.2`.
    Exit criteria: report view renders with aggregate score and criterion grid; inline expansion loads scored data without full-page navigation; URL-hash deep links work; refresh actions work; Excel export works; both themes.
    Completed: 2026-04-15.
    Handover:
    - `templates/user/individual-report-view.html` — complete rework: aggregate score panel (overall score, criteria-met/total with progress bar, position, last-run); report switcher `<select>` (hidden if ≤1 report); "Refresh All" form targeting `/user/evaluation/refresh`; criterion grid with indicator expand buttons (AJAX detail, `data-indicator-id`) replacing old `<a>` links; per-indicator export link (`/user/indicators/export/{id}`); hidden `indicator-detail-panel` per indicator with skeleton and content slot; null-safe `th:if` expressions (`== true` / `!= true`) for `noReports`/`noRun` compatibility when rendered from `UserViewController`.
    - `view/user/EvaluationWorkspaceController.java` — added `overallScore` (sum of criterion scores), `criteriaMet` (count where researcher position threshold is met), `criteriaTotal`; added `POST /user/evaluation/refresh` and `POST /user/evaluation/indicator/{id}/refresh` redirect-style endpoints.
    - `static/js/individual-report-dashboard.js` — rewritten with: `initThresholdRows` (unchanged logic); `initCriterionToggles` (accordion, one open at a time, hash `#criterion-N`); `initIndicatorExpand` (AJAX fetch `/user/evaluation/indicator/{id}/detail`, skeleton, renders scored-item table, hash `#indicator-{id}`); `initReportSwitcher` (on-change redirect); `applyHashState` (restore expansion from URL hash on load).
    - `static/css/individual-report-dashboard.css` — new sections: `.app-eval-aggregate` panel; `.app-eval-report-switcher`; `.indicator-block` / `.indicator-expand-btn` (button styled as link); `.indicator-detail-panel` (animated inline expansion, dark-mode aware); responsive breakpoints.
    - Tests updated: `individualReportViewDisplaysCriterionNameOrFallback` checks "Refresh All" and `/user/evaluation/refresh`; `individualReportViewRendersThresholdBadgesAndCompactIndicatorLinks` checks `data-indicator-id` and export link instead of old apply link.
    - 763 tests pass; 10 pre-existing failures (Postgres/Scopus/actuator infrastructure).

  - [x] `H37.4` **Eliminate or collapse catalog pages.**
    Reduce the catalog pages to selectors within the report view:
    — `user/indicators.html`: either remove and redirect to the report view, or reduce to a simple selector component (e.g. a left sidebar list or dropdown inside the report view) that switches the active indicator/criterion context.
    — `user/individual-reports.html`: if the researcher has exactly one report, redirect straight into that report's view; if multiple, reduce to a lightweight selector (list or dropdown) at the top of the report view for switching between reports.
    — Update sidebar navigation: the "Indicators" and "Reports" entries collapse into one "Evaluation" item pointing to `/user/evaluation` (or the canonical report view route).
    — Add redirect mappings: `/user/indicators` → `/user/evaluation`, `/user/individual-reports` → `/user/evaluation`, `/user/indicators/apply/{id}` → `/user/evaluation#indicator-{id}` (opens the relevant criterion expanded), `/user/individual-reports/view/{id}` → `/user/evaluation?report={id}`.
    Dependency: `H37.3`.
    Exit criteria: navigation reaches the evaluation workspace in one click; legacy URLs redirect correctly; `npm run verify-route-guardrails` passes.
    Completed: 2026-04-16.
    Handover:
    - `view/UserViewController.java` — `GET /user/indicators` and `GET /user/individual-reports` now redirect to `/user/evaluation`; `GET /user/indicators/apply/{id}` redirects to `/user/evaluation#indicator-{id}`; `GET /user/individual-reports/view/{id}` redirects to `/user/evaluation?report={id}`; all four POST refresh endpoints now redirect to the canonical evaluation URL instead of their old view routes; dead model-building code removed from each.
    - `templates/fragments.html` — user-sidebar "Reporting" section collapsed from two items ("Indicators" + "Reports") to one "Evaluation" item (`/user/evaluation`, icon `fa-chart-bar`); active state matches `indicators`, `individual-reports`, or `evaluation` section keys.
    - `templates/user/indicators.html` and `templates/user/individual-reports.html` — deleted (dead code, routes now redirect).
    - `scripts/verify-route-guardrails.js` — removed stale check block for deleted `individual-reports.html`.
    - Tests updated: all `UserViewControllerContractTest` tests for catalog routes now assert 3xx redirects to canonical evaluation URLs; template-rendering tests dropped in favour of simpler redirect checks.
    - 763 tests pass; 10 pre-existing failures unchanged.

  - [x] `H37.5` **Period comparison.**
    Completed: 2026-04-16.
    Handover:
    - "Compare with…" button in toolbar opens a run-picker select (hidden by default; shown on button click). Picker lists prior runs with formatted timestamp and source label from `window.evalPriorRuns`.
    - `GET /user/evaluation/compare?runA={prior}&runB={current}` returns per-indicator and per-criterion score maps; JS computes deltas as `current − prior`.
    - Aggregate panel gains a hidden "Score Δ" cell (`#eval-compare-delta-cell`) revealed when comparison is active, showing overall delta with `eval-delta--positive/negative/neutral` colour coding.
    - Each criterion card has a `.eval-criterion-delta` span (block, reserves vertical space) that shows `+N.N / −N.N / =` during comparison.
    - Indicator rows carry a `data-indicator-id` `eval-indicator-delta` span updated inline.
    - Indicator detail panel also injects a delta badge next to the total when `_compareData` is active.
    - Comparison state persisted in URL via `history.replaceState(?compare={runId})`; page reload restores state automatically.
    - "Clear comparison" button hides the picker, clears all delta spans, removes URL param, and hides the compare banner.
    - Compare banner (`#eval-compare-banner`) shown between toolbar and aggregate panel while comparison is active; hidden otherwise.
    - `window.evalPriorRuns` / `window.evalCurrentRunId` seeded from Thymeleaf inline JS; `RunSummary.createdAt` changed from `Instant` to `String` to avoid Thymeleaf/Jackson JSR310 serialisation error.
    - CSS: `.app-eval-compare-picker[hidden] { display: none }` explicit rule prevents `display: flex` overriding the `hidden` attribute.
    - All select elements on the evaluation page given `padding-top/bottom: 0.2rem; height: auto` to fix disproportionate height at 0.82rem context font-size.

  - [ ] `H37.6` **What-if analysis.**
    Add a what-if panel accessible from each expanded criterion (or from a global "Scenario" action in the report header):
    — User can add hypothetical inputs scoped to a specific indicator: e.g. "add N Q1 publications", "add N activities of type X", "add N citations on publication Y". Input form adapts to the indicator's output type (reusing the output-type conditional logic from `H37.1`).
    — "Calculate" button submits the scenario to `POST /user/evaluation/what-if` and displays the recalculated score alongside the actual score, with a clear visual distinction (dashed border, "scenario" badge per §6.5) so users never confuse hypothetical with actual.
    — Aggregate impact shown at the top: how the scenario changes the overall score and whether any criteria now meet their threshold.
    — "Reset" clears the scenario; scenario state not persisted (one-off analysis).
    — Clear messaging: "This is a hypothetical calculation. No data has been saved."
    Dependency: `H37.2`, `H37.3`.
    Exit criteria: what-if panel opens and accepts inputs appropriate to the indicator's output type; recalculation matches the scoring strategy's actual behavior; hypothetical scores are visually distinct from actual; reset clears cleanly; no side effects on persisted data.

  - [ ] `H37.7` **Per-criterion score breakdown charts.**
    For each expanded criterion, render a visual breakdown of which items contribute how much to the score:
    — Bar or horizontal-bar chart (Chart.js, theme-aware per §6.9) showing each contributing publication/activity/citation by name (truncated with tooltip for full title) on one axis and its contribution score on the other.
    — Ordered by contribution descending. Items below a small threshold grouped into an "Other" bar.
    — Hovering a bar highlights the corresponding row in the detail table below (or vice versa) for cross-reference.
    — Legible in both themes; accessible (chart has a text summary alternative for screen readers listing the top contributors).
    — Data sourced from `GET /user/evaluation/breakdown/{indicatorId}` in `H37.2`.
    Dependency: `H37.2`, `H37.3`.
    Exit criteria: breakdown chart renders for each criterion type (publication/activity/citation); ordering is correct; cross-reference highlight works; text alternative exists; both themes.

  - [ ] `H37.8` **Saved report snapshots.**
    Let researchers save a named snapshot of the current report state for later comparison:
    — "Save snapshot" action in the report header prompts for a name (default: "Snapshot {timestamp}") and stores the current aggregate scores, per-indicator scores, and run metadata via `POST /user/evaluation/snapshots`.
    — Snapshot storage: new entity/table (e.g. `EvaluationSnapshot`) with fields for `researcherId`, `reportId`, `name`, `createdAt`, `scoresJson`. Migration scripts included.
    — "My Snapshots" panel lists saved snapshots with name, timestamp, and actions (load into comparison, delete with confirmation per §6.7).
    — Snapshots appear as first-class options in the `H37.5` comparison run-picker, so a researcher can compare the current state against a saved snapshot, not just against prior runs.
    — Limit: reasonable cap (e.g. 50 snapshots per researcher per report) with empty/full states per §6.6.
    Dependency: `H37.2`, `H37.5`.
    Exit criteria: snapshots save, list, delete, and compare correctly; migration runs cleanly on an existing database; empty state and cap handling work; no stale data after deletion.

  - [ ] `H37.9` **Responsive behavior and accessibility audit.**
    Verify the evaluation workspace meets responsive and accessibility requirements:
    — Criterion grid: reflows from multi-column to single-column per §4.4.
    — Inline expansion: detail content stacks gracefully on small screens; filter panel and chart remain usable.
    — Comparison mode: deltas stay readable (not squeezed) on mobile.
    — What-if panel: full-width on mobile.
    — Breakdown chart: resizes correctly and stays legible.
    — Snapshot list: scrollable on mobile.
    — Accessibility: criterion expansion uses `aria-expanded`; comparison deltas pair color with text and direction icons per §2.3; charts have text alternatives; all interactive controls keyboard-reachable; focus management on expand/collapse and panel open/close; WCAG AA contrast in both themes.
    Exit criteria: workspace usable on 320px-wide viewport; keyboard-only navigation works end-to-end; screen readers announce expansion state, comparison deltas, and chart summaries; contrast passes WCAG AA in both themes.

  - [ ] `H37.10` **Legacy template cleanup and verification.**
    After the evaluation workspace is stable:
    — Remove or mark deprecated: `user/indicators-apply-publications.html`, `user/indicators-apply-activities.html`, `user/indicators-apply-citations.html` (replaced by consolidated template from `H37.1`), `user/indicators.html`, `user/individual-reports.html` (reduced or redirected per `H37.4`). `user/individual-report-view.html` either replaced or substantially rewritten per `H37.3`.
    — Remove or redirect old controller methods fully replaced by `EvaluationWorkspaceController`.
    — Verify no remaining references to old template names in JS, CSS, or other templates.
    — Run full verification suite: `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`.
    — Smoke test: single-run view, comparison mode, what-if scenarios for each output type, breakdown charts, snapshot save/load/delete/compare, Excel export.
    Dependency: all of `H37.1`–`H37.9` complete.
    Exit criteria: no dead templates for replaced pages; all verification scripts pass; no 404s or broken links in evaluation flows; all Option C features (period comparison, what-if, breakdown charts, snapshots) verified end-to-end.

## Backlog

- [ ] `H38` User-Reviewed Publication Authorship Overlay.
  Goal: let researchers confirm or reject authorship for imported publications so noisy Scopus links stop polluting reports, indicators, citations, and workspace views without deleting source data.
  Deliverable: a local authorship-decision layer on top of canonical imported publication links, with review UI, suspicious-publication triage, and reporting/read-model filtering that prefers user decisions over raw source linkage.
  Exit criteria: researchers can mark a publication as `CONFIRMED` or `REJECTED`; rejected publications no longer count toward user-facing reporting, indicators, citations, exports, and workspace lists; confirmed publications remain included even if later imports stay noisy; imported Scopus/DBLP lineage remains preserved and auditable; the system can surface a "needs review" queue for suspicious authorship links instead of requiring users to inspect all publications manually.

  Subtasks:

  - [ ] `H38.1` **Authorship decision persistence model.**
    Add a dedicated persistence model for user-level publication authorship decisions keyed by user + publication, separate from imported Scopus/Scholardex facts.
    Deliverable: document/entity + repository storing `status` (`CONFIRMED` / `REJECTED`), timestamps, decision source, optional reason, and enough immutable context to audit later.
    Exit criteria: imported source facts remain untouched; user decisions can be created, updated, queried, and deleted independently; duplicate decisions per user/publication are prevented.

  - [ ] `H38.2` **Effective-authorship read filtering.**
    Introduce a publication-authorship overlay in the read/reporting path so user decisions are applied consistently before data reaches indicators, citations, exports, and workspace tabs.
    Deliverable: shared filtering support or projection/read-model layer that excludes locally rejected publications and preserves locally confirmed ones.
    Exit criteria: all user-facing publication/citation/report queries can consume an "effective publications for user" view; no scoring service needs ad-hoc reject logic embedded directly in its scoring rules.

  - [ ] `H38.3` **Inline confirm/reject actions in researcher publication surfaces.**
    Add authorship confirmation/rejection controls to the main user-facing publication views, starting with the workspace publications tab and any remaining publication detail/apply flows where authorship confusion is visible.
    Deliverable: UI actions `Confirm mine` / `Reject authorship`, optimistic feedback, and visible authorship state on affected rows/details.
    Exit criteria: a researcher can review and decide authorship from the normal publication workflow without admin intervention; state persists and reflects immediately in the same surface.

  - [ ] `H38.4` **Suspicious-authorship triage queue.**
    Create a targeted "needs review" queue so users are asked only about likely false positives instead of every imported paper.
    Deliverable: heuristics and/or rule-based flags for suspicious authorship links (name mismatch, affiliation mismatch, topic jump, low evidence overlap, etc.) plus a dedicated queue/list in the user workspace.
    Exit criteria: the queue is populated deterministically from explicit heuristics; each flagged publication explains why it was flagged; researchers can confirm/reject directly from the queue.

  - [ ] `H38.5` **Bulk review workflow.**
    Support efficient cleanup of polluted Scopus identities by allowing multi-select or repeated queue decisions without opening each publication individually.
    Deliverable: bulk confirm/reject actions with safeguards, summary counts, and undo/rollback-friendly handling where practical.
    Exit criteria: researchers can clear multiple false-positive publications in one operation; accidental mass rejection is guarded by confirmation UX and auditable persisted decisions.

  - [ ] `H38.6` **Indicator/report/export integration.**
    Apply effective-authorship filtering to all user-facing reporting outputs that currently assume imported authorship is correct.
    Deliverable: indicator apply views, report computation, citation lists, workbook exports, and workspace summary counts all use the same effective-authorship layer.
    Exit criteria: rejecting a publication removes it from scores, totals, charts, and exports consistently; confirming a publication preserves inclusion consistently.

  - [ ] `H38.7` **Operational diagnostics and auditability.**
    Make authorship overrides explainable for both users and maintainers.
    Deliverable: concise provenance on publication rows/details ("Imported from Scopus, locally rejected by user on {date}") and admin/debug visibility into decision state without losing raw source lineage.
    Exit criteria: support/debug flows can distinguish imported linkage from local override decisions; users can see the current authorship status and when it changed.

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.
