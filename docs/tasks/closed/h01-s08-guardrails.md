# H01-S08 Guardrails
Status: historical archived reference; not authoritative for current work.

Date: 2026-03-03

## Summary

This slice adds lightweight enforcement to prevent reintroduction of known high-risk duplication/drift patterns identified in H01.

## Guardrail Command

- Local/CI command:
  - `npm run verify-duplication-guardrails`

## Enforced Rules

1. Legacy CNFIS artifacts must remain removed:
   - `CNFISScoringService.java`
   - `CNFISReport.java`
2. `ScoringFactoryService` must not use `return null` fallback for strategy resolution.
3. `ComputerScienceScoringService` must keep aligned book dispatch:
   - publication subtypes `bk/ch` -> book scorer
   - activity forum types `Book/Book Series` -> book scorer
4. Asset contract stays centralized:
   - `scripts/build-assets.js` and `scripts/verify-assets.js` must import `scripts/assets-contract.js`.

## Workflow Integration

- `build.gradle`:
  - Added `verifyDuplicationGuardrails` task.
  - Wired into `check` so standard quality flow enforces guardrails.
- `CONTRIBUTING.md`:
  - Local verification checklist now includes `npm run verify-duplication-guardrails`.
  - Added explicit guardrail policy note.
