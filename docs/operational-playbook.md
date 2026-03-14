# Operational Playbook

Status: active operational recovery and maintenance baseline.

## Primary Triage Surfaces

- `/admin/conflicts`
- `/admin/source-links`
- `/admin/user-defined-triage`
- `/admin/initialization`
- actuator metrics and canonical build/reconcile logs

USER_DEFINED deep-link filters:
- `/admin/source-links?source=USER_DEFINED`
- `/admin/conflicts?incomingSource=USER_DEFINED`
- `/admin/initialization` -> `USER_DEFINED Initialization` section (`/admin/initialization/user-defined/*`)

## Standard Recovery Sequence

1. Re-run the relevant canonical build or initialization step.
2. Reconcile source links if identity/linkage drift is involved.
3. Reconcile derived edges if traversal/link edges are affected.
4. Rebuild projections or reporting read models.
5. Re-run the targeted validation/guardrail baseline for the affected area.

For USER_DEFINED onboarding incidents, prefer:

1. Open `/admin/user-defined-triage` and confirm source-fact/link/conflict counts.
2. Run `/admin/initialization/user-defined/buildFacts` when source fact counts lag import events.
3. Run `/admin/initialization/user-defined/canonicalize` (optionally with source-link/edge reconcile).
4. Run `/admin/initialization/user-defined/runAll` for full maintenance when state is uncertain.
5. Validate recovery via filtered source-link/conflict pages and USER_DEFINED metrics.

## Operational Expectations

- Recovery steps must be replay-safe and deterministic.
- Rebuilds must not duplicate canonical facts, links, or projections.
- Postgres read cutovers must fail loudly if required read ports or projection state are unavailable.

## Documentation Rule

- Keep user/operator-facing playbook guidance here.
- Keep task-closeout evidence and slice-specific rollback notes under `docs/tasks/closed/`.
