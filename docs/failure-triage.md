# Failure Triage

Status: active failure-triage baseline.

## When Guardrails Fail

Inspect in this order:

1. The failing script/test output
2. Related route, contract, or asset guardrails
3. Admin conflict/source-link surfaces when the failure involves canonicalization
4. Application logs and actuator metrics for runtime or operability failures

For USER_DEFINED wizard-onboarding failures, inspect in this order:

1. `/admin/user-defined-triage` snapshot counts and recent lineage rows.
2. `/admin/source-links?source=USER_DEFINED` for link-state drift.
3. `/admin/conflicts?incomingSource=USER_DEFINED` for unresolved/ambiguous identity conflicts.
4. `/admin/initialization/user-defined/*` run outcomes (buildFacts, canonicalize, runAll).
5. `H19_TRIAGE canonical_build` logs and `core.h21.user_defined.*` gauges/counters.

## Common Areas

- Route/canonical UI regressions
- Source-link and identity-conflict drift
- USER_DEFINED source-fact/canonicalization drift
- Projection rebuild/readiness drift
- Asset and template contract regressions
- CI workflow and required-check drift

## Documentation Rule

- Keep durable triage guidance here.
- Keep task-specific failure evidence and closeout proof under `docs/tasks/closed/`.
