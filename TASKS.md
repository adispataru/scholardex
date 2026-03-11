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
  - [x] `H18.1` Define enrichment computation contract.
    Deliverable: documented deterministic rules for `rank`, `quartile`, and `quartileRank` at `category + edition` scope, including tie handling and null/insufficient-data behavior.
    Exit criteria: rules are unambiguous and implementation-ready.
    Status: completed on 2026-03-08.
    Handover:
    - Contract source of truth: `docs/h18.1-wos-ranking-enrichment-contract.md`.
    - Canonical linkage amendment: `docs/h17-scopus-canonical-contract.md` (H18.1 section).
    - Locked decisions: competition rank ties (`1,1,3`), position-bucket quartiles, source `quarter` precedence, missing metric value -> skip (non-conflict).
  - [x] `H18.2` Integrate enrichment into WoS ingestion/projection flow.
    Deliverable: service-level enrichment step that preserves source values and computes only missing fields.
    Exit criteria: persistence reflects "source if present, computed otherwise" for all three fields.
    Status: completed on 2026-03-08.
    Handover:
    - Canonical enrichment implementation: `WosFactBuilderService#enrichMissingCategoryRankingFields` computes missing `rank`, `quarter`, `quartileRank` while preserving source-provided fields.
    - Initialization order now includes explicit enrichment step before projections (`/admin/initialization/wos/enrichCategoryRankings`).
    - Big-bang flow executes enrichment between `build-facts` and `build-projections`.
  - [x] `H18.3` Add admin backend endpoints for enrichment operations.
    Deliverable: secured admin endpoints to trigger enrichment and retrieve summary results (processed, computed, preserved, failed).
    Exit criteria: authorized admins can execute enrichment and get deterministic run summaries.
    Status: completed on 2026-03-08.
    Handover:
    - New admin JSON endpoints: `POST /admin/initialization/wos/enrichment/run` and `GET /admin/initialization/wos/enrichment/summary`.
    - Deterministic summary DTO: `stepName`, `executed`, `startedAt`, `completedAt`, `processed`, `computed`, `preserved`, `failed`, `skipped`, `note`.
    - Locked mapping used in backend reporting: `computed=updated`, `failed=errors`, `preserved=processed-computed-failed`.
  - [x] `H18.4` Build dedicated admin page for WoS enrichment.
    Deliverable: admin UI page to start enrichment runs and review per-run outcome metrics.
    Exit criteria: page is accessible to admins only and supports operational verification.
    Status: completed on 2026-03-08.
    Handover:
    - Dedicated page endpoint: `GET /admin/initialization/wos/enrichment` with run action `POST /admin/initialization/wos/enrichment/runPage`.
    - Page shows latest deterministic enrichment metrics (`processed`, `computed`, `preserved`, `failed`, `skipped`) and links to JSON summary endpoint.
    - Initialization step 3 now exposes direct navigation to the dedicated enrichment page (`Open page`).
  - [ ] `H18.5` Backfill historical WoS records.
    Deliverable: one-time/backfill-capable execution path for existing data.
    Exit criteria: historical records are enriched according to the same contract, with idempotent rerun behavior.
    Status: active next task.
  - [ ] `H18.6` Add regression and integration test coverage.
    Deliverable: tests for preservation logic, computation correctness, and admin trigger flow.
    Exit criteria: automated tests cover success paths and key failure/edge cases.

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

- [ ] `H22` Postgres reporting core + Mongo ingest baseline migration.
  Goal: improve WoS scoring/reporting read and compute latency by moving reporting read models to PostgreSQL while keeping MongoDB as the ingestion/event/queue write model.
  Deliverable: architecture contract, SQL read schema, projection/sync pipeline, SQL query cutover for WoS scoring/reporting flows, and operability/rollback guardrails.
  Exit criteria: Mongo remains authoritative for raw import events/queues; WoS/scoring/report read models are served from PostgreSQL; SQL joins/materialized views back WoS scoring and citation-heavy report paths; parity and performance gates pass before full cutover.
  Subtasks:
  - [x] `H22.1` Architecture contract and bounded-context map.
    Deliverable: decision-locked contract separating Mongo write/ingest boundaries from PostgreSQL reporting read boundaries, including ownership and data-flow rules.
    Exit criteria: all affected read/write surfaces have explicit ownership, sync direction, and compatibility policy.
    Status: completed on 2026-03-11.
    Handover:
    - Contract source of truth: `docs/h22.1-postgres-reporting-architecture-contract.md`.
    - Companion sequence flows: `docs/h22.1-postgres-reporting-sequences.md`.
  - [x] `H22.2` PostgreSQL schema for WoS/scoring/reporting read core.
    Deliverable: normalized SQL schema (tables, keys, indexes, constraints) for WoS ranking/scoring and reporting read models.
    Exit criteria: schema supports deterministic joins for existing scoring/reporting contracts and enforces required uniqueness/integrity constraints.
    Status: completed on 2026-03-11.
    Handover:
    - Schema contract: `docs/h22.2-postgres-reporting-schema-contract.md`.
    - Flyway migrations: `src/main/resources/db/migration/V1__h22_2_create_pg_enums.sql`, `src/main/resources/db/migration/V2__h22_2_create_reporting_core_tables.sql`, `src/main/resources/db/migration/V3__h22_2_create_reporting_core_indexes.sql`.
    - Migration verification test: `PostgresReportingReadSchemaMigrationIntegrationTest`.
  - [x] `H22.3` Projection/sync pipeline from canonical Mongo to PostgreSQL.
    Deliverable: deterministic projector/backfill flow that materializes PostgreSQL read models from canonical Mongo collections with replay-safe behavior.
    Exit criteria: full rebuild and incremental sync both produce stable SQL read state with lineage/traceability.
    Status: completed on 2026-03-11.
    Handover:
    - Projection contract: `docs/h22.3-postgres-projection-contract.md`.
    - Projection state migration: `src/main/resources/db/migration/V4__h22_3_projection_state_tables.sql`.
    - Projector service: `JdbcPostgresReportingProjectionService` + `PostgresReportingProjectionService`.
    - Verification tests: `PostgresReportingProjectionServiceIntegrationTest`, `JdbcPostgresReportingProjectionServiceTest`, `PostgresReportingReadSchemaMigrationIntegrationTest`.
  - [ ] `H22.4` Query-layer cutover to SQL-backed WoS scoring/report reads.
    Deliverable: service/facade read paths for WoS scoring and report retrieval switched from Mongo-assembled joins to SQL-backed queries.
    Exit criteria: runtime scoring/report entrypoints no longer depend on Mongo join assembly for targeted paths.
  - [ ] `H22.5` Materialized views and refresh strategy for heavy reads.
    Deliverable: SQL materialized views (or equivalent precomputed read structures) for citation-heavy and ranking-heavy reporting queries, with refresh policy.
    Exit criteria: heavy reporting workloads meet target latency with deterministic refresh semantics.
  - [ ] `H22.6` Dual-read parity and performance gate.
    Deliverable: automated parity + latency comparison gates between legacy Mongo reads and new SQL reads across representative workloads.
    Exit criteria: correctness parity is proven and latency/error budgets are met before final read cutover.
  - [ ] `H22.7` Operationalization, rollback, and rebuild playbook.
    Deliverable: documented runbooks for deployment, monitoring, rollback, and full read-model rebuild/backfill in production-like environments.
    Exit criteria: on-call workflows can detect, mitigate, and recover from projector/read-model failures without data-loss ambiguity.
