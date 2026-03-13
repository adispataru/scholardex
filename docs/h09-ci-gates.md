# H09 CI Gates Enforcement Baseline

Date: 2026-03-04  
Status: enforced baseline for CI-required quality and security gates.

## 1. Objective

Operationalize existing local guardrails as CI gates so regressions are visible early and, after rollout, blocked before merge.

## 2. Scope

H09 baseline now enforces GitHub Actions coverage for:

1. `npm run verify-architecture-boundaries`
2. `npm run verify-h06-persistence`
3. `npm run verify-h07-guardrails`
4. `npm run verify-h08-baseline`
5. `./gradlew compileJava`
6. `./gradlew test --tests "*CoreApplicationTests"`
7. `npm run verify-assets`
8. `npm run verify-template-assets`
9. `./gradlew check`
10. dependency review gate on pull requests
11. CodeQL analysis gate on pull requests and pushes to `main`

Still out of scope:

- SAST/container scanning,
- non-GitHub CI providers.

## 3. Workflow Contract

Workflow files:

1. `.github/workflows/h09-quality-gates.yml`
2. `.github/workflows/h09-security-gates.yml`

Jobs:

1. `guardrails`
2. `java-smoke`
3. `quality-full`
4. `summary` (informational, non-required)
5. `dependency-review`
6. `codeql-analysis`

Runtime baseline:

1. Java: Temurin 25 for all Java-based CI jobs (`java-smoke`, `quality-full`, `codeql-analysis`)
2. Node.js: 20 for frontend/guardrail jobs
3. Gradle: project wrapper (`./gradlew`) with non-daemon invocation in CI build/test/check steps

Branch-protection required checks (enforced target):

1. `guardrails`
2. `java-smoke`
3. `quality-full`
4. `dependency-review`
5. `codeql-analysis`

## 4. Enforcement Policy

- Required checks are configured in GitHub branch protection for `main`.
- `summary` remains informational and non-blocking.
- Any temporary downgrade of required checks must have:
  1. linked tracking issue,
  2. named owner,
  3. expiry date,
  4. explicit restore criteria.

## 5. Flake Handling Policy

If the same required check flakes twice within 7 days:

1. open a tracking issue with owner and expiry date,
2. document scope and rollback criteria,
3. temporary non-blocking mode is allowed only with explicit expiry and follow-up task,
4. restore required mode immediately after fix.

Rerun policy limits:

1. max one manual rerun per failing required check per PR;
2. if rerun still fails, treat as real failure and open/attach flake issue if non-deterministic;
3. do not merge with bypass unless approved by repository admins and tracked with expiry.

## 6. Local Parity Commands

Run before pushing CI-sensitive changes:

1. `npm run verify-h09-baseline`
2. `./gradlew test --tests "*CoreApplicationTests"` (quick smoke sanity)
3. `npm run verify-h12-integrations` when touching Scopus/import integration paths

For H23 route/UI changes that affect canonical Scholardex or category navigation, also run:

4. `npm run verify-h23-ui`

`verify-h09-baseline` contract:

1. `verify-architecture-boundaries`
2. `verify-h06-persistence`
3. `verify-h07-guardrails`
4. `verify-h08-baseline`
5. `verify-assets`
6. `verify-template-assets`
7. `./gradlew check`

Expected runtime profile:

1. local developer machine: typically slower than CI smoke jobs due to full `check`;
2. run before PR when touching build/security/test infrastructure;
3. use targeted commands during inner-loop development and run full baseline before push.

H23 note:

- `npm run verify-h23-ui` is a contributor-facing route/UI sanity suite for the canonical H23 navigation model.
- It is not part of `verify-h09-baseline` and is not automatically a required CI gate unless a later task explicitly promotes it.

Troubleshooting:

1. inspect uploaded workflow artifacts (`guardrails-logs`, `quality-full-artifacts`, `java-smoke-test-report`);
2. rerun failed job once;
3. if flaky, open issue with owner + expiry and link in PR.

## 7. Notes

- CI logs and test report artifacts are uploaded on failure for faster triage.
- Required-check pass-rate should be tracked manually in weekly engineering notes for the first 2 weeks after enforcement.
- H09 baseline evidence (2026-03-04):
  - quality workflow includes `guardrails`, `java-smoke`, `quality-full`;
  - security workflow includes `dependency-review`, `codeql-analysis`.
