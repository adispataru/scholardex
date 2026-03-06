# Project Recovery Tasks (High-Level)

Objective: raise runtime functional quality from baseline-safe behavior to production-grade correctness and resilience.

Done history moved to `TASKS-done.md`.

## Backlog

- [ ] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical user/admin business workflows end-to-end under realistic conditions.
  Deliverable: focused high-value functional test suite (multi-step user/admin/report/export flows) with deterministic fixtures and clear pass/fail contracts.
  Exit criteria: top business workflows are validated across success and failure paths, and functional regressions are caught before merge by repeatable automated checks.
  Subtasks:
  - [ ] `H13.1` Admin WoS maintenance end-to-end flow.
    Deliverable: deterministic workflow test for admin-triggered WoS maintenance chain (ingest -> facts -> projections -> verify) including authorization checks.
    Exit criteria: success and unauthorized/failure paths are both asserted.
  - [ ] `H13.2` User indicator refresh/export workflow.
    Deliverable: deterministic workflow test covering indicator refresh and export from user-facing flow.
    Exit criteria: refresh updates persisted score state and export contract remains stable.
  - [ ] `H13.3` Failure-path workflow gate.
    Deliverable: at least one full workflow-level degraded/failure scenario with deterministic error handling assertions.
    Exit criteria: failure mode is reproducible and blocks regressions.

- [x] `H15` CI guardrail realignment and quality-gate restoration.
  Goal: restore trust in CI by aligning guardrail rules with the current post-H14 architecture and enforcing the complete guardrail set in GitHub workflows.
  Deliverable: updated guardrail scripts/workflows and a green full validation baseline (`verify-h09-baseline` + `gradlew check`) on compliant code.
  Exit criteria: CI fails only on real regressions (not stale policy checks), and required guardrails are consistently enforced on PR/push.
  Status: completed on 2026-03-06.
  Note: H15.1-H15.4 completed; guardrail scripts and quality workflows now align with post-H14 behavior and pass on rerun.
  Subtasks:
  - [x] `H15.1` Guardrail policy audit for stale assumptions.
    Deliverable: inventory of guardrails that still encode pre-H14 behavior (WoS cache and old CS dispatch assumptions).
    Exit criteria: each stale check has a documented intended replacement aligned with current architecture.
    Status: completed on 2026-03-06.
    Note: see `docs/h15-guardrail-policy-audit.md` for stale/valid classification, source-of-truth mappings, and H15.2 decision-locked script updates.
    H15.2 handoff:
    - `verify-h06-persistence`: remove WoS ranking-cache/repository assertions for `CacheService`; keep edit/update canonical `findById` checks while allowing `buildCitationsView` `id/eid` compatibility fallback.
    - `verify-duplication-guardrails`: replace publication `bk/ch` delegation expectation with non-`ar/re/cp` empty-score policy; keep activity `Book/Book Series` delegation requirement unchanged.
  - [x] `H15.2` Guardrail script updates.
    Deliverable: update `verify-h06-persistence` and `verify-duplication-guardrails` to reflect current intended behavior.
    Exit criteria: scripts pass on compliant code and fail on true policy regressions.
    Status: completed on 2026-03-06.
    Note: script-only update completed in line with `docs/h15-guardrail-policy-audit.md`; no runtime service code changed for this task.
  - [x] `H15.3` GitHub workflow enforcement completion.
    Deliverable: ensure quality workflows execute the full required guardrail set (including WoS parity baseline/integration checks) with failure artifacts.
    Exit criteria: PR/push pipelines consistently run and enforce the updated guardrails.
    Status: completed on 2026-03-06.
    Note: `h09-quality-gates.yml` guardrails job now runs a single explicit guardrail suite (`verify-architecture-boundaries`, `verify-h06-persistence`, `verify-h07-guardrails`, `verify-h08-baseline`, `verify-h12-integrations`, `verify-duplication-guardrails`, `verify-wos-parity-baseline`) with per-check CI logs and failure artifact upload.
  - [x] `H15.4` Full quality-gate recovery.
    Deliverable: restore green status for `npm run verify-h09-baseline` and `./gradlew check`.
    Exit criteria: both gates pass end-to-end and remain stable across reruns.
    Status: completed on 2026-03-06.
    Note: validated with repeated local runs of `npm run verify-h09-baseline` and `./gradlew check`; all checks passed consistently.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
