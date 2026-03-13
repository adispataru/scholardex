# H10 Quality Gates Matrix

Date: 2026-03-04  
Status: active contributor command map for change-scope validation.

## 1. Purpose

Provide one deterministic matrix for contributors to pick the right verification commands by change scope, while keeping local checks aligned with enforced CI gates (`H09`).

## 2. Baseline for All PRs

Run this minimum baseline before opening a PR:

```bash
npm run build
npm run verify-assets
npm run verify-template-assets
npm run verify-duplication-guardrails
npm run verify-architecture-boundaries
./gradlew test
```

For CI-sensitive changes (build/test/security/workflow), run:

```bash
npm run verify-h09-baseline
```

## 3. Change Type -> Required Commands

| Change type | Required commands | Why |
|---|---|---|
| Refactor with behavior-preservation risk | `npm run verify-h03-baseline` and `npm run verify-h04-baseline` | Protect existing transport/service behavior and reliability guardrails. |
| Repository query or data-access changes | `npm run verify-h04-mongo-integration` and `npm run verify-h06-persistence` | Validate Mongo contract behavior and persistence consistency rules. |
| Security/validation/error-handling changes | `npm run verify-h07-guardrails` | Prevent regressions in auth/validation/error contracts. |
| Observability/operability changes | `npm run verify-h08-baseline` | Preserve logging/metrics/readiness guardrails. |
| CI workflow / quality gate changes | `npm run verify-h09-baseline` | Ensure local parity with enforced required checks. |
| Startup/config wiring changes | `./gradlew bootRun -m` and `./gradlew test --tests "*CoreApplicationTests"` | Verify task graph wiring and context startup smoke. |
| Frontend template/asset changes | `npm run build`, `npm run verify-assets`, `npm run verify-template-assets` | Enforce template/asset contract and generated bundle integrity. |
| H23 route-map or canonical UI navigation changes | `npm run verify-h23-ui`, `npm run verify-template-assets`, `./gradlew test --tests "*RankingViewControllerContractTest" --tests "*AdminViewControllerContractTest" --tests "*UserViewControllerContractTest"` | Protect canonical Scholardex/category routes, compatibility redirects, and route-owned browserless UI behavior. |

If multiple change types apply, run the union of command sets.

## 4. PR Evidence Guidance

In PR description, include:

1. command list actually executed,
2. pass/fail outcome summary,
3. any intentionally skipped command with reason.

## 5. CI Mapping Reference

Final required checks on `main` (see `docs/h09-ci-gates.md`):

1. `guardrails`
2. `java-smoke`
3. `quality-full`
4. `dependency-review`
5. `codeql-analysis`

Use `npm run verify-h09-baseline` as the local parity shortcut before pushing CI-sensitive changes.
