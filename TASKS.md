# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
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

- [ ] `H18` WoS ranking enrichment (computed fallback data + admin control page).
  Goal: enrich WoS ranking records with computed values for fields missing in import files, without overriding values explicitly provided by source files.
  Deliverable: enrichment flow that computes `rank`, `quartile`, and `quartileRank` per `category + edition`, plus an admin page to run/inspect enrichment.
  Exit criteria: for each `category + edition`, source-provided values are preserved; missing values are deterministically computed; admins can run and validate enrichment from a dedicated page.
  Subtasks:
  - [ ] `H18.1` Define enrichment computation contract.
    Deliverable: documented deterministic rules for `rank`, `quartile`, and `quartileRank` at `category + edition` scope, including tie handling and null/insufficient-data behavior.
    Exit criteria: rules are unambiguous and implementation-ready.
  - [ ] `H18.2` Integrate enrichment into WoS ingestion/projection flow.
    Deliverable: service-level enrichment step that preserves source values and computes only missing fields.
    Exit criteria: persistence reflects "source if present, computed otherwise" for all three fields.
  - [ ] `H18.3` Add admin backend endpoints for enrichment operations.
    Deliverable: secured admin endpoints to trigger enrichment and retrieve summary results (processed, computed, preserved, failed).
    Exit criteria: authorized admins can execute enrichment and get deterministic run summaries.
  - [ ] `H18.4` Build dedicated admin page for WoS enrichment.
    Deliverable: admin UI page to start enrichment runs and review per-run outcome metrics.
    Exit criteria: page is accessible to admins only and supports operational verification.
  - [ ] `H18.5` Backfill historical WoS records.
    Deliverable: one-time/backfill-capable execution path for existing data.
    Exit criteria: historical records are enriched according to the same contract, with idempotent rerun behavior.
  - [ ] `H18.6` Add regression and integration test coverage.
    Deliverable: tests for preservation logic, computation correctness, and admin trigger flow.
    Exit criteria: automated tests cover success paths and key failure/edge cases.

