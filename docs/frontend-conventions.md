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

## UI Structure

- Shared entity pages should stay entity-first and role-aware.
- Admin-only management pages remain under `/admin/**`.
- User-owned flows remain under `/user/*`.

## Verification

- Use the frontend and route guardrails for template, asset, and canonical-route changes.
