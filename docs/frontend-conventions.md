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

## Verification

- Use the frontend and route guardrails for template, asset, and canonical-route changes.
- For touched post-`H35` authenticated frontend work, the minimum targeted set is `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, and `npm run verify-route-guardrails`.
- Add `npm run verify-ui-guardrails` only when the touched templates or JS intersect the guarded route/UI surfaces already covered by that script.
