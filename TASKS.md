# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Backlog

- [ ] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical user/admin workflows across the current canonical architecture (Mongo ingest/facts, WoS/Scopus initialization flows, PostgreSQL reporting projections, and user-facing report/export reads).
  Deliverable: focused workflow-level tests for the highest-value operational paths, using deterministic fixtures and asserting state transitions across controller -> orchestration -> persistence/read-model boundaries.
  Exit criteria: top workflows that now span canonical ingest, projection rebuilds, Postgres-backed reads, and user report/export surfaces are validated across success and failure paths, and regressions are caught before merge by repeatable automated checks.
  Subtasks:
  - [ ] `H13.1` Admin WoS maintenance end-to-end flow.
    Deliverable: deterministic workflow test for the modern admin WoS initialization path, covering canonical WoS steps (`ingest -> build facts/onboarding -> enrich category rankings -> build projections`) plus verification/readiness assertions for the resulting read models.
    Exit criteria: the workflow validates the current initialization surface under `/admin/initialization`, confirms step ordering and expected persistence/read-model effects, and asserts both authorized execution and unauthorized access behavior.
  - [ ] `H13.2` User indicator refresh/export workflow.
    Deliverable: deterministic workflow test covering a representative user reporting path across persisted indicator refresh, individual-report refresh, and workbook export using the current user surfaces and projection-backed read dependencies.
    Exit criteria: refresh actions persist/update the expected run/result state, downstream user-facing views resolve from the refreshed state without recomputation drift, and export response contracts remain stable for the selected workflow.
  - [ ] `H13.3` Failure-path workflow gate.
    Deliverable: at least one full workflow-level degraded scenario exercising a modern critical path failure, such as initialization/projection verification failure, Postgres reporting readiness mismatch, or user report refresh/export degradation.
    Exit criteria: the selected failure mode is reproducible with deterministic fixtures, produces explicit user/operator-visible behavior, and blocks regressions in the corresponding workflow.

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.

- [ ] `H21` User-defined source onboarding into Scholardex.
  Goal: support user-triggered non-Scopus/WoS/Scholar publication imports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for user-defined imports modeled as source events/facts with deterministic IDs and moderation/approval metadata.
  Exit criteria: user-defined publications and related entities imported via user operations integrate with the same Scholardex identity and lineage contracts.
  Dependency: execute after `H19.9` citation canonicalization to avoid EID-coupled citation gaps for user-only publications.
