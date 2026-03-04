# H09 CI Gates Bootstrap

Date: 2026-03-04  
Status: active rollout for CI-required quality gates.

## 1. Objective

Operationalize existing local guardrails as CI gates so regressions are visible early and, after rollout, blocked before merge.

## 2. Scope

This bootstrap slice adds GitHub Actions coverage for:

1. `npm run verify-architecture-boundaries`
2. `npm run verify-h06-persistence`
3. `npm run verify-h07-guardrails`
4. `npm run verify-h08-baseline`
5. `./gradlew compileJava`
6. `./gradlew test --tests "*CoreApplicationTests"`

Out of scope in this bootstrap:

- full `./gradlew check`,
- SAST/container scanning,
- non-GitHub CI providers.

## 3. Workflow Contract

Workflow file: `.github/workflows/h09-quality-gates.yml`

Jobs:

1. `guardrails`
2. `java-smoke`
3. `summary` (informational, non-required)

Branch-protection required checks (Stage 2 target):

1. `guardrails`
2. `java-smoke`

## 4. Rollout Policy

## Stage 1 (soft visibility)

- Workflow runs on `pull_request` and `push` to `main`.
- Checks are visible, but branch protection required checks are not yet switched on.
- Default Stage 1 window:
  - 3–5 business days or
  - at least 10 PR runs.

Monitor during Stage 1:

1. failure rate by job,
2. flaky behavior,
3. runtime/cost trend.

Exit criteria for Stage 2:

1. at least 5 consecutive green runs on default branch,
2. no unresolved flaky failures for `guardrails`/`java-smoke`.

## Stage 2 (hard required checks)

- In GitHub branch protection for `main`, set required status checks:
  - `guardrails`
  - `java-smoke`
- `summary` remains informational.

## 5. Flake Handling Policy

If the same check flakes twice within 7 days:

1. open a tracking issue with owner and expiry date,
2. document scope and rollback criteria,
3. temporary non-blocking mode is allowed only with explicit expiry and follow-up task,
4. restore required mode immediately after fix.

## 6. Local Parity Commands

Run before pushing CI-sensitive changes:

1. `npm run verify-architecture-boundaries`
2. `npm run verify-h06-persistence`
3. `npm run verify-h07-guardrails`
4. `npm run verify-h08-baseline`
5. `./gradlew compileJava`
6. `./gradlew test --tests "*CoreApplicationTests"`

## 7. Notes

- CI logs and test report artifacts are uploaded on failure for faster triage.
- This bootstrap keeps strictness moderate for speed/stability; expanded H09 checks can be added in follow-up slices.
