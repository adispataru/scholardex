# Operational Playbook

Status: active operational recovery and maintenance baseline.

## Primary Triage Surfaces

- `/admin/conflicts`
- `/admin/source-links`
- `/admin/initialization`
- actuator metrics and canonical build/reconcile logs

## Standard Recovery Sequence

1. Re-run the relevant canonical build or initialization step.
2. Reconcile source links if identity/linkage drift is involved.
3. Reconcile derived edges if traversal/link edges are affected.
4. Rebuild projections or reporting read models.
5. Re-run the targeted validation/guardrail baseline for the affected area.

## Operational Expectations

- Recovery steps must be replay-safe and deterministic.
- Rebuilds must not duplicate canonical facts, links, or projections.
- Postgres read cutovers must fail loudly if required read ports or projection state are unavailable.

## Documentation Rule

- Keep user/operator-facing playbook guidance here.
- Keep task-closeout evidence and slice-specific rollback notes under `docs/tasks/closed/`.
