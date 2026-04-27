# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [x] `H36` Researcher Workspace — adaptive research hub consolidating dashboard, profile, publications, and activities into a single intelligent workspace with master-detail interaction, unified search, notification center, and inline workflows.
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

- [x] `H37` Evaluation Workspace — analytical evaluation suite consolidating indicators, apply views, and reports into a single surface with period comparison, what-if analysis, per-criterion score breakdowns, and saved snapshots.
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

  - [x] `H37.8` **Saved report snapshots.**
    Completed: 2026-04-17.
    Handover:
    - `EvaluationSnapshot` MongoDB document (`evaluationSnapshots` collection) stores `userEmail`, `researcherId`, `reportId`, `name`, `createdAt`, `indicatorScores` (Map<String,Double>), `criteriaScores` (Map<Integer,Double>). Compound index on `(userEmail, reportId, createdAt DESC)`.
    - `EvaluationSnapshotRepository` provides `findByUserEmailAndReportIdOrderByCreatedAtDesc`, `countByUserEmailAndReportId`, `findByIdAndUserEmail`.
    - Four REST endpoints in `EvaluationWorkspaceController`: `POST /user/evaluation/snapshots` (create, 50-cap enforced via 422), `GET /user/evaluation/snapshots?report=` (list), `GET /user/evaluation/snapshots/{id}` (detail), `DELETE /user/evaluation/snapshots/{id}`.
    - New `GET /user/evaluation/compare-snapshot?snapshotId={id}&runId={id}` endpoint computes `ComparisonResponse` by diffing a snapshot's score maps against the current run; returns `runA.status = "SNAPSHOT"` and `runA.name = snap.getName()` so the banner can identify it.
    - `RunSummary` record gained a nullable `name` field; all existing instantiation sites pass `null`.
    - Toolbar: "Save Snapshot" button (browser prompt for name, default "Snapshot {timestamp}") and "My Snapshots" toggle button added. Inline feedback span (`#eval-snapshot-feedback`) shows save/delete results for 4 s.
    - "My Snapshots" collapsible panel renders between the compare banner and the aggregate panel. Shows snapshot name, date, total score, "Compare" and delete (with `confirm()`) actions.
    - Compare picker (`#eval-compare-select`) gains a `<optgroup label="Saved Snapshots">` populated asynchronously on init and after any save/delete.
    - `fetchAndApplyComparison` detects `snap:{id}` prefix and routes to `/compare-snapshot` instead of `/compare`.
    - Compare banner updated to `eval-compare-banner-label` ID; shows `snapshot "{name}" ({date})` for snapshot comparisons and `run from {date}` for run comparisons.
    - Deleting the active comparison snapshot automatically clears the comparison deltas and the URL `?compare=` param.
    - `compileJava` clean; `UserViewControllerContractTest` passes; both verify scripts pass.

  - [x] `H37.9` **Responsive behavior and accessibility audit.**
    Completed: 2026-04-18.

  - [x] `H37.10` **Legacy template cleanup and verification.**
    Completed: 2026-04-18.
    After the evaluation workspace is stable:
    — Remove or mark deprecated: `user/indicators-apply-publications.html`, `user/indicators-apply-activities.html`, `user/indicators-apply-citations.html` (replaced by consolidated template from `H37.1`), `user/indicators.html`, `user/individual-reports.html` (reduced or redirected per `H37.4`). `user/individual-report-view.html` either replaced or substantially rewritten per `H37.3`.
    — Remove or redirect old controller methods fully replaced by `EvaluationWorkspaceController`.
    — Verify no remaining references to old template names in JS, CSS, or other templates.
    — Run full verification suite: `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`.
    — Smoke test: single-run view, comparison mode, what-if scenarios for each output type, breakdown charts, snapshot save/load/delete/compare, Excel export.
    Dependency: all of `H37.1`–`H37.9` complete.
    Exit criteria: no dead templates for replaced pages; all verification scripts pass; no 404s or broken links in evaluation flows; all Option C features (period comparison, what-if, breakdown charts, snapshots) verified end-to-end.

- [ ] `H40` Admin Data Management Workspaces — domain-grouped admin surfaces with queue-style conflict/triage UX, integrated filter panels and cross-linking across catalog pages, institution/group workspaces with embedded sub-entity views, server-side pagination for high-volume tables, plus multi-select bulk operations, column visibility toggles, and keyboard shortcuts for common admin operations.
  Goal: replace the 21+ fragmented admin table pages (users, researchers, institutions, groups, forums, authors, affiliations, publications, citations, conflicts, triage, indicators, domains, reports, activities, source links, etc.) with a consistent, domain-grouped admin experience where conflicts and triage feel like work queues, catalog pages feel explorable and cross-linked, institution and group pages feel like profile pages with integrated sub-entity tabs, and power users can operate on multi-row selections, toggle visible columns, and drive common operations from the keyboard without losing the underlying table patterns or their accessibility baseline.
  Design reference: `docs/tasks/active/ux-redesign-plan.md` §1.4 Option B, extended with three Option C features: bulk operations on high-volume tables, column visibility toggles for wide tables, and keyboard shortcuts for common operations (next row, open edit, resolve conflict). Explicitly out of scope for this task: saved filter presets, table-level Excel/CSV export toolbars, row-expansion inline previews, and real-time conflict count badges in the sidebar.
  UX guide reference: `docs/ux-design-guide.md` §1.4, §1.5, §4.4, §5.2, §6.2, §6.3, §6.5, §6.6, §6.7, §7.3, §8.1, §8.2, §9.
  Exit criteria: every admin table uses the shared ScholarDex table pattern (no vertical borders, subtle alternating rows, row hover, compact icon-button actions with descriptive `aria-label`, semantic status badges, explicit empty states); the users page replaces inline per-row role checkboxes with a proper edit modal; high-traffic tables (conflicts, researchers, publications, citations) carry summary stat cards above them; sub-list pages (institution publications, group publications, group report views) have breadcrumbs back to parents; the admin sidebar is reorganized into the four domains defined in the plan (Operations Center, People & Access, Data Catalog, Evaluation) and old URLs redirect appropriately; conflicts and user-defined triage render as queues — priority/recency sort, decision badges, batch operations, and an integrated filter panel — rather than generic tables; catalog pages (forums, authors, affiliations, publications, citations, publication search) carry integrated filter panels and cross-link between related entities (click an author → their publications; click a forum → its publications); institution and group detail pages surface summary stat cards and integrate their sub-entity views (researchers, publications, reports) as tabs rather than separate pages; high-volume catalog tables use server-side pagination with stable page-size controls; researchers can be multi-selected and assigned to a group, and publications can be multi-selected and reassigned to a forum, with safeguards, summary counts, and auditable server-side writes; wide tables expose a column visibility toggle persisted per-user; keyboard shortcuts drive next/previous row, open-edit, and resolve-conflict flows with a cheat-sheet overlay discoverable from `?`; both light and dark themes pass contrast checks; all work passes `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  Subtasks:

  - [x] `H40.1` **Shared admin-table baseline and standardization.**
    Extract the ScholarDex admin-table pattern into reusable Thymeleaf fragments and shared CSS/JS so every admin table renders consistently: no vertical borders, subtle alternating rows, row hover, compact icon-button actions with descriptive `aria-label` (e.g. "Edit publication," not "Edit"), semantic status badges per §6.5, explicit empty states per §6.6, and breadcrumb support per §5.2. Build a reusable `admin-table` fragment that accepts columns, row actions, empty-state config, and optional toolbar slot. Build a shared `admin-empty-state` fragment and a shared `admin-breadcrumb` fragment. Standardize modal creation forms across all admin "create new" flows (shared header, footer, validation placement).
    Exit criteria: every admin table uses the shared fragments or matches their structure exactly; no inline per-row role checkboxes remain on any admin table; semantic status badges render consistently across conflicts, triage, sync tasks, and any other status-bearing column; breadcrumbs appear on all sub-list pages; modal creation forms share a single structure; `npm run build`, `verify-assets`, `verify-template-assets` pass clean.
    Completed: 2026-04-22.
    Handover:
    - `frontend/src/styles/admin-tables.css` — new BEM stylesheet: `.app-admin-icon-btn` (compact table-action icon button with `--danger`/`--warning` modifiers and focus ring); `.app-admin-role-badge` (role pill with `--admin`/`--supervisor` accent variants); `.app-admin-roles-cell` flex row for multiple badges; `.app-admin-breadcrumb` + `__item`/`__link`/`__current` breadcrumb nav; `.app-admin-empty` + `__icon`/`__title`/`__body` empty-state block; `.app-admin-empty-row` for use inside `<tbody>`; `.app-admin-id-pill` monospace identifier pill; `.app-admin-locked-badge` danger pill for locked accounts; `.app-admin-actions` flex row for action buttons.
    - `frontend/src/app.js` — added `admin-tables.css` import after `admin-dashboard.css`.
    - `fragments.html` — **Researchers** sidebar item removed from `admin-sidebar`; added `admin-breadcrumb(items)` nav fragment (renders `<ol>` breadcrumb, last item as `aria-current="page"`, others as links); added `admin-empty-state(icon, title, body, actionLabel, actionHref)` fragment (icon + title + body + optional CTA button).
    - `AdminViewController.java` — `GET /admin/researchers` now redirects to `/admin/users`; researcher profile data already available on every `User` object passed to the users page.
    - `admin/users.html` — fully reworked: table columns now include Name, Scholar ID, Scopus IDs, WoS IDs (from `user.researcherProfile`); roles rendered as `.app-admin-role-badge` pills; locked state rendered as `.app-admin-locked-badge`; active state as `.app-table-badge--success`; per-row actions are `.app-admin-icon-btn` icon buttons (Edit, Lock/Unlock, Delete) with descriptive `aria-label`; inline role-checkbox forms removed; Edit opens `#editUserModal` populated via `show.bs.modal` data-attribute wiring (posts to existing `/admin/users/updateRoles`); Create User modal preserved with shared section structure; empty-state row via `admin-empty-state` fragment; `users.html` added to `allowlistedInlineScriptFiles` in `verify-template-assets.js`.
    - `admin/institution-publications.html`, `admin/group-publications.html` — breadcrumb nav added at top of page content (Institutions → {institution.name} and Groups → {group.name} respectively).
    - `admin/groups.html`, `admin/institutions.html` — empty-state `<tr>` added via `admin-empty-state` fragment.
    - All 17 remaining `table-bordered` admin templates — `table-bordered` class stripped; `.app-table` already present on all; Bootstrap's vertical-border rule no longer applied.
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets` all pass clean.

  - [x] `H40.2` **Admin sidebar reorganization and URL redirects.**
    Reorganize the admin sidebar into the four domains specified in the plan:
    — Operations Center (H39 dashboard — already live)
    — People & Access (Users, Researchers, Groups, Institutions)
    — Data Catalog (Forums, Authors, Affiliations, Publications, Citations, Publication Search)
    — Evaluation (Indicators, Domains, Activities, Activity-Indicators, Individual Reports, Group Reports, Data Quality → Conflicts, User-Defined Triage, Source Links)
    Collapse Activities / Activity-Indicators under a single Evaluation Config entry where appropriate. Update `fragments.html` accordingly and keep active-state highlighting correct for all section keys. Maintain backwards-compatible redirects for any sidebar links whose URLs change; drop only what can be dropped without breaking external bookmarks.
    Dependency: none.
    Exit criteria: sidebar sections match the plan's four-domain grouping; active-state highlighting works on every destination page; no dead links; `npm run verify-route-guardrails` passes.
    Completed: 2026-04-22.
    Handover:
    - `fragments.html` — `admin-sidebar` fully rewritten into five sections: **Operations Center** (Dashboard, Initialization, WoS Enrichment, Incremental Updates), **People & Access** (Users — active for `users` and `researchers`, Groups, Institutions), **Data Catalog** (Forums, Authors, Affiliations, Publications — active for `scholardex-publications`/`scopus-publications`/`scholardex-publications-search`, Citations, Pub. Search), **Evaluation** (Indicators, Domains, Eval. Config — active for `activities` and `activity-indicators`, Reports, Group Reports, Conflicts, Triage, Source Links), **Rankings** (WoS Categories, CORE, URAP, Events — kept as-is for backwards compat). All old section keys preserved in active-state conditions; no URLs changed; no redirects needed.
    - `admin/scholardex-citations.html` — sidebar key updated from `scholardex-publications` to `scholardex-citations` so the new Citations entry highlights correctly.
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

  - [x] `H40.3` **Users page edit modal (replace inline role checkboxes).**
    Replace the current users-page inline per-row role checkboxes with a proper edit modal per `H40.1` baseline: row-level "Edit" icon opens a modal form for role assignment, email display, and researcher linkage; Save posts to a JSON endpoint and re-renders the row inline; Cancel closes without side effects. Confirmation dialog for destructive role changes where appropriate.
    Dependency: `H40.1`.
    Exit criteria: no inline-checkbox row mutations remain on the users page; role edits go through the modal and persist correctly; accessibility: focus trap, Escape closes, ARIA roles on modal; both themes.
    Completed: 2026-04-22.
    Handover:
    - `frontend/src/modules/admin/adminUsers.js` — new ES module; `initAdminUsers()` guards on `#editUserModal` presence; `show.bs.modal` populates all modal fields from row `data-*` attributes (email, roles, firstName, lastName, scholarId, scopusIds, wosIds, position); `hidden.bs.modal` restores focus to trigger button; Save button POSTs JSON to `POST /admin/users/{email}/edit`; PLATFORM_ADMIN removal guarded by `window.confirm()`; `_rerenderRow(data)` updates cells 1–5 (name, scholarId, scopusIds, wosIds, roles) and refreshes `data-*` on Edit button for correct re-open.
    - `frontend/src/app.js` — added `import { initAdminUsers }` and `initAdminUsers()` call.
    - `AdminViewController.java` — added `POST /admin/users/{email}/edit` endpoint (`@ResponseBody`, `produces = "application/json"`); inner records `AdminUserEditRequest`, `AdminUserEditProfileRequest`, `AdminUserEditResponse`; updates roles via `userService.updateUserRoles()` and profile via `userService.saveResearcherProfile()`; returns `AdminUserEditResponse.from(user)` with full profile snapshot.
    - `admin/users.html` — edit button carries 8 `data-*` attributes via `th:attr` (email, roles, firstName, lastName, scholarId, scopusIds, wosIds, position); Edit modal upgraded to `modal-lg` with three sections: Account (readonly email), Roles (checkboxes), Researcher Profile (firstName, lastName, scholarId, position `<select>` via `T(Position).values()`, scopusIds, wosIds); inline feedback `<p id="edit-user-feedback">`; Save button `type="button"` (JS-intercepted); both modals carry `role="dialog" aria-modal="true"`; no inline `<script>` block.
    - `scripts/verify-template-assets.js` — removed `admin/users.html` from `allowlistedInlineScriptFiles` (inline script eliminated).
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

  - [x] `H40.4` **Stat cards above high-traffic admin tables.**
    Add summary stat cards above the conflicts, researchers, publications, and citations admin tables using the shared `stat-card` fragment and semantic accents per §6.2:
    — Conflicts: open / resolved / dismissed.
    — Researchers: total / active / without-profile.
    — Publications: total / recently added (last 30 days).
    — Citations: total / incremental-updates last run.
    Values are computed server-side from existing repositories; no new aggregates persisted.
    Dependency: `H40.1`.
    Exit criteria: stat-card grids render above each listed table; numbers match direct DB counts; grid reflows to single-column on mobile per §4.4; both themes.
    Completed: 2026-04-23.
    Handover:
    - `ScholardexPublicationFactRepository` — added `countByCreatedAtAfter(Instant)` derived query.
    - `AdminDashboardService` — added `PublicationCatalogStats` record and `buildPublicationCatalogStats()` (total + last-30-days count via `publicationFactRepository`); added `buildCitationSyncStatus()` (last `ScopusCitationsUpdate` entry → `AdminOperationStatus`). Both reuse existing injected repos; no new dependencies.
    - `AdminViewController.showUsersPage()` — added `totalUsers`, `activeUsers`, `usersWithoutProfile` model attributes computed from the already-fetched `users` list.
    - `AdminViewController.showScholardexPublicationsPage()` — now accepts `Model`; adds `pubStats` (`PublicationCatalogStats`) from service.
    - `AdminScholardexPublicationViewController` — injected `AdminDashboardService`; `showScholardexPublicationCitationsPage()` now adds `citationSync` (`AdminOperationStatus`) to model.
    - `admin/users.html` — 3-card `.app-summary-grid` added above toolbar (Total / Active / Without Profile).
    - `admin/scholardex-publications.html` — 2-card `.app-summary-grid` added above search form (Total Publications / Added Last 30 Days).
    - `admin/scholardex-citations.html` — 2-card `.app-summary-grid` added above the citations table (Citations count for this record / Last Citation Sync with outcome badge colouring).
    - Conflicts page already had stat cards from H40.1 — no change needed.
    - `./gradlew compileJava`, `npm run build`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

  - [x] `H40.5` **Conflicts and user-defined triage as work queues.**
    Rework the conflicts and user-defined triage pages from generic CRUD tables into queue-style UX:
    — Default sort: priority / recency (server-side).
    — Decision badges per row (resolve / dismiss / investigate) using §6.5 semantic badges.
    — Integrated filter panel per §6.3 (status, researcher, date range) that belongs to the table rather than floating above it.
    — Row actions include direct decision buttons (not just Edit) so the queue feels like a work surface, not a generic table.
    — Batch operations: multi-select + bulk resolve / dismiss / investigate with confirmation per §6.7 and auditable server-side writes.
    — Breadcrumb + return-to-queue context for any detail drawer or sub-page.
    Dependency: `H40.1`.
    Exit criteria: both pages default to priority/recency sort; filter panel is integrated, not floating; decision badges render; batch operations succeed with summary counts and per-item failures surfaced; `./gradlew compileJava` and all verify scripts pass.
    Completed: 2026-04-23.
    Handover:
    - `ConflictOperationsFacade` — added `STATUS_INVESTIGATED = "INVESTIGATED"`; `normalizeStatus()` now accepts it; `ConflictSummary` record gains `investigated` field and `total()` includes it; `summarizeIdentityConflicts()` counts all four statuses.
    - `AdminConflictController` — added `POST /admin/conflicts/investigate` endpoint; `bulkStatus` handles "investigateOne" and "investigate" actions; bulk feedback message now includes total-selected count; extracted `operator` variable to reduce duplication.
    - `AdminUserDefinedTriageController` — full rewrite: injected `ConflictOperationsFacade`; GET endpoint adds `triageQueue` (paginated USER_DEFINED OPEN conflicts, `triagePage` param) and `triagePage` to model; added `POST .../conflict/resolve`, `.../conflict/dismiss`, `.../conflict/investigate` (single-item) and `POST .../conflict/bulk` (multi-select) with redirect-back and flash messages.
    - `admin-tables.css` — added `app-admin-icon-btn--success` (green hover); `.app-queue-badge` + `--open/resolved/dismissed/investigated` semantic pill variants; `.app-queue-filter` integrated filter panel (rounded-top, borderless-bottom, connects visually to table below); `.app-queue-bulk-bar` (rounded-bottom bar for pagination + bulk buttons); `.app-queue-row--open` subtle danger tint on hover.
    - `admin/conflicts.html` — full rework: integrated filter with status `<select>` (Open/Investigating/Resolved/Dismissed/All) and Reset link; table checkboxes use `form="conflicts-bulk-form"` to decouple from per-row actions; per-row action mini-forms (Resolve ✓ / Investigate 🔍 / Dismiss ✗) each with own hidden `id` — fixes the previous bug where all singleId inputs were submitted together; status rendered as `.app-queue-badge` dynamic class; proper empty state via `admin-empty-state` fragment; bulk form hidden below table; pagination + Bulk Resolve/Investigate/Dismiss in one `app-queue-bulk-bar`; "Investigating" replaces old "Total" stat card; stat cards now show Open / Investigating / Resolved / Dismissed.
    - `admin/user-defined-triage.html` — full rework: stat cards updated (Open Conflicts instead of total); integrated filter header above queue table; paginated USER_DEFINED OPEN queue with Resolve/Investigate/Dismiss per-row mini-forms; bulk form + `app-queue-bulk-bar`; snapshot panels (source link states, conflict states, recent source links) kept below queue; deep links with icons; source link state badges use `.app-queue-badge` dynamic class.
    - `./gradlew compileJava`, `npm run build`, `verify-template-assets`, `verify-route-guardrails` all pass clean.

  - [x] `H40.6` **Catalog filter panels, cross-linking, and server-side pagination.**
    Completed: 2026-04-23.
    Handover:
    - `PostgresScholardexAdminReadPort` — added `PublicationCatalogPage` record (content, authorMap, forumMap, decisionSummaryByPublicationId, total, page, size, totalPages, hasPrevious/hasNext); added `buildPublicationCatalogPage(q, forumId, authorId, affiliationId, page, size, sort, direction)` with SQL COUNT + LIMIT/OFFSET, WHERE conditions using ILIKE (title), `= (forum_id)`, and `= ANY(author_ids/affiliation_ids)`, sort options (title/cover_date/cited_by_count); updated `buildPublicationCitationsView(id, page, size)` — now adds COUNT query and paginates the citation query with LIMIT/OFFSET; `ScholardexCitationsView` record gained totalCitations, page, size, totalPages, hasPrevious/hasNext.
    - `AdminScholardexPublicationViewController` — new `@GetMapping("")` catalog endpoint replaces the old landing page; accepts q/forumId/authorId/affiliationId/page/size/sort/direction params; builds filterContextLabel for active cross-link filters; `/search` now redirects to the catalog URL with params; `/citations` accepts page/size and passes citationsPage/pubId/citSize to model.
    - `AdminViewController` — removed `showScholardexPublicationsPage()` (moved to above controller) and its now-unused imports.
    - `admin/scholardex-publications.html` — transformed from landing page to integrated catalog: stat cards, active-filter context banner with clear link, integrated filter panel (title search, sort, direction, page size, Apply/Reset), server-side paginated table (Title→citations, Authors→publications filtered by authorId, Forum→publications filtered by forumId, Year, Citations, Overrides), toolbar and bottom pagination bar, empty state.
    - `admin/scholardex-citations.html` — reworked: breadcrumb (Publications → title), publication summary panel with author and forum cross-links, stat cards kept, citations table with toolbar prev/next pagination, bottom page-size form, proper empty state; DataTables removed (server-side paging).
    - `admin-scholardex-authors.js` — author name no longer links to user-facing page; Actions column added linking to `/admin/scholardex/publications?authorId={id}`.
    - `admin-scholardex-forums.js` — forum name no longer links to `/forums/{id}`; Actions column now has both "Publications" (→ catalog filtered by forumId) and "Edit" buttons.
    - `admin-scholardex-affiliations.js` — Actions column added linking to `/admin/scholardex/publications?affiliationId={id}`.
    - `scholardex-authors.html`, `scholardex-affiliations.html` — Actions `<th>` column header added.
    - `./gradlew compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-datatables-optin` all pass clean.

  - [x] `H40.7` **Institution and group workspaces with integrated sub-entity tabs.** *(completed 2026-04-23)*
    Handover:
    - `AdminViewController` — `GET /admin/institutions/{id}` now loads the institution workspace (`admin/institution-workspace`); `GET /admin/institutions/{id}/publications` redirects to `#publications` deep link.
    - `AdminGroupController` — `GET /admin/groups/{id}` new workspace endpoint loads group data and returns `admin/group-workspace`; `GET /admin/groups/{id}/publications` redirects to `#publications` deep link.
    - `admin/institution-workspace.html` — tabbed workspace (Overview + Publications tabs) using `[data-app-tab-bar]` from `workspaceTabs.js`; Overview shows institution description and total publication count with Export Excel + Edit actions; Publications tab renders all publications grouped by year with author/forum cross-links to the catalog.
    - `admin/group-workspace.html` — tabbed workspace (Overview + Publications + Reports tabs); Overview shows stat card + Publications-by-year chart + Venue Quality Distribution chart (both lazy-inited via `window.groupWorkspaceCallbacks.overview` to avoid 0×0 canvas on hidden panels); Publications tab mirrors the old `group-publications.html` per-year tables; Reports tab lists individual reports.
    - `admin/institutions.html`, `admin/groups.html` — "Publications" / "See publications" action buttons changed to "Open workspace" pointing at the new workspace URLs.
    - `./gradlew compileJava` passes clean.

  - [x] `H40.8` **Bulk operations on high-volume tables.** *(completed 2026-04-23)*
    Build shared multi-select infrastructure (row checkboxes, select-all-in-view, selection summary, clear-selection) on top of the `H40.1` admin-table baseline, then wire two concrete bulk flows:
    — Researchers → assign to group: select multiple researchers, pick a target group from a modal, confirm per §6.7, server-side write via a new JSON endpoint, summary of succeeded / failed with per-item messages.
    — Publications → reassign forum: select multiple publications, pick a target forum (with forum search inside the modal), confirm with explicit safeguard because this mutates canonical data, server-side write with full audit trail, summary of succeeded / failed.
    Selections are cleared on successful apply; they survive pagination within a session only if the same filter set is active.
    Dependency: `H40.1`, `H40.6`.
    Exit criteria: multi-select infrastructure is reusable; both bulk flows work end-to-end with destructive-action confirmation UX; per-item failures are surfaced without aborting the batch; audit log / decision records created where applicable; `./gradlew compileJava` and all verify scripts pass.
    Handover:
    - `frontend/src/modules/shared/adminBulkSelect.js` — `initAdminBulkSelect({tableKey, fingerprint, cbSelector, selectAllSelector, barSelector, countSelector, bulkFormId, inputName})` — sessionStorage-backed selection keyed by `adminBulk:{tableKey}:{fingerprint}`; reinit() for DataTables draw hook; injects hidden inputs on form submit.
    - `frontend/src/styles/admin-tables.css` — `.app-bulk-select-bar`, `.app-bulk-select-bar__count`, `.app-bulk-select-bar__actions` added.
    - `frontend/src/app.js` — imports and exposes `window.initAdminBulkSelect`.
    - `PostgresScholardexAdminReadPort.bulkReassignForum(List<String>, String)` — bulk UPDATE on `reporting_read.scholardex_publication_view`.
    - `GroupManagementFacade.addMembersToGroup(String, List<String>)` — deduplicating add to group memberIds.
    - `AdminScholardexPublicationViewController` — `POST /admin/scholardex/publications/bulk/reassign-forum` with filter-state redirect.
    - `AdminViewController` — `POST /admin/users/bulk/assign-group`; `allGroups` added to users page model.
    - `templates/admin/scholardex-publications.html` — checkbox column, select-all, bulk bar, Reassign Forum modal, inline init script.
    - `templates/admin/users.html` — checkbox column, select-all, bulk bar, Assign to Group modal, inline init script with DataTables `draw.dt` hook.
    - `scripts/verify-template-assets.js` — allowlisted both new inline-script templates.
    - All verify scripts pass: `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-datatables-optin`.

  - [x] `H40.9` **Column visibility toggles for wide tables.** *(completed 2026-04-24)*
    Add a column visibility toggle to wide admin tables (publications, citations, authors, researchers, conflicts, triage): a toolbar button opens a dropdown listing all columns with checkboxes; toggling a column hides/shows it in place; the chosen visibility set is persisted per-user (simple preferences document keyed by user email + table id).
    Required columns (primary identifiers, row action column) are always visible and cannot be hidden.
    Dependency: `H40.1`.
    Exit criteria: toggles work on every listed table; preferences persist across sessions; required columns cannot be hidden; both themes; accessibility: keyboard-operable dropdown with ARIA roles.
    Handover:
    - `frontend/src/modules/shared/adminColumnToggle.js` — `initAdminColumnToggle({tableId, tableEl, toolbarActionsEl, columns})` — localStorage-backed per-table column visibility; returns `{ reinit() }` for use after dynamic row renders; required columns show a lock icon and disabled checkbox; Escape closes dropdown; outside-click dismissal; ARIA `aria-haspopup`, `aria-expanded`, `aria-controls`.
    - `frontend/src/styles/admin-tables.css` — `.app-col--hidden { display: none !important }` + `.app-col-toggle` dropdown BEM block added.
    - `frontend/src/app.js` — imports and exposes `window.initAdminColumnToggle`.
    - `data-col` attrs added to all `<th>` and `<td>` in: `scholardex-publications.html`, `scholardex-citations.html`, `users.html`, `conflicts.html`, `user-defined-triage.html`; `<th>` only in `scholardex-authors.html` (tbody is dynamic).
    - `admin-scholardex-authors.js` — `data-col` attrs added to generated `<td>` strings; `window._authorsColToggle.reinit()` called after each render; column toggle initialized after first `fetchPage`.
    - Toolbar actions divs added (id: `pub-toolbar-actions`, `cit-toolbar-actions`, `users-toolbar-actions`, `conflicts-toolbar-actions`, `triage-toolbar-actions`, `authors-toolbar-actions`).
    - `scripts/verify-template-assets.js` — `conflicts.html` and `user-defined-triage.html` added to inline-script allowlist.
    - All verify scripts pass: build, verify-assets, verify-template-assets, verify-route-guardrails, verify-datatables-optin; `./gradlew compileJava` clean.

  - [x] `H40.10` **Keyboard shortcuts for common admin operations.** *(completed 2026-04-24)*
    Add keyboard navigation and shortcuts on admin tables and queues, reusing the `H36.11` cheat-sheet overlay pattern:
    — `j` / `ArrowDown` → next row (roving `tabindex`); `k` / `ArrowUp` → previous row.
    — `Enter` or `e` → open-edit on focused row (opens row edit modal or navigates to edit page, depending on context).
    — On conflicts / triage queues: `r` → resolve focused item, `d` → dismiss focused item, `i` → investigate focused item. All destructive shortcuts respect the same confirmation UX as the button equivalents.
    — `?` → open the shortcuts cheat-sheet overlay listing all active shortcuts for the current page.
    Shortcuts are guarded against firing while focus is inside text inputs, selects, or contenteditable elements.
    Dependency: `H40.1`, `H40.5`.
    Exit criteria: shortcuts work on conflicts, triage, users, researchers, publications, citations, and authors tables; cheat-sheet overlay enumerates them; no shortcut fires while typing in a field; focus ring is visible on the active row in both themes.
    Handover:
    - `frontend/src/modules/shared/adminShortcuts.js` — `initAdminShortcuts({sections, tables})` — reuses `app-shortcuts-*` CSS from H36.11; builds `#admin-shortcuts-overlay`; global `?` toggle and `Escape` close (capture phase); roving tabindex per tbody with `MutationObserver` for dynamic tables; `j`/`k`/`↑`/`↓` nav; per-table `keyActions` map for `e`/`Enter`/`r`/`i`/`d`; all guarded against field focus.
    - `frontend/src/styles/admin-tables.css` — `.app-row--kb-focused` highlight + `:focus` outline using `--app-color-focus`.
    - `frontend/src/app.js` — imports and exposes `window.initAdminShortcuts`.
    - Wired on 6 pages via inline scripts (conflicts, triage, users, publications, citations) and `admin-scholardex-authors.js`; each page passes its own `sections` config for the cheat sheet and `keyActions` for row-level shortcuts.
    - All verify scripts pass; `./gradlew compileJava` clean.

  - [x] `H40.11` **Responsive behavior and accessibility audit.**
    Completed: 2026-04-24.
    Handover:
    - Responsive audit passed: stat-card grids reflow via `app-summary-grid` CSS grid; tables use `app-table-scroll` horizontal scroll with no action buttons hidden on mobile; filter panels, modals, and toolbar collapse correctly on narrow screens; `@media` queries confirm no d-none on action buttons.
    - Accessibility audit passed on all 6 admin pages (publications, citations, users, conflicts, triage, authors): all icon buttons carry `aria-label`; bulk controls carry `aria-label`; column-toggle button carries `aria-haspopup`, `aria-expanded`, `aria-controls`; cheat-sheet overlay reachable via `?` key and dismissible via `Escape`.
    - Two issues found and fixed:
      1. `adminBulkSelect.js` `_updateBar()` — added `aria-live="polite"` and `role="status"` to the bulk bar element so screen readers announce selection-count changes.
      2. `adminColumnToggle.js` button click handler — on open, focus now moves to the first enabled checkbox so keyboard users don't need extra Tab presses.
    - `scripts/verify-template-assets.js` passes clean.

  - [x] `H40.12` **Legacy template cleanup and verification.**
    Completed: 2026-04-26.
    Handover:
    - Deleted orphaned `admin/institution-publications.html` and `admin/group-publications.html` — both routes now redirect to workspace URLs with `#publications` hash.
    - Fixed 4 pre-existing test failures in contract tests:
      1. `AdminConflictControllerContractTest` — `ConflictSummary(long,long,long,long)` constructor called with 3 ints; added 4th arg.
      2. `AdminViewControllerContractTest` — added missing `@MockitoBean GroupManagementFacade`; replaced stale `institutionPublicationsViewRendersExpectedTemplateAndModel` + `institutionPublicationsViewRedirectsWhenInstitutionMissing` with `institutionPublicationsRedirectsToWorkspace`; removed stale `scholardexPublicationsPagesRenderCanonicalTemplates` (wrong controller scope).
      3. `AdminScholardexPublicationViewControllerContractTest` — added missing `@MockitoBean AdminDashboardService`; updated `buildPublicationCitationsView` mock to 3-arg signature; updated `ScholardexCitationsView` constructor to 9-arg form; renamed `searchRouteBuildsPublicationSearchView` → redirect assertion; added `citationSync` stub; added `scholardexPublicationsPagesRenderCanonicalTemplates` with proper stubs.
    - Fixed two pre-existing link correctness issues: citations template author links now point to `/user/authors/view/{id}`; admin-scholardex-authors.js author name column now links to `/user/authors/view/`.
    - Removed redundant manual `${_csrf.parameterName}` hidden input from publications bulk form (`th:action` already injects CSRF).
    - All verification passes: `compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-ui-guardrails`; 34/34 contract tests green.

## Backlog

- [x] `H41` Delete Standalone Publication Wizard (Tier 2.1). *(completed 2026-04-26)*
  Goal: eliminate the dead standalone wizard surface so no user-facing route resolves to it and no template asset validation entry references it.
  Deliverable:
  - Delete `templates/user/publications-add-step1.html`, `step2.html`, `step3.html`.
  - Drop `GET /user/publications/add` (and any `step2`/`step3` counterparts) from the controller; add a `redirect:/user/workspace#publications` in their place for any bookmarked external link.
  - Remove the progressive-enhancement `href="/user/publications/add"` fallback from any workspace button or link that currently carries it; ensure the button triggers the inline wizard directly.
  - Drop the corresponding entries from the `verify-template-assets` allowlist.
  - Confirm no remaining template, JS module, or test references the deleted routes or templates.
  Exit criteria: `verify-template-assets`, `verify-route-guardrails`, and `verify-ui-guardrails` all pass; hitting `/user/publications/add` in a browser redirects to the workspace publications tab; no broken links in workspace HTML.
  Handover:
  - All three wizard templates deleted (`publications-add-step1/2/3.html`).
  - `PublicationWizardController` replaced with single-method redirect class: all `GET /user/publications/add/**` routes redirect to `/user/workspace#publications`.
  - `PublicationWizardControllerContractTest` deleted (no longer applicable).
  - Workspace fallback `href`s changed from `/user/publications/add` to `#` (click handlers already prevent navigation and trigger inline wizard).
  - Onboarding link in workspace updated: `th:href="@{/user/publications/add-step-1}"` → `href="#" data-tab-goto="publications"`.
  - Legacy `publications.html` "Add Publication" button removed (workspace is now the primary path).
  - `verify-template-assets.js` cleaned: removed allowlist entries for step1/step2 external assets and inline scripts.
  - All verify scripts pass; compile is clean; no regressions in existing tests (pre-existing 14 failures unrelated to this task).
  Reference: `docs/tasks/active/ux-redesign-plan-after-tier1.md` §2.1, Phase A.

- [x] `H42` Login Page & Single-Keycloak Institutional Sign-In. *(completed 2026-04-26)*
  Goal: modernize the login page and add one institutional SSO path through Keycloak while preserving the existing local account login contract. The app talks to a single configured Keycloak realm/client; Keycloak handles institutional identity selection and federation outside this app.
  Design reference: `docs/tasks/active/ux-redesign-plan-after-tier1.md` §2.4, scoped as Option C-lite: Option B visual treatment plus SSO area, without in-app institution selector, register flow, or animated forgot/register transitions.
  UX guide reference: `docs/ux-design-guide.md` §1.1, §1.2, §4.1, §6.2, §6.3, §6.6, §8.1.
  Exit criteria: `/login` presents a polished ScholarDex login surface with local email/password form and a clear institutional sign-in action; Spring form-login still posts to `/login` with `username` and `password`; institutional sign-in redirects to `/oauth2/authorization/keycloak`; successful Keycloak login creates or resolves a local `User` principal so existing controllers continue to see `authentication.getPrincipal() instanceof User`; first-time Keycloak users are auto-created as `RESEARCHER` accounts with no usable local password; existing local users keep their locally assigned roles and profiles; unauthenticated MVC routes still redirect to `/login`; API unauthenticated behavior remains JSON 401; logout works for both login types; all work passes `./gradlew compileJava`, targeted auth/security contract tests, `npm run build`, `npm run verify-assets`, and `npm run verify-template-assets`.

  Subtasks:

  - [x] `H42.1` **Login page visual refresh and dual-login layout.** *(completed 2026-04-26)*
    Rework `login.html` into a responsive ScholarDex login surface: local account form, institutional sign-in area, branded wordmark/header, inline error/logout states, light/dark support, and concise onboarding/help copy. Preserve `name="username"`, `name="password"`, `autocomplete="username"`, `autocomplete="current-password"`, `th:action="@{/login}"`, CSRF behavior, and local form submission.
    Exit criteria: local login contract tests still pass; login page includes `/oauth2/authorization/keycloak`; responsive layout collapses cleanly on mobile; no external runtime CSS dependency is introduced beyond existing asset patterns.
    Handover:
    - `login.html` now uses the bundled `core-styles` / `core-scripts` fragments instead of the Bootstrap CDN and inline styles.
    - The page renders a standalone two-panel ScholarDex login surface with local email/password login and an institutional sign-in CTA to `/oauth2/authorization/keycloak`.
    - `frontend/src/styles/login.css` contains the responsive light/dark layout using existing `--app-*` design tokens and is imported by `frontend/src/app.js`.
    - `AuthViewControllerSecurityContractTest` now locks the local form contract, the Keycloak link, and the no-CDN asset contract.
    - Verification passed: `./gradlew test --tests "*AuthViewControllerSecurityContractTest" -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`.

  - [x] `H42.2` **Keycloak OAuth2 client configuration.** *(completed 2026-04-26)*
    Add Spring OAuth2 client support for one registration id, `keycloak`, configured only through environment-backed properties: issuer URI, client id, client secret, and scopes. Keep local form login enabled.
    Exit criteria: app starts without Keycloak config when SSO is disabled or absent; with config present, `/oauth2/authorization/keycloak` initiates OAuth2 login; existing form login still works.
    Handover:
    - Added `spring-boot-starter-oauth2-client`.
    - `KeycloakOAuth2ClientConfig` conditionally creates an in-memory `keycloak` `ClientRegistrationRepository` only when `KEYCLOAK_ISSUER_URI` and `KEYCLOAK_CLIENT_ID` are non-blank, using OIDC discovery and redirect URI `{baseUrl}/login/oauth2/code/{registrationId}`.
    - Keycloak settings are env-backed through `scholardex.oauth2.keycloak.*` properties instead of active blank `spring.security.oauth2.client.*` defaults, because Boot validates blank OAuth registrations at startup.
    - `WebSecurityConfig` permits `/oauth2/**` and `/login/oauth2/**`, and enables `oauth2Login(loginPage("/login"))` only when a `ClientRegistrationRepository` bean exists; local form login/logout/API entry-point behavior is unchanged.
    - `.env.example` documents `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, and `KEYCLOAK_SCOPES`.
    - Targeted coverage added in `AuthViewControllerOAuth2SecurityContractTest`; verification passed: `./gradlew test --tests "*AuthViewController*SecurityContractTest" -q`, `./gradlew compileJava`.

  - [x] `H42.3` **Local user bridge for OIDC principals.** *(completed 2026-04-26)*
    Add an OAuth2/OIDC success path that extracts verified email from Keycloak, resolves the local `User`, or auto-creates a new local `RESEARCHER` account with a generated unusable password. Replace or wrap the authenticated principal so downstream MVC/user controllers continue receiving the local `User` model.
    Exit criteria: existing local users keep their roles/profile; new Keycloak users get only `RESEARCHER`; missing/blank/unverified email fails login with a safe `/login?error` redirect; locked local users cannot sign in through Keycloak.
    Handover:
    - `KeycloakOAuth2LoginSuccessHandler` now converts successful Keycloak OAuth2 logins into local `User` principals by requiring a verified email claim, normalizing it, resolving the local account, or provisioning a new `RESEARCHER` user with a generated password.
    - Existing local users keep their stored roles/profile; locked users, missing email, blank email, and unverified email fail through `/login?error`.
    - `WebSecurityConfig` wires the bridge as the OAuth2 success handler when OAuth2 login is enabled, while preserving local form login.
    - Focused coverage added in `KeycloakOAuth2LoginSuccessHandlerTest`.

  - [x] `H42.4` **Security contract updates.** *(completed 2026-04-26)*
    Extend `WebSecurityConfig` to support both `formLogin` and `oauth2Login` on the same login page. Keep `/login`, static assets, health endpoints, and OAuth2 callback/authorization endpoints permitted as needed. Preserve current MVC redirect and API 401/403 handling.
    Exit criteria: unauthenticated MVC pages redirect to `/login`; unauthenticated `/api/**` returns JSON auth errors; logout invalidates local session for both auth methods; no existing admin/researcher authorization rules are weakened.
    Handover:
    - Existing `WebSecurityConfig` behavior remains unchanged: local form login and conditional Keycloak OAuth2 login share `/login`, OAuth2 callback/authorization endpoints are permitted, and API/MVC exception handling remains split between JSON auth errors and MVC redirects.
    - Contract tests now lock OAuth2 authorization redirect behavior, OAuth2 callback failure handling, POST-only logout, local form-login logout, and logout for the bridged Keycloak local `User` principal.
    - Existing API and MVC security contracts cover JSON 401/403 behavior and unauthorized admin/MVC redirects.

  - [x] `H42.5` **Auth regression tests and documentation notes.** *(completed 2026-04-26)*
    Add focused tests for local login preservation, login page SSO link, OAuth2 user provisioning, existing-user role preservation, locked-user rejection, and unknown first-time Keycloak user auto-creation. Document required Keycloak env vars in the relevant project config/docs surface.
    Exit criteria: targeted auth/security tests pass; compile and frontend verification pass; `.env.example` or equivalent config guidance includes the Keycloak variables without real secrets.
    Handover:
    - `AuthViewControllerSecurityContractTest` covers local form-login preservation, login-page Keycloak CTA, bundled login assets, and logout contracts.
    - `KeycloakOAuth2LoginSuccessHandlerTest` covers existing-user role/profile preservation, new verified-email `RESEARCHER` provisioning, generated local password secret use, email normalization, invalid email claims, locked-user rejection, and local `User` principal bridging.
    - `.env.example` and `docs/authentication.md` document the Keycloak environment variables, redirect URI, verified-email requirement, local-user bridge, ignored Keycloak roles/groups, and app-only logout behavior without real secrets.
    - Verification passed: `./gradlew test --tests "*Keycloak*OAuth2*" -q`, `./gradlew test --tests "*AuthViewController*SecurityContractTest" -q`, `./gradlew compileJava`, `./gradlew test -q`, `npm run verify-assets`, `npm run verify-template-assets`.

  Assumptions:
  - Single Keycloak registration id is `keycloak`.
  - Keycloak is the only institutional auth integration; no in-app institution selector.
  - First-time Keycloak users are auto-created as local `RESEARCHER` users.
  - Role elevation remains local/admin-managed; Keycloak roles are not mapped to `PLATFORM_ADMIN` or `SUPERVISOR` in this task.
  - Forgot password and self-registration remain out of scope.

- [x] `H43` Error Pages Option B — contextual recovery surfaces. *(completed 2026-04-26)*
  Goal: modernize `403`, `404`, `500`, generic error, and shared not-found pages per `docs/tasks/active/ux-redesign-plan-after-tier1.md` §2.5 Option B.
  Deliverable: theme-aware error pages that keep authenticated users inside the ScholarDex app shell, provide clear recovery actions, and show context-specific guidance for permission, not-found, and server-error cases.
  Design reference: `docs/tasks/active/ux-redesign-plan-after-tier1.md` §2.5 Option B; `docs/ux-design-guide.md` §7.6.
  Exit criteria: authenticated errors render with sidebar/navbar/footer; unauthenticated errors render as a standalone ScholarDex-centered surface; no error template loads Bootstrap/CDN scripts; 403 explains permission recovery, 404 offers search/browse suggestions, 500 includes retry action plus timestamp/request context where available; `shared/not-found.html` uses the same visual/error pattern; targeted MVC/template/asset checks pass.

  Subtasks:

  - [x] `H43.1` **Shared error-page presentation baseline.**
    Handover: Added a reusable error-page fragment and bundled error-page CSS/JS through the existing `/assets/app.*` pipeline; legacy Bootstrap/CDN and inline error styling were removed from runtime error templates.

  - [x] `H43.2` **Authenticated shell vs standalone rendering.**
    Handover: Error pages now render inside the app shell when a local `User` principal is present, and render as a standalone ScholarDex surface for unauthenticated sessions.

  - [x] `H43.3` **Context-specific Option B content.**
    Handover: 403 now gives permission guidance, 404 provides browse/search recovery links, 500 shows retry plus timestamp/request context, and generic errors use safe fallback copy.

  - [x] `H43.4` **Controller model metadata.**
    Handover: `CustomErrorController` and `MvcExceptionHandler` now populate consistent error metadata through `ErrorPageModelFactory`.

  - [x] `H43.5` **Shared not-found alignment.**
    Handover: `shared/not-found.html` now uses the shared error-page pattern for missing entity/detail flows.

  - [x] `H43.6` **Regression and guardrail coverage.**
    Handover: Focused MVC tests cover error routing, model metadata, authenticated shell rendering, standalone unauthenticated rendering, and exception-handler metadata; `verify-template-assets` now includes `templates/errors`.

- [x] `H38` User-Reviewed Publication Authorship Overlay. *(completed 2026-04-19)*
  Goal: let researchers confirm or reject authorship for imported publications so noisy Scopus links stop polluting reports, indicators, citations, and workspace views without deleting source data.
  Deliverable: a local authorship-decision layer on top of canonical imported publication links, with review UI, suspicious-publication triage, and reporting/read-model filtering that prefers user decisions over raw source linkage.
  Exit criteria: researchers can mark a publication as `CONFIRMED` or `REJECTED`; rejected publications no longer count toward user-facing reporting, indicators, citations, exports, and workspace lists; confirmed publications remain included even if later imports stay noisy; imported Scopus/DBLP lineage remains preserved and auditable; the system can surface a "needs review" queue for suspicious authorship links instead of requiring users to inspect all publications manually.
  Handover:
  - The full user-reviewed authorship overlay is now in place across workspace review, suspicious triage, bulk decisions, confirmed-only scoring/reporting, cache invalidation, and diagnostics without mutating raw imported lineage.
  - Researchers can review pending publications in one place, constrain review using confirmed affiliation scope, and rely on consistent downstream filtering across user-facing reports, indicators, citations, and exports.
  - Imported linkage and local override state remain distinguishable: the workspace now shows concise provenance on publication rows/details, and the admin publication search exposes compact per-publication override summaries for operational debugging.

  Subtasks:

  - [x] `H38.1` **Authorship decision persistence model.** *(completed 2026-04-16)*
    Add a dedicated persistence model for user-level publication authorship decisions keyed by user + publication, separate from imported Scopus/Scholardex facts.
    Deliverable: document/entity + repository storing `status` (`CONFIRMED` / `REJECTED`), timestamps, decision source, optional reason, and enough immutable context to audit later.
    Exit criteria: imported source facts remain untouched; user decisions can be created, updated, queried, and deleted independently; duplicate decisions per user/publication are prevented.
    Handover:
    - `PublicationAuthorshipDecision` now lives in `scholardex.publication_authorship_decisions` with a unique `userEmail + publicationId` compound index, `CONFIRMED` / `REJECTED` status, `USER_REVIEW` source, timestamps, optional reason, and a compact immutable audit snapshot.
    - `PublicationAuthorshipDecisionRepository` supports single-row lookup, per-user listing, subset lookup by publication ids, and delete-to-clear semantics so implicit pending remains represented by row absence.
    - `PublicationAuthorshipDecisionService` owns upsert/clear/query behavior, validates that the user and publication exist, captures publication/user/authorship snapshot data on write, and leaves imported `ScholardexPublicationFact` / `ScholardexAuthorshipFact` records untouched.
    - Targeted regression coverage now exists in `PublicationAuthorshipDecisionServiceTest` and `PublicationAuthorshipDecisionRepositoryTest`.

  - [x] `H38.2` **Effective-authorship read filtering.** *(completed 2026-04-16)*
    Introduce a publication-authorship overlay in the read/reporting path so user decisions are applied consistently before data reaches indicators, citations, exports, and workspace tabs.
    Deliverable: shared filtering support or projection/read-model layer that excludes locally rejected publications and preserves locally confirmed ones.
    Exit criteria: all user-facing publication/citation/report queries can consume an "effective publications for user" view; no scoring service needs ad-hoc reject logic embedded directly in its scoring rules.
    Handover:
    - `EffectiveAuthorshipReadService` now sits above `ScholardexProjectionReadService`, resolves the user’s raw publication set from canonical author ids, subtracts `REJECTED` publication ids, and re-includes `CONFIRMED` publication ids by direct canonical publication lookup.
    - `UserPublicationFacade` now uses the effective publication set for the main user publication view, and workspace citation drilldown now rejects access when the base publication is not effectively owned by the user.
    - `UserReportFacade` now uses the effective publication set for indicator apply, report-scoped individual report computation, and report-scoped indicator detail, so user-facing report/citation calculations no longer derive owned publications directly from author ids.
    - PostgreSQL reporting views, canonical publication/authorship facts, admin/group/export paths, and scoring rules remain unchanged in this slice; the overlay is applied only in the shared user-scoped read/report assembly layer.
    - Targeted regression coverage now exists in `EffectiveAuthorshipReadServiceTest`, `UserPublicationFacadeTest`, and `UserReportFacadeTest`.

  - [x] `H38.2a` **Confirmed-only scoring inputs.** *(completed 2026-04-16)*
    Make user-scoped scoring and evaluation authoritative by counting only explicitly confirmed publications, while keeping publication discovery broad enough for authorship review.
    Deliverable: a scoring-specific authorship read path and rewired user scoring surfaces that consume only `CONFIRMED` publications, plus a contextual warning when a user has zero confirmed publications.
    Scope:
    - add a scoring-specific read path above `ScholardexProjectionReadService` that returns only confirmed publications for a user
    - rewire user-scoped scoring/evaluation surfaces to use confirmed-only publications:
      - indicator apply
      - evaluation page / report-scoped computation
      - report refresh flows
      - user-scoped scoring exports
    - keep workspace/publication discovery on the broader imported/effective set so pending publications remain reviewable
    - show a warning on scoring/evaluation surfaces only when the user has zero confirmed publications, explaining that only confirmed publications are counted in scoring
    - keep this rule out of the scoring services themselves; filtering stays in the read/assembly layer
    Exit criteria: pending and rejected publications do not contribute to user-scoped scores, totals, charts, or scoring exports; workspace discovery still shows candidate publications for review; users with zero confirmed publications see a clear warning rather than silently misleading results.
    Handover:
    - `EffectiveAuthorshipReadService` now exposes a scoring-specific confirmed-only path via `findConfirmedPublicationsForScoring(...)` and `hasConfirmedPublicationsForScoring(...)`; pending publications no longer enter scoring inputs, while confirmed publications are still reloaded directly by `publicationId`.
    - `UserReportFacade` now uses confirmed-only publications for user-scoped scoring assembly: indicator apply, report-scoped computation, report-scoped detail, and the user scoring export methods; workspace discovery still uses the broader effective-authorship view.
    - Publication-based apply/evaluation surfaces now receive `confirmedPublicationScoringWarning` when the user has zero confirmed publications; activity-only scoring surfaces do not receive that warning.
    - `EvaluationWorkspaceController` now sets the report-level warning based on whether the selected report actually uses publication/citation scoring and whether the user has any confirmed publications for scoring.
    - Targeted regression coverage now exists in `EffectiveAuthorshipReadServiceTest`, `UserReportFacadeTest`, and `EvaluationWorkspaceControllerContractTest`.

  - [x] `H38.3` **Inline confirm/reject actions in researcher publication surfaces.** *(completed 2026-04-16)*
    Add authorship confirmation/rejection controls to the main user-facing publication views, starting with the workspace publications tab and any remaining publication detail/apply flows where authorship confusion is visible.
    Deliverable: UI actions `Confirm mine` / `Reject authorship`, optimistic feedback, and visible authorship state on affected rows/details.
    Exit criteria: a researcher can review and decide authorship from the normal publication workflow without admin intervention; state persists and reflects immediately in the same surface.
    Handover:
    - The workspace publications tab now uses a review-oriented publication list instead of the filtered effective-authorship set, so pending, confirmed, and rejected publications all remain visible for inline review.
    - `UserPublicationsViewModel` now carries `authorshipReviewStateByPublicationId`, and the workspace publications endpoint returns per-publication review state with `PENDING`, `CONFIRMED`, or `REJECTED`, plus optional reason and `updatedAt`.
    - `ResearcherWorkspaceController` now exposes workspace-only authorship decision endpoints: confirm, reject, and clear. All are authenticated and return a compact decision-state JSON response for in-place UI updates.
    - The workspace publications detail panel now includes an “Authorship” section with row-level status badges, one-click confirm, inline two-step reject confirmation, clear decision, and inline success/error feedback. Rejected rows stay visible in place.
    - Targeted regression coverage now exists in `UserPublicationFacadeTest`, `PublicationAuthorshipDecisionServiceTest`, and `ResearcherWorkspaceControllerContractTest`.

  - [x] `H38.4` **Suspicious-authorship triage queue.** *(completed 2026-04-16)*
    Create a targeted "needs review" queue so users are asked only about likely false positives instead of every imported paper.
    Deliverable: heuristics and/or rule-based flags for suspicious authorship links (name mismatch, affiliation mismatch, topic jump, low evidence overlap, etc.) plus a dedicated queue/list in the user workspace.
    Exit criteria: the queue is populated deterministically from explicit heuristics; each flagged publication explains why it was flagged; researchers can confirm/reject directly from the queue.
    Handover:
    - The workspace Publications tab now includes a built-in `Needs review` filter mode rather than a separate page. It is driven by `suspiciousAuthorshipByPublicationId` plus `suspiciousPendingCount` on `UserPublicationsViewModel`, so the queue stays inside the existing master-detail review flow.
    - `SuspiciousAuthorshipTriageService` computes deterministic pending-only suspicion flags from current canonical data using three explicit rules: `NAME_MISMATCH`, `NO_AFFILIATION_OVERLAP`, and `SECONDARY_ID_ONLY`. No suspicion state is persisted.
    - `UserPublicationFacade.buildWorkspacePublicationsView(...)` now enriches the workspace publication payload with suspicious-authorship metadata while leaving scoring and legacy publication surfaces unchanged.
    - The workspace UI now shows a review summary bar, `All` / `Needs review` filters, row-level `Needs review` badges, and a detail-panel explanation block listing the exact heuristic reasons. Confirming or rejecting an item while filtered removes it from the queue immediately and advances context to the next flagged row when possible.
    - Targeted regression coverage now exists in `SuspiciousAuthorshipTriageServiceTest`, `UserPublicationFacadeTest`, `ResearcherWorkspaceControllerContractTest`, and the updated `UserViewControllerContractTest`.

  - [x] `H38.5` **Bulk review workflow.** *(completed 2026-04-18)*
    Support efficient cleanup of polluted Scopus identities by allowing multi-select or repeated queue decisions without opening each publication individually.
    Deliverable: bulk confirm/reject actions with safeguards, summary counts, and undo/rollback-friendly handling where practical.
    Exit criteria: researchers can clear multiple false-positive publications in one operation; accidental mass rejection is guarded by confirmation UX and auditable persisted decisions.
    Handover:
    - The workspace publications experience now treats `Pending Review` as a first-class filter with dedicated pending, suspicious-pending, and recommended-pending summary counts on `UserPublicationsViewModel`, rather than limiting review acceleration to the suspicious queue only.
    - `PublicationAuthorshipDecisionService` now exposes best-effort bulk confirm/reject handling for pending publications only, reusing the existing per-publication decision path, preserving the affiliation-scope eligibility gate, and returning per-item success/failure results instead of one aggregate success state.
    - `ResearcherWorkspaceController` now exposes `POST /user/workspace/publications/authorship/bulk`, with request payload `{ publicationIds, action, reason? }` and response payloads that distinguish succeeded ids, failed ids with messages, and updated review states for successful rows.
    - The workspace publications frontend now supports pending-row selection, current-view select-all, bulk confirm/reject actions, mixed-result feedback, and explicit `Recommended accept` labeling for non-suspicious pending publications while preserving the existing single-item review flow and suspicious reason details.
    - Targeted regression coverage now exists in `PublicationAuthorshipDecisionServiceTest`, `UserPublicationFacadeTest`, `ResearcherWorkspaceControllerContractTest`, and `UserViewControllerContractTest`.

  - [x] `H38.6` **Indicator/report/export integration.** *(completed 2026-04-18)*
    Apply effective-authorship filtering to all user-facing reporting outputs that currently assume imported authorship is correct.
    Deliverable: indicator apply views, report computation, citation lists, workbook exports, and workspace summary counts all use the same effective-authorship layer.
    Exit criteria: rejecting a publication removes it from scores, totals, charts, and exports consistently; confirming a publication preserves inclusion consistently.
    Handover:
    - User-scoped reporting and export computation in `UserReportFacade` now consistently uses the confirmed-only/effective-authorship layer for indicator apply, report-scoped computation, citation detail assembly, indicator workbook export, and both CNFIS workbook export variants.
    - The remaining freshness gap is now closed: `PublicationAuthorshipDecisionService` invalidates user-scoped reporting caches after successful confirm, reject, clear, and successful bulk review mutations, so the next evaluation/detail read recomputes from the latest confirmed publication set instead of reusing stale persisted output.
    - `UserIndicatorResult.Mode.LATEST` rows and transient `UserIndividualReportRun` rows are now treated as disposable caches; durable `SNAPSHOT` indicator results and `EvaluationSnapshot` history remain untouched by authorship-decision invalidation.
    - No user-facing controller or export contract changed in this slice; existing endpoints continue to work, but their next read after an authorship decision is now fresh by construction.
    - Targeted regression coverage now exists in `PublicationAuthorshipDecisionServiceTest`, `UserIndicatorResultServiceTest`, `UserIndividualReportRunServiceTest`, and the existing `UserReportFacadeTest` confirmed-only scoring/export coverage remains in place.

  - [x] `H38.7` **Operational diagnostics and auditability.** *(completed 2026-04-19)*
    Make authorship overrides explainable for both users and maintainers.
    Deliverable: concise provenance on publication rows/details ("Imported from Scopus, locally rejected by user on {date}") and admin/debug visibility into decision state without losing raw source lineage.
    Exit criteria: support/debug flows can distinguish imported linkage from local override decisions; users can see the current authorship status and when it changed.
    Handover:
    - The workspace publications tab now renders concise provenance directly in both the row and the detail view: pending items show imported source lineage, while confirmed/rejected items show imported lineage plus the local decision outcome and decision date; stored decision reasons are also shown in the detail panel when present.
    - This slice keeps the user-facing provenance lightweight by deriving it from existing publication ids (`eid`, `wosId`, `googleScholarId`) plus the current authorship review state, without introducing a new persisted provenance model just for display.
    - The existing admin publication search page now exposes a compact `Authorship overrides` summary column per publication row, including total override count, confirmed/rejected split, and latest decision status/date.
    - `PublicationAuthorshipDecisionRepository` now supports publication-scoped decision lookup across users, and `PostgresScholardexAdminReadPort` aggregates that into `PublicationAuthorshipDecisionAdminSummary` for admin/debug read flows.
    - Targeted regression coverage now exists in `AdminScholardexPublicationViewControllerContractTest`, `PublicationAuthorshipDecisionRepositoryTest`, `ResearcherWorkspaceControllerContractTest`, and `UserPublicationFacadeTest`.

- [ ] `H44` Phase B — Tier 3.1 Shared Component Library (Option C).
  Goal: lock the ScholarDex design system before scaling Tier 2 (admin form modernization) and further UX work by building all missing shared fragments and JS utilities enumerated in the Tier 3.1 Option C decision, then documenting them in `docs/frontend-conventions.md` and migrating existing ad-hoc usages where the diff is small.
  Design reference: `docs/tasks/active/ux-redesign-plan-after-tier1.md` §3.1 Option C.
  UX guide reference: `docs/ux-design-guide.md` §6.2, §6.3, §6.5, §6.6, §6.7, §7.1, §8.1, §8.2.
  Exit criteria: all 10 fragments/components listed below are built, tested, and importable; `docs/frontend-conventions.md` documents each component's shape, variants, and usage contract; existing ad-hoc implementations are migrated to the shared components where the diff is ≤ a few lines per call site; `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava` all pass clean; both light and dark themes render correctly for every new component.

  Subtasks:

  - [x] `H44.1` **`confirmation-dialog` fragment and JS API.** *(completed 2026-04-26)*
    Extract the pattern used ad-hoc in conflicts (bulk resolve/dismiss), bulk select (publisher reassign, group assign), and two-click delete flows into a single reusable `confirmation-dialog(id, title, body, confirmLabel, tone)` Thymeleaf fragment backed by a `confirmationDialog.js` module.
    The JS API must expose `window.appConfirmDialog.open({ dialogId, onConfirm, onCancel })` so callers never manage modal lifecycle directly. The confirm button must use the `--danger` accent when `tone="danger"` and the default primary accent otherwise. Focus must be trapped inside the dialog; Escape cancels; ARIA `role="alertdialog"`, `aria-modal="true"`, `aria-labelledby`, `aria-describedby`.
    Exit criteria: fragment renders a functional confirmation dialog in any template; all existing ad-hoc confirm flows in `conflicts.html`, `user-defined-triage.html`, workspace delete, and bulk select are migrated to the shared fragment; `npm run build` and both verify-assets scripts pass.

  - [x] `H44.2` **Toast notification system.** *(completed 2026-04-26)*
    Build a `toastManager.js` module and supporting `shared-toasts.css` that provide an ephemeral feedback queue: `window.appToast.show({ message, tone, duration, actionLabel, onAction })`. Tones: `success`, `error`, `warning`, `info`. Default duration 4 s; `duration: 0` means sticky until dismissed. Queue renders as a fixed stack in the bottom-right corner (per §8.1), max 5 visible, older toasts pushed up. Each toast is dismissible by X button. ARIA `role="status"` for non-error tones, `role="alert"` for error; `aria-live="polite"` on the container. Both themes.
    Migrate at least the inline `#eval-snapshot-feedback` span and workspace publications save-feedback span to `appToast.show()`.
    Exit criteria: `appToast.show()` is usable from any page after `app.js` loads; toasts stack, auto-dismiss, and are keyboard/screen-reader accessible; migrated call sites no longer use inline feedback spans; `npm run build` passes.

  - [x] `H44.3` **`pagination` fragment (server-side and client-side variants).** *(completed 2026-04-26)*
    Promote the ad-hoc pagination patterns in the publications catalog, citations page, conflicts queue, and workspace tabs into a single `pagination(page, totalPages, baseUrl, pageSizeOptions, currentPageSize)` fragment for server-side contexts and a `clientPagination.js` utility (wraps existing workspace publications/activities pagination logic) for client-side contexts.
    Server-side fragment: prev/next buttons, current-page display, first/last buttons for jump, page-size `<select>` that posts/gets with `size=` param — all per §6.3. ARIA `role="navigation"`, `aria-label="Pagination"`. Both themes.
    Client-side utility: exported `initClientPagination({ data, pageSize, renderFn, container })` replacing the hand-rolled per-module pagination in `workspacePublications.js` and `workspaceActivities.js`.
    Exit criteria: server-side fragment renders and navigates correctly on publications catalog, citations, conflicts, and triage pages; client-side utility replaces duplicated paging code in workspace modules; `npm run build` and all verify scripts pass.

  - [x] `H44.4` **`filter-panel` fragment.** *(completed 2026-04-26)*
    Promote the integrated filter patterns from `.app-queue-filter` (conflicts/triage) and the publications catalog inline filter to a shared `filter-panel(formId, method, action, fields)` fragment. Fields are passed as a list of `FilterFieldDef` objects (name, label, type: `text|select|date`, options list for select). The panel has a visually connected header that folds into the table's top border (per §6.3), a Reset link that clears all fields and submits, and an Apply button that submits the form. ARIA `role="search"` on the form. Responsive: collapses to a stack on narrow screens.
    - Added `FilterFieldDef` / `FilterOptionDef`, shared `filter-panel(...)` Thymeleaf fragment, and `shared-filter-panel.css`.
    - Migrated admin conflicts and Scholardex publication catalog filter panels to the shared fragment while preserving hidden cross-link filters for publication catalog applies.
    Migrate conflicts, triage, and publications catalog filter blocks to the shared fragment.
    Exit criteria: shared fragment renders in all three migrated contexts; existing filter behavior is preserved; `npm run build` and all verify scripts pass.

  - [x] `H44.5` **`stat-card` fragment.** *(completed 2026-04-26)*
    Consolidate the ad-hoc `.app-summary-grid` / `stat-card` pattern (used across admin dashboard, conflicts, users, publications, citations, evaluation aggregate panel) into a single `stat-card(label, value, accent, contextLine, icon)` Thymeleaf fragment. Accent values: `primary`, `success`, `warning`, `danger`, `neutral`. Context line is optional secondary text (e.g. "last 30 days"). Icon is optional Font Awesome class. Cards compose into a grid via a `stat-card-grid(cards)` fragment that auto-reflows to single column on mobile (per §4.4).
    - Added `StatCardDef`, shared `stat-card(...)` and `stat-card-grid(cards)` fragments, and primary/neutral/icon styling on the existing summary-card foundation.
    - Migrated admin conflicts, users, Scholardex publications, and Scholardex citation detail stat grids to the shared grid fragment with render-contract assertions.
    Migrate all existing `.app-summary-grid` usages that are ≤ 5 cards with static values to the shared fragment.
    Exit criteria: fragment renders correctly on admin users, publications, citations, conflicts, and evaluation aggregate panels; single-column reflow works at 576 px; both themes; `npm run build` passes.

  - [x] `H44.6` **Generic `breadcrumb` fragment.** *(completed 2026-04-26)*
    Add a non-admin `breadcrumb(items)` fragment mirroring the existing `admin-breadcrumb` shape but using workspace-appropriate styling (lighter, no admin-sidebar assumption). Items are `BreadcrumbItem` objects with `label` and optional `href`. The last item renders as `aria-current="page"` without a link. Integrate with the existing `admin-breadcrumb` so both share the same CSS class structure and can be toggled by a `variant` param (`admin` vs `default`), or keep as two separate named fragments if the diff is trivial.
    Apply generic breadcrumbs to: workspace sub-flows that need back-navigation context (e.g. evaluation criterion deep links), public pages (Tier 2.3 prep).
    Exit criteria: fragment renders in at least two non-admin contexts; `npm run build` passes; screen reader announces breadcrumb correctly.
    - Added `BreadcrumbItem`, shared `breadcrumb(items, variant)` fragment, and `admin-breadcrumb(items)` wrapper using the same class structure.
    - Applied default breadcrumbs to forum detail, WoS category detail, and university detail; migrated Scholardex citation detail to the admin wrapper.

  - [x] `H44.7` **`admin-form` fragment.** *(completed 2026-04-26)*
    Build a `admin-form(id, action, method, title, sections, submitLabel, cancelHref)` Thymeleaf fragment providing the shared admin form shell: sticky header with title and Save/Cancel controls, `<section>` blocks with heading and helper text slot, consistent field layout (label above input, error message below), and CSRF token injection. Replaces the ad-hoc form layouts in the long-form admin edit pages (`edit-individualReport.html`, `edit-groupReport.html`, `indicators-edit.html`).
    CSS: `.app-admin-form` BEM block in `admin-tables.css` (or new `admin-forms.css`); sticky header uses `position: sticky; top: 0` with a white/dark background so it stays visible during scroll.
    Exit criteria: fragment is usable by at least one long-form admin edit page (apply to `edit-individualReport.html` as the pilot); Save/Cancel sticky behavior works; `npm run build` and `verify-template-assets` pass.
    - Added the shared `admin-form(...)` and `admin-form-section(...)` fragments with CSRF injection, sticky Save/Cancel controls, section helper text, and slotted body content.
    - Added `admin-forms.css`, imported it into the bundled frontend assets, and migrated `edit-individualReport.html` as the pilot long-form edit page.
    - Covered the pilot with a controller render test asserting the shared shell, sticky action header, cancel route, and section output.

  - [x] `H44.8` **`modal-shell` fragment.** *(completed 2026-04-26)*
    Build a `modal-shell(id, title, size, footerSlot)` Thymeleaf fragment providing a reusable Bootstrap modal wrapper with: `role="dialog"`, `aria-modal="true"`, `aria-labelledby` wired to title, focus trap (first focusable element on open, returns to trigger on close), Escape-key close via JS, and consistent header/body/footer layout. Wrap the existing `#editUserModal` in `users.html` and the bulk-action modals in publications/users as the pilot migration.
    The JS module (`modalShell.js` or integrated into `app.js`) must expose `window.appModal.open(id)` and `window.appModal.close(id)` as a thin wrapper above Bootstrap's modal API, adding the focus-trap and return-focus behavior that Bootstrap 5 does not guarantee consistently.
    Exit criteria: `modal-shell` renders three existing modals correctly; focus trap and return-focus work; Escape closes; `npm run build` and both verify-assets scripts pass.
    - Added shared `modal-shell(...)` with title wiring, close control, body/footer slots, and the `data-app-modal-shell` marker for JS behavior.
    - Added `modalShell.js` exposing `window.appModal.open(id)` / `window.appModal.close(id)`, including focus trap, Escape close, backdrop close, Bootstrap-style lifecycle events, and return-focus handling.
    - Migrated `#editUserModal`, `#assignGroupModal`, and `#reassignForumModal`; legacy modal handling now skips migrated shell modals.
    - Covered the users and publication catalog pilots with render-contract assertions for shell IDs, ARIA labels, and shell markers.

  - [x] `H44.9` **`search-input` fragment.** *(completed 2026-04-26)*
    Build a `search-input(id, name, placeholder, value, kbdHint, clearable)` fragment that produces a styled search input with optional `<kbd>` shortcut hint (hidden when input is focused, per `app-ws-search__hint` pattern) and optional clear (×) button. Mirrors the existing `app-ws-search__field` shape from the workspace but packaged as a standalone fragment usable outside the workspace (admin catalog filter search, public page search, evaluation page search). CSS goes into `shared-forms.css` (new file) or is added to `admin-tables.css`.
    Apply the fragment to: the publications catalog title-search field, the evaluation page (if a search input is present), and the workspace unified-search field (as a drop-in swap).
    Exit criteria: fragment renders in all three applied contexts; clear button empties the input and triggers form submit or `input` event; shortcut hint shows/hides correctly; both themes; `npm run build` passes.
    - Added shared `search-input(...)` with optional keyboard hint, clear button, accessible icon treatment, and bundled clear-button behavior via `searchInput.js`.
    - `filter-panel(...)` now renders text fields through `search-input(...)`, covering the Scholardex publications title search while preserving form submission.
    - Migrated the workspace unified search and public directory searches for forums, core rankings, and WoS categories while preserving existing input IDs for current JS modules.
    - Covered the migrated admin/public/workspace contexts with targeted render/template assertions.

  - [x] `H44.10` **Button group and icon-button conventions documentation.** *(completed 2026-04-26)*
    No new code required. Audit the existing `.app-admin-icon-btn` usages and the workspace action-button patterns and write up the button taxonomy in `docs/frontend-conventions.md`: icon-only action button, labeled action button, danger variant, disabled state, button group with divider. Include HTML snippet examples, ARIA requirements (every icon-only button must have `aria-label`), and tone-to-CSS-class mapping. Flag any existing call sites that are missing `aria-label` and fix them as part of this subtask.
    Exit criteria: button-group section added to `docs/frontend-conventions.md`; all icon-only admin buttons across all admin templates carry `aria-label`; `npm run verify-ui-guardrails` passes.
    - Added button taxonomy guidance to `docs/frontend-conventions.md` covering icon-only buttons, labeled actions, tone classes, disabled state, and action groups with snippets.
    - Audited admin icon-only controls; `.app-admin-icon-btn` call sites were already labeled, and repeated admin scroll-to-top anchors now carry `aria-label="Scroll to top"`.

  - [x] `H44.11` **`frontend-conventions.md` documentation pass and ad-hoc migration cleanup.** *(completed 2026-04-27)*
    With all components built, do a final pass:
    — Add a "Shared Components" section to `docs/frontend-conventions.md` documenting each of the 10 components: purpose, Thymeleaf fragment signature, JS API (if any), CSS block name, variants, and when to use vs. when not to use.
    — List the existing components that were already built before H44 (`tab-bar`, `admin-breadcrumb`, `admin-empty-state`, skeleton loader, shortcuts overlay, bulk-select, column-toggle) with the same documentation shape for completeness.
    — Identify any remaining ad-hoc usages of patterns now covered by shared components that were not migrated in H44.1–H44.9 and file them as known technical debt in a `docs/tasks/active/component-library-debt.md` note (do not attempt a full sweep in this subtask).
    — Run the full verification suite and confirm a clean pass.
    Exit criteria: `docs/frontend-conventions.md` "Shared Components" section is complete and accurate; `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-ui-guardrails`, and `./gradlew compileJava` all pass; debt note filed if any remaining ad-hoc usages were found.
    - Added the shared component catalog to `docs/frontend-conventions.md`, covering all H44 components plus the pre-existing tab bar, empty state, skeleton, shortcuts, bulk-select, and column-toggle utilities.
    - Filed remaining ad-hoc usages in `docs/tasks/active/component-library-debt.md` for future focused migrations.
    - Completed the H44 verification closeout with build, asset/template/route/UI guardrails, and Java compilation.

- [x] `H45` Phase C — Admin Form Modernization (Tier 2.2 Option D). *(completed 2026-04-27)*
  Goal: modernize admin edit workflows by collapsing short edit pages into row-edit modals powered by the shared `modal-shell` fragment, while keeping genuinely long configuration forms as dedicated pages using the shared `admin-form` baseline.
  Design reference: `docs/tasks/active/ux-redesign-plan-after-tier1.md` §2.2 Option D and Phase C.
  UX guide reference: `docs/ux-design-guide.md` §6.2, §6.3, §6.5, §6.6, §6.7, §8.1, §8.2.
  Exit criteria: short admin edit pages no longer require full-page navigation; row-level Edit actions open accessible shared modals with pre-populated values, Save/Cancel behavior, validation/error placement, focus return, Escape close, and toast/status feedback; long-form admin edit pages use the shared `admin-form` shell with sticky Save/Cancel controls, section headings, helper text, breadcrumbs, and consistent field layout; legacy edit URLs redirect to their parent list/detail surface where practical; deleted templates are removed from template guardrails; both light and dark themes render correctly; `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava` pass clean.

  Subtasks:

  - [x] `H45.1` **Admin edit form inventory and modal/dedicated classification.** *(completed 2026-04-27)*
    Audit the Tier 2.2 candidate edit pages and record the chosen treatment at the top of the implementation handoff. Default rule: convert forms with simple scalar fields or shallow lists to row-edit modals; keep forms with nested repeaters, builders, or many related collections as dedicated pages.
    Initial classification: convert `scholardex-editForum.html`, `scholardex-editAffiliation.html`, `edit-institutions.html`, and `domains-edit.html` to modals; keep `edit-group.html`, `activities-edit.html`, `edit-individualReport.html`, `edit-groupReport.html`, `indicators-edit.html`, and `activity-indicators.html` as dedicated surfaces unless the audit proves a form is simpler than it currently appears.
    Exit criteria: each candidate form has an explicit modal-vs-dedicated decision before migration starts.
    Handover:
    - Modal conversions confirmed for `scholardex-editForum.html` and `scholardex-editAffiliation.html`: both are scalar-only edit forms (forum: name/ISSN/eISSN/ISBN/aggregation type/publisher; affiliation: name/city/country) and their parent catalogs already render async action columns from `admin-scholardex-forums.js` / `admin-scholardex-affiliations.js`.
    - Modal conversions confirmed for `edit-institutions.html` and `domains-edit.html`: both are shallow list forms that match their existing create-modal workflows (institution profile plus Scopus/WoS affiliation rows; domain details plus WoS category rows). Keep saves on the existing create/update contracts unless H45.3/H45.4 choose JSON for smoother row refresh.
    - Dedicated pages confirmed for `edit-group.html` and `activities-edit.html`: group editing has dynamic domain/member collections plus workspace navigation, and activity editing has nested fields, allowed values, and reference-field repeaters. Migrate their layout to `admin-form` in H45.5 instead of forcing them into modals.
    - Dedicated pages confirmed for `edit-individualReport.html`, `edit-groupReport.html`, and `indicators-edit.html`: report definitions and indicators contain criteria, thresholds, scoring strategies, formula/configuration fields, and dynamic builders. `edit-individualReport.html` already uses the shared `admin-form` pilot from H44.7; H45.6 should finish the same baseline for the remaining report/indicator pages.
    - `activity-indicators.html` is not a standalone edit-template candidate in current code; it is a list/create/delete configuration surface returned by `AdminActivityController#getActivityIndicators`. Treat it as a dedicated configuration surface if touched during H45.6, not as a row-edit modal migration target.

  - [x] `H45.2` **Scholardex forum and affiliation row-edit modals.** *(completed 2026-04-27)*
    Replace `admin/scholardex-editForum.html` and `admin/scholardex-editAffiliation.html` with row-edit modals on `admin/scholardex-forums.html` and `admin/scholardex-affiliations.html`.
    Use `modal-shell`, row Edit icon buttons, preloaded row data or a compact JSON/detail endpoint, existing save facades, toast feedback, and focus return. Redirect old edit URLs back to the parent catalog.
    Exit criteria: forum and affiliation edits complete without leaving the catalog pages; old edit templates are unused.
    Handover:
    - `admin/scholardex-forums.html` now includes an `editForumModal` shared `modal-shell`; the async table action renders an Edit button instead of a full-page edit link.
    - `admin-scholardex-forums.js` fetches `/admin/scholardex/forums/{id}/edit-data`, populates the modal, posts the existing `/admin/scholardex/forums/edit/{id}` form contract with CSRF headers, closes the modal, shows a toast, and refreshes the current table page.
    - `admin/scholardex-affiliations.html` now includes an `editAffiliationModal` shared `modal-shell`; the async table action renders Publications + Edit actions and removes the name-as-edit-link pattern.
    - `admin-scholardex-affiliations.js` fetches `/admin/scholardex/affiliations/{id}/edit-data`, posts the existing `/admin/scholardex/affiliations/edit/{id}` contract with CSRF headers, closes the modal, shows a toast, and refreshes the current table page.
    - `AdminViewController` legacy edit GET routes now redirect to their parent catalogs; compact JSON edit-data endpoints serve modal population; POST handlers keep the existing save facades and now redirect to parent catalogs.
    - Focused verification passed: `./gradlew test --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.3` **Institution row-edit modal.** *(completed 2026-04-27)*
    Move `admin/edit-institutions.html` into an edit modal on `admin/institutions.html`, preserving name, description, Scopus affiliation mapping, and WoS affiliation fields.
    Keep existing create/delete behavior intact, and make Save update the row or refresh the page consistently after success.
    Exit criteria: institution edits happen from the institutions list; `/admin/institutions/edit/{id}` redirects to `/admin/institutions`.
    Handover:
    - `admin/institutions.html` now renders an `editInstitutionModal` shared `modal-shell` beside the existing create modal. Row actions use an Edit button with `data-edit-institution-id` instead of navigating to the standalone edit page.
    - The modal preserves institution name/description, Scopus affiliation rows, and WoS affiliation rows. It reuses the existing `/admin/institutions/update` POST contract and refreshes the page after a successful modal save so the server-rendered table stays authoritative.
    - Added `/admin/institutions/{id}/edit-data` JSON endpoint for modal population. Legacy `GET /admin/institutions/edit/{id}` now redirects to `/admin/institutions`.
    - Existing create/delete behavior is unchanged. The shared collection helpers now add generated Scopus rows as `scopusAffiliations[index].afid` so modal-generated rows bind to affiliation ids directly.
    - Focused verification passed: `./gradlew test --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.4` **Domain row-edit modal.** *(completed 2026-04-27)*
    Move `admin/domains-edit.html` into an edit modal on `admin/domains.html`, preserving name, description, and WoS category assignment behavior.
    Reuse existing category option data already loaded for the domains page.
    Exit criteria: domain edits happen from the domains list; `/admin/domains/edit/{id}` redirects to `/admin/domains`.
    Handover:
    - `admin/domains.html` now renders an `editDomainModal` shared `modal-shell` beside the existing create modal. Row actions use an Edit button with `data-edit-domain-id` instead of navigating to the standalone edit page.
    - The modal preserves domain name/description and WoS category rows. It reuses the existing `/admin/domains/update` POST contract and refreshes the page after a successful modal save so the server-rendered table remains authoritative.
    - Added `/admin/domains/{id}/edit-data` JSON endpoint for modal population. Legacy `GET /admin/domains/edit/{id}` now redirects to `/admin/domains`.
    - Existing create/delete behavior is unchanged. Shared category helpers populate both create and edit modal rows from the existing `allWosCategories` model data.
    - Focused verification passed: `./gradlew test --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.5` **Dedicated admin-form baseline for complex group and activity editors.** *(completed 2026-04-27)*
    Keep `admin/edit-group.html` and `admin/activities-edit.html` as dedicated pages, but migrate their layout to the shared `admin-form` shell.
    Preserve dynamic domain/member rows for groups and dynamic activity field/reference-field rows for activities. Add breadcrumbs back to the parent list and consistent Save/Cancel placement.
    Exit criteria: group and activity edit pages match the shared admin form pattern without changing their data contract.
    Handover:
    - `admin/edit-group.html` now uses the shared `admin-form` shell with breadcrumbs, sticky Save/Cancel actions, and sections for group profile, domains, institution, and researchers. Existing dynamic domain/member row behavior and the `/admin/groups/update` POST contract are preserved.
    - `AdminGroupController#editGroup` now supplies breadcrumbs, `adminFormObject`, and the `allDomains` alias needed by the modernized form while keeping existing model attributes intact.
    - `admin/activities-edit.html` now uses the shared `admin-form` shell with breadcrumbs and sections for activity profile, activity fields, and referenced fields. Existing dynamic field, allowed-value, and reference-field JavaScript behavior and the `/admin/activities/update` POST contract are preserved.
    - `AdminActivityController#editActivity` now supplies breadcrumbs and `adminFormObject`; existing reference-field rows submit indexed `referenceFields[n]` names so they match dynamically added rows.
    - Focused verification passed: `./gradlew test --tests '*AdminGroupControllerContractTest' --tests '*AdminActivityControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.6` **Dedicated admin-form baseline for report and indicator configuration.** *(completed 2026-04-27)*
    Complete the long-form baseline for `admin/edit-groupReport.html`, `admin/indicators-edit.html`, and any remaining report/indicator configuration holdouts not already covered by H44.7.
    Keep these as dedicated pages because they contain report criteria, scoring rules, or configuration builders.
    Exit criteria: all surviving long-form admin edit pages use the shared `admin-form` pattern or an explicitly documented equivalent.
    Handover:
    - `admin/edit-groupReport.html` now uses the shared `admin-form` shell with breadcrumbs, sticky Save/Cancel actions, and sections for report metadata, report indicators, and the criteria builder. Existing dynamic indicator, criterion, and threshold rows and the `/admin/groupReports/update` POST contract are preserved.
    - `AdminGroupReportsController` now supplies `adminFormObject`, breadcrumbs, and `allPositions` for both edit and apply routes through a shared edit-model helper.
    - `admin/indicators-edit.html` now uses the shared `admin-form` shell with breadcrumbs and sections for indicator identity/activity mapping and scoring rules. Existing scoring inputs, helper text, activity-description sync attributes, and the `/admin/indicators/update` POST contract are preserved.
    - `AdminViewController#editIndicator` now supplies `adminFormObject` and breadcrumbs. `edit-individualReport.html` remains the existing H44.7 shared-form baseline, and `activity-indicators.html` remains unchanged because it is a list/create/delete configuration surface rather than a dedicated edit page.
    - Focused verification passed: `./gradlew test --tests '*AdminGroupReportsControllerContractTest' --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.7` **Legacy template, route, and guardrail cleanup.** *(completed 2026-04-27)*
    Remove dead short edit templates after their parent-list modals are live, update route guardrails/template asset checks, and make legacy edit GET routes redirect to canonical parent pages.
    Do not remove POST endpoints that are still used by modal saves unless they are replaced by tested JSON endpoints.
    Exit criteria: no deleted template is referenced by controllers, tests, or verification scripts.
    Handover:
    - Removed the dead short edit templates `admin/scholardex-editForum.html`, `admin/scholardex-editAffiliation.html`, `admin/edit-institutions.html`, and `admin/domains-edit.html` after their row-edit modals and redirect compatibility routes were in place.
    - Kept the existing Scholardex forum/affiliation POST edit endpoints because the row-edit modals still submit to those save contracts.
    - Updated the institution workspace Edit action to return to the canonical `/admin/institutions` surface instead of linking to the removed full-page editor.
    - Updated async Scholardex forum/affiliation JS behavior tests to assert row edit buttons rather than legacy full-page edit links.
    - Extended route guardrails to fail if removed short edit templates reappear and to reject stale domain/institution edit page links in runtime templates.
    - Focused verification passed: `node scripts/test-admin-scholardex-forums.js`, `node scripts/test-admin-scholardex-affiliations.js`, `./gradlew test --tests '*AdminViewControllerContractTest' -q`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

  - [x] `H45.8` **Regression coverage and UI verification.** *(completed 2026-04-27)*
    Add or update MVC/render-contract tests for each migrated modal and each surviving dedicated admin form. Cover modal shell markers, ARIA labels, populated edit data, save endpoints, redirect compatibility, and guardrail expectations.
    Exit criteria: targeted controller/template tests pass, frontend build and guardrails pass, and Java compilation is clean.
    Handover:
    - Completed the Phase C regression closeout across migrated modal forms and surviving dedicated admin forms. Existing tests cover modal shell markers, ARIA labels, edit-data JSON, legacy redirect compatibility, and shared `admin-form` markers for group, activity, report, indicator, and individual-report editors.
    - Added explicit short-edit modal save contract coverage for Scholardex forum, Scholardex affiliation, domain, and institution updates, including redirect targets and facade-bound model values.
    - Verified async Scholardex table behavior with row edit buttons and canonical publication links through the dedicated JS behavior tests.
    - Full H45 closeout verification passed: `./gradlew test --tests '*AdminViewControllerContractTest' --tests '*AdminGroupControllerContractTest' --tests '*AdminActivityControllerContractTest' --tests '*AdminGroupReportsControllerContractTest' --tests '*AdminIndividualReportsControllerContractTest' -q`, `node scripts/test-admin-scholardex-forums.js`, `node scripts/test-admin-scholardex-affiliations.js`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `npm run verify-route-guardrails`, `npm run verify-ui-guardrails`, and `./gradlew compileJava`.

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.
