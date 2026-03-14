# Failure Triage

Status: active failure-triage baseline.

## When Guardrails Fail

Inspect in this order:

1. The failing script/test output
2. Related route, contract, or asset guardrails
3. Admin conflict/source-link surfaces when the failure involves canonicalization
4. Application logs and actuator metrics for runtime or operability failures

## Common Areas

- Route/canonical UI regressions
- Source-link and identity-conflict drift
- Projection rebuild/readiness drift
- Asset and template contract regressions
- CI workflow and required-check drift

## Documentation Rule

- Keep durable triage guidance here.
- Keep task-specific failure evidence and closeout proof under `docs/tasks/closed/`.
