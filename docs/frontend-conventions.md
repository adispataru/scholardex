# Frontend Conventions

Status: active frontend and template guidance.

## Template Rules

- Shared route pages must use the unified sidebar composition path.
- Canonical route families and canonical template names must be preserved.
- Removed route aliases and stale template tokens must not reappear in templates or JS.

## Asset Rules

- Runtime assets must go through the existing asset build/verification flow.
- Transitional third-party assets or inline-script exceptions must stay explicit and guarded.
- New exceptions should be rare and documented alongside the verification guardrail change.
- The shared authenticated shell remains owned by `src/main/resources/templates/fragments.html`, with shell styles and behavior implemented through `frontend/src/**` and emitted via `/assets/app.css` and `/assets/app.js`.
- SB Admin 2 stylesheet and script assets are no longer part of the authenticated frontend baseline.
- Shared-shell work must not reintroduce SB Admin 2 dependency into authenticated templates, fragments, or the authored frontend asset contract.
- Touched frontend work must not introduce new Bootstrap 4-only UI APIs or classes where Bootstrap 5-compatible replacements exist.
- Touched frontend work must not introduce new jQuery-driven UI behavior as the intended steady-state interaction model.
- `data-bs-theme` on the root element is the shared theme-state source of truth for migrated shell work.

## UI Structure

- Shared entity pages should stay entity-first and role-aware.
- Admin-only management pages remain under `/admin/**`.
- User-owned flows remain under `/user/*`.
- Shared-shell behavior and styling should move into repo-owned shared fragments and `frontend/src/**` rather than further coupling to legacy SB Admin assets.
- Shared-shell and theme behavior should converge on repo-owned design tokens and theme-aware styles in `frontend/src/**`.
- Touched UI should converge on Bootstrap 5-compatible markup/data APIs and one ScholarDex design language that supports both light and dark modes.
- Shared table/list styling should converge on the repo-owned table foundation in `frontend/src/**` rather than treating raw `table table-bordered` or BS4 DataTables styling as the visible contract.
- Touched list/table pages should use the shared ScholarDex table classes and section structure instead of inventing page-local table presentation systems.
- Shared form/workflow styling should converge on the repo-owned workflow foundation in `frontend/src/**` rather than treating Bootstrap 4 modal, tooltip, collapse, or input-group presentation as the visible contract.
- Touched form/workflow pages should use the shared ScholarDex workflow classes for step shells, section grouping, action bars, helper content, and workflow control panels instead of inventing page-local form systems.
- Touched form/workflow work must not preserve Bootstrap 4 modal/tooltip/input-group styling as the intended baseline on migrated surfaces.
- Shared dashboard/summary/feedback styling should converge on the repo-owned summary foundation in `frontend/src/**` rather than treating SB Admin stat cards, placeholder dashboard shells, page-local chart wrappers, or raw `alert alert-*` blocks as the visible contract.
- Touched dashboard, summary, and mixed workspace pages should use the shared ScholarDex summary classes for summary grids, summary panels, chart frames, empty states, and feedback surfaces instead of inventing page-local summary systems.
- Touched dashboard/summary work must not preserve SB Admin stat-card or Bootstrap 4 alert/card styling as the intended baseline on migrated surfaces.
- After `H35`, the authenticated shell, table/list, workflow, summary, footer, and shared runtime families share one ScholarDex-owned frontend baseline, and touched work must preserve that accessibility, responsive, cross-theme, and remnant-cleanup contract.
- Remaining Bootstrap 4 or SB Admin debt is intentionally bounded to untouched non-authenticated or otherwise deferred legacy pages until a later active task reopens them.

## Button Conventions

- Icon-only admin row actions use `.app-admin-icon-btn` and must include one accessible name with `aria-label`. The visible content should remain icon-only, and the icon must be `aria-hidden="true"`.
- Labeled actions use the existing `btn` classes and may include a leading Font Awesome icon marked `aria-hidden="true"`. The visible label is the accessible name; do not also add a competing `aria-label`.
- Icon-button tones map as follows: neutral/default uses `.app-admin-icon-btn`, destructive uses `.app-admin-icon-btn--danger`, cautionary uses `.app-admin-icon-btn--warning`, and positive/resolve uses `.app-admin-icon-btn--success`.
- Disabled buttons should use native `disabled`. Use `aria-disabled="true"` only for links that must remain anchors, and prevent activation in JS when doing so.
- Button groups should use existing flex wrappers such as `.app-admin-actions`, `.app-form-actions`, `.app-workflow__actions`, or `.app-bulk-select-bar__actions`. Keep destructive actions visually separated when they appear beside primary actions.

```html
<button type="button" class="app-admin-icon-btn" aria-label="Edit user">
    <i class="fa-solid fa-pen fa-xs" aria-hidden="true"></i>
</button>
<button type="submit" class="app-admin-icon-btn app-admin-icon-btn--danger" aria-label="Delete user">
    <i class="fa-solid fa-trash fa-xs" aria-hidden="true"></i>
</button>
```

```html
<button type="button" class="btn btn-primary btn-sm">
    <i class="fa-solid fa-plus fa-sm" aria-hidden="true"></i> New User
</button>
```

```html
<div class="app-admin-actions">
    <button type="button" class="app-admin-icon-btn" aria-label="Edit item">
        <i class="fa-solid fa-pen fa-xs" aria-hidden="true"></i>
    </button>
    <button type="submit" class="app-admin-icon-btn app-admin-icon-btn--danger" aria-label="Delete item">
        <i class="fa-solid fa-trash fa-xs" aria-hidden="true"></i>
    </button>
</div>
```

```html
<button type="button" class="btn btn-primary btn-sm" disabled>Save</button>
<a class="btn btn-outline-secondary btn-sm disabled" href="#" aria-disabled="true">Export</a>
```

## Shared Components

Use these shared fragments and JS utilities before adding page-local variants. Keep HTML in `src/main/resources/templates/fragments.html`, behavior in `frontend/src/modules/shared/**`, and styling in the matching `frontend/src/styles/**` file.

### H44 Components

| Component | Contract | Use | Avoid |
| --- | --- | --- | --- |
| Confirmation dialog | `confirmation-dialog(id, title, body, confirmLabel, tone)` plus `window.appConfirmDialog.open({ title, body, confirmLabel, tone, onConfirm })`; CSS: `.app-confirm-dialog`; tones: `primary`, `danger`. | Destructive or irreversible confirmation flows that need an accessible modal prompt. | New `window.confirm()` calls or page-local confirmation modals. |
| Toast notifications | `window.appToast.show({ message, tone, duration, actionLabel, onAction })`; CSS: `.app-toast`; tones: `success`, `error`, `warning`, `info`. | Ephemeral success/error/status feedback after client-side actions. | Inline feedback spans for transient feedback unless the message must remain in the form layout. |
| Pagination | Server fragment `pagination(prevHref, nextHref, page, totalPages, showingText, label)` and client helpers `buildPaginationHtml(...)` / `wirePaginationClicks(...)`; CSS: `.app-pagination`. | Server-rendered page navigation and client-rendered table panels. | New `.app-table-pager` markup for touched paginated surfaces. |
| Filter panel | `filter-panel(formId, method, action, fields)` with `FilterFieldDef`; CSS: `.app-filter-panel`. | Table/list filter surfaces that submit as a form. | Page-local queue/filter panels for touched tables. |
| Stat cards | `stat-card(label, value, accent, contextLine, icon)` and `stat-card-grid(cards)` with `StatCardDef`; CSS: `.app-summary-card`, `.app-summary-grid`; accents: `primary`, `success`, `warning`, `danger`, `neutral`. | Compact metric summaries and dashboard stat rows. | Hand-written stat-card grids when the values are already available server-side. |
| Breadcrumbs | `breadcrumb(items, variant)` and `admin-breadcrumb(items)` with `BreadcrumbItem`; CSS: `.app-breadcrumb`; variants: `default`, `admin`. | Navigation context above detail/workspace pages. | Hand-built breadcrumb lists on touched pages. |
| Admin form | `admin-form(id, action, method, title, sections, submitLabel, cancelHref)` and `admin-form-section(title, helperText, body)`; CSS: `.app-admin-form`. | Long-form admin edit pages that need sticky Save/Cancel controls and consistent sections. | New `.app-form-surface` shells on admin edit pages. |
| Modal shell | `modal-shell(id, title, size, bodySlot, footerSlot)` plus `window.appModal.open(id)` / `window.appModal.close(id)`; CSS: `.app-modal-shell`; sizes: empty, `sm`, `lg`, `xl`. | Reusable Bootstrap-shaped modals with shared focus, Escape, backdrop, and return-focus behavior. | New raw `modal fade` blocks on touched pages. |
| Search input | `search-input(id, name, placeholder, value, kbdHint, clearable)` plus clear behavior from `searchInput.js`; CSS: `.app-search-input`. | Search fields in filters, headers, and table controls. | Plain text inputs for search on touched pages. |
| Button conventions | CSS: `.app-admin-icon-btn`, `.app-admin-actions`, `.app-form-actions`, `.app-workflow__actions`; tones: default, `--danger`, `--warning`, `--success`. | Icon-only admin row actions, labeled actions, and grouped action controls. | Icon-only controls without `aria-label` or custom action wrappers when an existing group fits. |

### Existing Shared Components

| Component | Contract | Use | Avoid |
| --- | --- | --- | --- |
| Tab bar | `tab-bar(tabs, callbacksRef)` or existing `[data-app-tab-bar]` markup with `workspaceTabs.js`; CSS: `.app-tab-bar`. | Sectioned pages with keyboard-accessible tabs and optional lazy callbacks. | Page-local tab state managers for touched tabbed surfaces. |
| Admin empty state | `admin-empty-state(...)`; CSS: `.app-admin-empty` / `.app-admin-empty-state`. | Empty rows or standalone empty panels in admin tables and lists. | Empty table rows that only say “No data” without guidance. |
| Skeleton loaders | CSS: `.app-skeleton-*`; helpers in `workspacePanelLoader.js` and workspace modules. | Async panel/table/chart loading placeholders. | Spinners for larger table or chart loading areas. |
| Shortcuts overlays | `workspaceShortcuts.js` and `adminShortcuts.js`; CSS: `.app-shortcuts-*` and focused-row styles. | Keyboard help overlays and row navigation on guarded workspace/admin tables. | Hidden keyboard shortcuts without discoverable help. |
| Bulk select | `initAdminBulkSelect({ tableKey, fingerprint, cbSelector, selectAllSelector, barSelector, countSelector, bulkFormId, inputName })`; CSS: `.app-bulk-select-bar`. | Cross-page admin table selection and bulk forms. | Page-local selected-id tracking on touched admin tables. |
| Column toggle | `initAdminColumnToggle({ tableId, tableEl, toolbarActionsEl, columns })`. | Optional admin table column visibility controls. | One-off column visibility controls that do not persist consistently. |

## Verification

- Use the frontend and route guardrails for template, asset, and canonical-route changes.
- For touched post-`H35` authenticated frontend work, the minimum targeted set is `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, and `npm run verify-route-guardrails`.
- Add `npm run verify-ui-guardrails` only when the touched templates or JS intersect the guarded route/UI surfaces already covered by that script.
