# Workflows

Status: active project workflow summary.

## Admin Operational Flows

- Initialization and rebuild flows run through admin maintenance surfaces and canonical build/projection services.
- Conflict and source-link triage use the existing admin investigatory surfaces instead of one-off workflow-specific tooling.
- Ranking/reporting cutovers require projection/readiness verification before steady-state use.

### Scopus Maintenance Workflow

- Upload-driven Scopus maintenance runs through `/admin/incremental-updates/scopus` and stays batch-scoped:
  - ingest uploaded Scopus payload
  - build facts
  - run batch-scoped canonical materialization
  - optionally run batch projection rebuild, edge reconcile, and source-link repair follow-up for that stored upload batch
- Scheduler publication/citation maintenance also stays batch-scoped:
  - ingest scheduler-fetched publication/citation payloads into import events
  - run batch-scoped canonical materialization
  - run batch-scoped projection refresh
- Full Scopus initialization and rebuild flows remain explicit full-corpus maintenance under `/admin/initialization`.
- Contributor rule: incremental Scopus changes must preserve batch scope, replay safety, and non-destructive projection behavior; full-rebuild assumptions such as full rescans or `TRUNCATE`-style replacement must not leak into upload or scheduler flows.

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
