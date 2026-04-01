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
- Shared-shell modernization must not introduce new SB Admin 2 dependency or preserve SB Admin 2 as the intended shell baseline.
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

## Verification

- Use the frontend and route guardrails for template, asset, and canonical-route changes.
- For touched shared-shell work, the minimum targeted set is `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, and `npm run verify-route-guardrails`.
- Add `npm run verify-ui-guardrails` only when the touched templates or JS intersect the guarded route/UI surfaces already covered by that script.
