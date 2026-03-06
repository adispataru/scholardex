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

- [ ] `H17` Scopus canonical import pipeline transition.
  Goal: replace direct Scopus document writes with a canonical ingestion pipeline aligned to WoS patterns (`events -> facts -> views`) for deterministic replay and safer evolution.
  Deliverable: high-level migration to Scopus import events ledger, normalized Scopus facts layer, and projection views consumed by application/reporting flows.
  Exit criteria: Scopus ingest is replayable/idempotent from source events, canonical facts are the single derivation source, read paths use stable views/contracts, and parity/guardrail checks protect against regressions.
  Assumption lock (2026-03-06): big-bang cutover for all Scopus entities; no historical data migration/backfill is required (clean-state bootstrap only).
  Subtasks:
  - [ ] `H17.1` Canonical Scopus contract lock.
    Deliverable: `docs/h17-scopus-canonical-contract.md` with canonical collections, required fields, identity keys, lineage fields, and source-policy rules for publications, citations, forums, authors, affiliations, and funding.
    Exit criteria: schema, identity, and source policy are decision-locked before implementation changes.
  - [ ] `H17.2` Canonical storage and index baseline.
    Deliverable: canonical Mongo collection/index definitions for Scopus import events, normalized facts, and read views with idempotence-oriented unique constraints.
    Exit criteria: fresh environment creates canonical storage deterministically with required uniqueness/index coverage.
  - [ ] `H17.3` Event ledger ingestion pipeline.
    Deliverable: ingestion paths write immutable Scopus import events (no direct entity writes) with deterministic metadata (`source`, `ingestedAt`, `batchId`, `correlationId`, `payloadHash`).
    Exit criteria: all Scopus import entrypoints produce events only.
  - [ ] `H17.4` Deterministic fact builders (all entities).
    Deliverable: replayable transformation flow from events into normalized facts for publications, citations, forums, authors, affiliations, and funding.
    Exit criteria: replaying identical event input yields identical fact state (idempotent/upsert-safe).
  - [ ] `H17.5` Projection views and query contracts.
    Deliverable: materialized Scopus views aligned to existing admin/API/reporting/scoring read patterns.
    Exit criteria: runtime reads are served via canonical views/facts with no raw payload coupling.
  - [ ] `H17.6` Big-bang read/write cutover and legacy retirement.
    Deliverable: switch all active Scopus write flows to canonical ingestion and all read flows to canonical views; remove/disable legacy direct-write pipeline paths.
    Exit criteria: no active runtime path writes legacy Scopus documents directly.
  - [ ] `H17.7` Scheduler and task flow canonicalization.
    Deliverable: `ScopusPublicationUpdate` and `ScopusCitationsUpdate` execution publishes canonical events and triggers canonical transform/projection flow.
    Exit criteria: scheduled/manual Scopus updates are fully canonical and replay-safe.
  - [ ] `H17.8` Guardrails and regression gates.
    Deliverable: guardrail checks that fail on legacy direct-write Scopus persistence and enforce canonical pipeline usage in CI.
    Exit criteria: CI blocks reintroduction of non-canonical Scopus persistence patterns.
  - [ ] `H17.9` Validation and closeout evidence.
    Deliverable: run log + closeout notes capturing `./gradlew compileJava`, targeted Scopus tests, `./gradlew check`, and replay/idempotence verification evidence.
    Exit criteria: local and CI critical gates are green with canonical Scopus pipeline active.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
