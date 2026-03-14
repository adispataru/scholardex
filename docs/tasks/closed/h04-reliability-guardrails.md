# H04-S06 Reliability and Runtime Guardrails
Status: active indexed source-of-truth document.

Date: 2026-03-03

## 1. Command Matrix

- Default refactor safety gate:
  - `npm run verify-h04-baseline`
- Mongo repository integration contract gate (Docker/Testcontainers required):
  - `npm run verify-h04-mongo-integration`
- Broader all-tests sanity:
  - `./gradlew test`

## 2. Runtime Guardrail Policy (Soft Budgets)

Runtime reporting is non-blocking for threshold breaches and blocking only for script integrity failures.

- Reporting command:
  - `node scripts/verify-test-runtime.js`
- Inputs:
  - `build/test-results/test/TEST-*.xml`
- Reported metrics:
  - suite count
  - test count
  - summed reported suite time
  - top slowest suites

Soft thresholds:

- total reported suite time target: `10.0s`
- per-suite warning threshold: `0.5s`

Behavior:

- Threshold breach => `WARN` output (no failing exit code).
- Missing/corrupt test result input => failing exit code with actionable message.

## 3. Deterministic Execution Rules

- New tests should avoid nondeterministic time/data behavior unless explicitly controlled.
- Prefer deterministic fixtures and explicit ordering assertions only when order is business-significant.
- Keep high-level guards focused on stable observable contracts (headers/view/model/workbook structure).

## 4. Flaky Test Triage Workflow

Flake policy: if a test flakes twice in 7 days, quarantine or rewrite before adding more tests in that area.

Quarantine note template:

- Test: `<class>#<method>`
- Owner: `<team or person>`
- First flaky date: `YYYY-MM-DD`
- Last flaky date: `YYYY-MM-DD`
- Interim action: `quarantined|rewriting`
- Expected fix slice: `<task or PR>`

Re-enable criteria:

- at least 5 consecutive local/CI passes,
- deterministic root cause documented,
- quarantine note removed/closed in docs/tasks.

## 5. G08 Integration Infrastructure Decision

G08 is implemented with Testcontainers Mongo through dedicated repository integration tests.

- Strategy: Testcontainers-backed `@DataMongoTest` contracts.
- Baseline inclusion: **separate command** (`verify-h04-mongo-integration`) to keep default gate fast/stable.
- Initial coverage scope:
  - `ScopusPublicationRepositoryIntegrationTest`
  - `ScopusCitationRepositoryIntegrationTest`
