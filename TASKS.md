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

- [ ] `H19` Multi-source Scholardex identity and ingestion architecture.
  Goal: make Scholardex the canonical identity and link graph layer across publications, authors, forums, and affiliations, supporting four sources (`SCOPUS`, `WOS`, `GSCHOLAR`, `USER_DEFINED`) with deterministic lineage, linking, and runtime reads optimized for indicator computation.
  Deliverable: unified canonical contracts + storage models + ingestion/linking pipelines + immediate runtime cutover so all operational reads/writes resolve through Scholardex entities and canonical relationship edges, not source-specific silo models.
  Exit criteria: publication/author/forum/affiliation identity is source-agnostic and deterministic; WoS-first onboarding is complete; Scholar (Publish or Perish) and user-defined imports are supported; runtime paths are cut over to Scholardex; source-specific legacy identity paths are removed from runtime; citations are canonical-ID based across all sources; all entity conflict types are captured in generic conflict storage; source-to-canonical mapping is queryable and replay-stable; canonical publication-author linkage is queryable and deterministic; canonical author-affiliation linkage is queryable and deterministic; affiliation-side traversal for scoring/reporting is fast-path capable.
  Execution order override (locked): for remaining H19 implementation, complete citation migration first (`H19.9`) before finalizing Scopus runtime flow/data initialization and before closing runtime cutover (`H19.7`).
  Subtasks:
  - [x] `H19.1` Define canonical multi-source identity and ownership contract.
    Deliverable: locked contract for Scholardex entities (`publication`, `author`, `forum`, `affiliation`, `citation`) with per-source IDs, provenance/lineage fields, conflict rules, source-link mapping rules, and replay/idempotence semantics.
    Exit criteria: one contract document is implementation-ready and explicitly defines source ownership boundaries for Scopus/WoS/Scholar/User-defined.
    Handover:
    - Contract source of truth: `docs/h19.1-multisource-identity-contract.md`.
  - [x] `H19.2` Define canonical keying and merge policy for journal/forum identity.
    Deliverable: deterministic forum identity policy that links WoS journal identity and Scopus forum identity into Scholardex forum records, with normalization and collision handling rules.
    Exit criteria: deterministic link keys and conflict quarantine behavior are documented and testable.
    Handover:
    - Contract source of truth: `docs/h19.2-forum-keying-merge-contract.md`.
  - [x] `H19.3` Implement Scholardex publication identity model v2.
    Deliverable: publication model supporting source IDs (`eid`, `wosId`, `googleScholarId`, `userSourceId`) plus canonical `scholardexPublicationId` and lineage metadata, with canonical `authorIds` aligned to relationship-edge contracts.
    Exit criteria: all publication ingest/build paths can persist and resolve the new identity model without ambiguity, and publication author linkage is consistent with canonical authorship edges.
  - [x] `H19.4` Implement Scholardex author identity model v2 (researcher-linked).
    Deliverable: author model that supports multiple source author IDs (Scopus/WoS/Scholar/User) as source-identity canonical facts, with canonical `affiliationIds` aligned to relationship-edge contracts, researcher linkage maintained on the researcher side via `primaryScholardexAuthorId`, and deterministic merge rules.
    Exit criteria: author linking and lookup are source-agnostic and deterministic for scoring/reporting entrypoints, and author-affiliation linkage is consistent with canonical author-affiliation edges.
  - [x] `H19.5` Implement Scholardex affiliation identity model v2.
    Deliverable: affiliation model that supports multiple source affiliation IDs and alias resolution across Scopus/WoS/Scholar/User, with reverse-link query support via canonical edge/index contracts (no forum-style reverse arrays required).
    Exit criteria: affiliation linking resolves deterministically, deduplicates source aliases, and supports fast affiliation-side traversal for scoring/reporting entrypoints.
  - [x] `H19.6` Build WoS-first onboarding into Scholardex entities.
    Deliverable: WoS ingestion/linking pipeline that populates/links Scholardex publication/forum/author/affiliation identities using existing WoS canonical facts/views.
    Exit criteria: WoS-only journals/publications not present in Scopus are represented and queryable in Scholardex runtime reads.
  - [x] `H19.9` Canonical citation model and migration from EID-only citation path.
    Deliverable: `scholardex.citation_facts` design and implementation keyed by canonical publication IDs, with migration/cutover from source/EID-bound citation reads.
    Exit criteria: WoS-only and Scholar-only publications participate in citation edges without EID dependency.
    Status: completed (canonical citation facts + runtime citation read cutover).
  - [x] `H19.7` Immediate runtime cutover to Scholardex read/write paths.
    Deliverable: all runtime read/write entrypoints (user/admin/report/export/scoring lookups) use Scholardex canonical paths directly; source-silo runtime identity paths are removed.
    Exit criteria: no runtime dependency remains on legacy source-specific identity stores for publication/author/forum/affiliation/citation resolution; citation runtime paths resolve via canonical citation facts.
    Status: implementation largely complete for publication/author/forum/affiliation/citation; remaining closeout is decommission/validation hardening.
  - [x] `H19.10` Generic identity conflict model + admin operations.
    Deliverable: `scholardex.identity_conflicts` contract and implementation covering publication/forum/author/affiliation ambiguity, plus operational listing/resolve/clear flows.
    Exit criteria: ambiguous merges across all canonical entity types are captured and manageable through one generic conflict surface.
  - [x] `H19.11` Source-link ledger + replay/traceability integration.
    Deliverable: `scholardex.source_links` contract and implementation mapping `(entityType, source, sourceRecordId)` to canonical entity IDs with deterministic state transitions.
    Exit criteria: traceability/replay workflows can resolve source record to canonical entity deterministically in one query path.
  - [x] `H19.12` Canonical relationship-edge model for indicator runtime.
    Deliverable: authoritative `scholardex.authorship_facts` (`publication -> author`) and `scholardex.author_affiliation_facts` (`author -> affiliation`) with deterministic ids, lineage, idempotence, and conflict policy.
    Exit criteria: canonical edge writes/replays are deterministic, conflict-safe, and consistent with `publication_facts.authorIds` and `author_facts.affiliationIds`.
  - [ ] `H19.13` Indicator/report query cutover to edge-backed traversals.
    Deliverable: scoring/report/export/user/admin query paths use canonical edge-backed traversals for publication-by-author and author-by-affiliation access, with performance parity/guardrail checks.
    Exit criteria: runtime indicator computation no longer depends on source-silo author/affiliation linkage paths and passes parity/performance gates.
  - [ ] `H19.8` End-to-end validation, parity, and operability gates.
    Deliverable: workflow and integration tests covering all four sources, identity-link conflicts, replay/idempotence, and cutover regressions; observability metrics and failure triage hooks.
    Exit criteria: CI gates catch identity/linking regressions and operational dashboards expose source-level ingest/link health.

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
