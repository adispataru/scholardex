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
  Goal: replace direct Scopus document writes with a canonical ingestion pipeline aligned to WoS patterns (`events -> facts -> views`) while converging runtime publication reads to a derived `scholardex.publication` projection that merges Scopus, WoS, and Google Scholar enrichments.
  Deliverable: high-level migration to Scopus import events ledger, normalized Scopus facts layer, explicit cross-source field ownership contract, and merged projection views consumed by application/reporting flows.
  Exit criteria: Scopus ingest is replayable/idempotent from source events, source-specific facts remain authoritative, merged publication views are deterministic and lineage-backed, and guardrail checks protect against regressions and ownership drift.
  Assumption lock (2026-03-06): big-bang cutover for all Scopus entities; no historical data migration/backfill is required (clean-state bootstrap only).
  Amendment note (2026-03-06): H17.1 contract is extended to include cross-source ownership boundaries and derived merged-publication projection constraints (`scholardex.publication*`) without reopening H17.1 status.
  Amendment note (2026-03-07): WoS canonical fact semantics are split: journal score facts in `WosMetricFact` (`journalId + year + metricType`) and category ranking facts in `wos.category_facts` (`journalId + year + metricType + categoryNameCanonical + editionNormalized`); projections/read paths join score + ranking facts.
  Amendment note (2026-03-07): WoS category ranking facts now carry both `quarter + quartileRank` and `rank` where `rank` is category+edition rank (official JSON), while government data may provide only quarter.
  Subtasks:
  - [x] `H17.1` Canonical Scopus contract lock.
    Deliverable: `docs/h17-scopus-canonical-contract.md` with canonical collections, required fields, identity keys, lineage fields, and source-policy rules for publications, citations, forums, authors, affiliations, and funding.
    Exit criteria: schema, identity, and source policy are decision-locked before implementation changes.
  - [x] `H17.2` Canonical storage and index baseline.
    Deliverable: canonical Mongo collection/index definitions for Scopus import events, normalized facts, Scopus read views, and merged `scholardex.publication*` projection/index prerequisites (lookup/sort/reporting keys) with idempotence-oriented unique constraints.
    Exit criteria: fresh environment creates canonical and merged-projection storage deterministically with required uniqueness/index coverage.
  - [x] `H17.3` Event ledger ingestion pipeline.
    Deliverable: ingestion paths write immutable Scopus import events (no direct entity writes) with deterministic metadata (`source`, `ingestedAt`, `batchId`, `correlationId`, `payloadHash`).
    Exit criteria: all Scopus import entrypoints produce events only.
  - [x] `H17.4` Deterministic fact builders (all entities).
    Deliverable: replayable transformation flow from events into normalized facts for publications, citations, forums, authors, affiliations, and funding with field-ownership safeguards that prevent Scopus builders from clobbering non-Scopus enrichments.
    Exit criteria: replaying identical event input yields identical Scopus fact state (idempotent/upsert-safe) and ownership boundaries are preserved.
  - [x] `H17.5` Projection views and query contracts.
    Deliverable: deterministic projection builders materialize `scopus.forum_search_view`, `scopus.author_search_view`, `scopus.affiliation_search_view`, and enriched `scholardex.publication_view`; runtime admin/API/reporting/scoring reads use projection-backed contracts with merged-publication lookup compatibility (`id` primary, plus `eid`/`wosId`/`googleScholarId`).
    Exit criteria: read flows are projection-backed, publication identity resolution normalizes to projection `id`, and WoS/Scholar enrichment persistence is projection-owned without Scopus field clobbering.
  - [x] `H17.6` Big-bang read/write cutover and legacy retirement.
    Deliverable: switch active Scopus write flows to canonical ingestion and publication-facing read flows to merged `scholardex.publication` projection; remove/disable legacy direct-write and direct-read Scopus document paths in runtime facades; centralize WoS/Scopus big-bang operations on dedicated admin initialization UI (`/admin/initialization`) with deterministic step actions and full-run orchestration.
    Exit criteria: no active runtime path writes legacy Scopus documents directly, publication reads no longer depend on legacy direct Scopus documents, and big-bang maintenance is executed from the dedicated initialization page (rankings page no longer exposes maintenance controls).
  - [x] `H17.7` Scheduler and task flow canonicalization.
    Deliverable: `ScopusPublicationUpdate` and `ScopusCitationsUpdate` execution publishes canonical events and triggers canonical transform/projection flow.
    Exit criteria: scheduled/manual Scopus updates are fully canonical and replay-safe.
  - [x] `H17.8` Guardrails and regression gates.
    Deliverable: guardrail checks that fail on legacy direct-write Scopus persistence and enforce canonical pipeline usage in CI.
    Exit criteria: CI blocks reintroduction of non-canonical Scopus persistence patterns.
  - [x] `H17.9` Validation and closeout evidence.
    Deliverable: run log + closeout notes capturing `./gradlew compileJava`, targeted Scopus tests, `./gradlew check`, and replay/idempotence verification evidence.
    Exit criteria: local and CI critical gates are green with canonical Scopus pipeline active.
  - [x] `H17.10` Cross-source merge policy and linker rules.
    Deliverable: production linker/merge implementation for `scholardex.publication_view` with exact-key resolution precedence (`id` -> `eid` -> `doiNormalized`), conflict quarantine persistence, NON-WOS exclusion, and migrated WoS enrichment call-sites (`UserReportFacade`, `GroupCnfisExportFacade`) that write through linker-owned lineage fields only.
    Exit criteria: enrichment writes are deterministic, ownership-safe, replay-safe, conflict-aware (quarantine/non-mutating), and no reporting/export flow bypasses linker service for WoS/Scholar-owned keys.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
