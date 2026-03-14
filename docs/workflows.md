# Workflows

Status: active project workflow summary.

## Admin Operational Flows

- Initialization and rebuild flows run through admin maintenance surfaces and canonical build/projection services.
- Conflict and source-link triage use the existing admin investigatory surfaces instead of one-off workflow-specific tooling.
- Ranking/reporting cutovers require projection/readiness verification before steady-state use.

## User Flows

- Dashboard and personal views run under the canonical `/user/*` route family.
- Publication and citation views, report refreshes, and workbook exports depend on projection-backed and canonicalized data.
- User-defined publication onboarding uses the existing publication wizard surface and should materialize through canonical `USER_DEFINED` lineage.
- H21.1 lock (2026-03-14): route family remains `/user/publications/add`; contract-level source/keying target is `USER_DEFINED` (see `docs/tasks/active/h21.1-user-defined-wizard-onboarding-contract.md`) while runtime migration lands in H21.2+.

## Shared Read Flows

- Shared entity pages are role-aware but route-stable.
- Shared routes should read from the current canonical projection/read-model path for the entity.
- Legacy duplicate admin/user read surfaces remain removed.

## Migration Guidance

- New workflow docs should capture the steady-state flow, not every intermediate migration step.
- Task-specific workflow detail belongs under `docs/tasks/active/` or `docs/tasks/closed/`.
