# H10 Release Hygiene Baseline

Date: 2026-03-04  
Status: active baseline for release-affecting changes.

## 1. Purpose

Define a minimal, repeatable merge/release hygiene contract so production-facing changes include risk visibility, rollback readiness, and verification evidence.

## 2. PR Checklist (Release-Affecting Changes)

Apply this checklist when the change impacts runtime behavior, security, persistence, CI gates, or operability.

1. Scope is focused; unrelated files are excluded.
2. Risk notes are explicit (behavioral, data, security, operability).
3. Rollback note is included:
   - what can be reverted safely,
   - what requires data/config rollback.
4. Verification evidence is attached:
   - command list,
   - pass/fail outcomes,
   - justification for any skipped command.
5. Docs are updated for changed contracts/commands/policies.

## 3. Merge Gate Checklist

1. Required CI checks are green:
   - `guardrails`
   - `java-smoke`
   - `quality-full`
   - `dependency-review`
   - `codeql-analysis`
2. No unresolved “temporary bypass” without:
   - linked issue,
   - owner,
   - expiry date.
3. PR description includes final risk + rollback notes.
4. At least one reviewer confirms command evidence is sufficient for change scope.

## 4. Evidence Command Baseline

Default baseline:

```bash
npm run build
npm run verify-assets
npm run verify-template-assets
npm run verify-duplication-guardrails
npm run verify-architecture-boundaries
./gradlew test
```

CI-sensitive parity:

```bash
npm run verify-h09-baseline
```

Scope-specific commands are selected via:

- `docs/h10-quality-gates-matrix.md`

## 5. Rollback Note Template

Use this compact structure in PRs:

1. Rollback trigger: `<what issue triggers rollback>`
2. Code rollback: `<commit/PR revert path>`
3. Data/config rollback: `<properties/indexes/migrations to revert or re-run>`
4. Post-rollback verification: `<commands to confirm stable state>`

## 6. Non-Goals

This baseline does not define:

1. full release calendar/versioning policy,
2. deployment orchestration runbook,
3. SLO/incident response playbooks.

Those can be introduced in follow-up operational tracks.
