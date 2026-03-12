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

- [ ] `H23` Scholardex UI route consolidation and steady-state naming cleanup.
  Goal: reduce maintenance overhead and product-surface drift by consolidating MVC/UI routes around Scholardex-first forum navigation while retiring the split between Scopus forum pages and WoS ranking pages.
  Deliverable: canonical Scholardex forum routes/templates for public and admin UI, WoS-specific category pages, trimmed MVC compatibility redirects/helpers, and updated docs/guardrails that reflect the new steady-state navigation model.
  Exit criteria: covered MVC surfaces use the new canonical route families, legacy MVC paths are either redirected or clearly marked transitional, and tests/guardrails enforce the consolidated UI architecture.
  Subtasks:
  - [ ] `H23.1` Inventory and classify transitional debt.
    Deliverable: concise inventory doc of internal legacy/transitional hotspots, grouped as `remove now`, `keep intentionally`, `defer`.
    Must include:
    - dual-read-only Mongo adapters vs runtime adapters,
    - compatibility redirects/routes still used internally,
    - legacy Scopus naming in internal models/services used on steady-state paths,
    - documented transitional frontend/script allowances still active.
    Exit criteria: each hotspot has owner + planned action and no ambiguous “maybe legacy” items remain.
  - [ ] `H23.2` Scholardex UI route consolidation.
    Deliverable: move covered MVC forum/publication/affiliation pages to canonical Scholardex-first route families and retire WoS rankings as the primary forum discovery surface.
    Focus:
    - public canonical forum routes under `/scholardex/forums` and `/scholardex/forums/{id}`,
    - admin canonical forum/publication/affiliation routes under `/admin/scholardex/**`,
    - legacy MVC `/admin/scopus/**` and `/rankings/wos` list routes reduced to redirects or explicit transitional shims where needed,
    - Scholardex forums listing includes all known forums and absorbs current WoS-listing behavior via WoS status column/filter.
    Exit criteria: covered MVC discovery/list pages resolve through canonical Scholardex routes and the old split between Scopus forum pages and WoS journal listing is no longer the primary navigation model.
  - [ ] `H23.3` Unified forum detail and UI naming normalization.
    Deliverable: replace the current journal-only WoS ranking detail flow with a forum-centric Scholardex detail page and normalize UI-facing naming around that model.
    Focus:
    - current `/rankings/wos/{id}` detail behavior moves to the canonical Scholardex forum detail page,
    - journal forums show WoS ranking details when indexed, otherwise a deterministic “not indexed by WoS” message,
    - conference proceedings show a CORE placeholder section,
    - books show a book-ranking placeholder section,
    - UI-facing controller/template/service naming reflects Scholardex ownership rather than the old Scopus/WoS split.
    Keep:
    - API paths and DTO schemas unchanged in this wave.
    Exit criteria: covered forum detail pages are forum-type aware, placeholders exist for later ranking integrations, and no active canonical MVC page still presents WoS rankings as a separate primary forum-detail concept.
  - [ ] `H23.4` Route-aware guardrails and deterministic UI verification refresh.
    Deliverable:
    - focused MVC contract tests aligned to the new canonical routes plus legacy redirects,
    - refreshed JS/browserless coverage for renamed pages/assets,
    - deterministic coverage for forum-detail branching and new WoS category pages,
    - guardrails that block reintroduction of the Scopus-forums vs WoS-rankings UI split.
    Exit criteria: cleanup-specific test suite is deterministic and catches regressions in canonical routes, redirect shims, forum-type branching, and category-page presence.
  - [ ] `H23.5` Docs, route map, and task closeout.
    Deliverable: update architecture/runbook/task docs to reflect canonical Scholardex forum navigation, WoS category pages, retained legacy MVC redirects, and API-layer names intentionally kept stable.
    Exit criteria: `TASKS.md`/`TASKS-done.md` and H22/H19 adjacent docs are consistent with the post-H23 UI route map and clearly distinguish canonical MVC routes from intentionally retained API naming.
  Public interfaces / types:
  - MVC/public/admin route and template names may change in this wave.
  - `/api/scopus/**` remains unchanged.
  - `/api/rankings/wos` remains unchanged unless a later task explicitly changes it.
  - No DTO schema changes.
  - No DB schema/migration changes required by default.
  - API/controller DTO rename or deprecation work is explicitly deferred to a separate future task.
  Test plan (for future implementation task):
  - Core targeted suite:
    - `*RankingViewControllerContractTest`
    - `*AdminViewControllerContractTest`
    - any affected forum-detail service/read tests
    - any redirect/route compatibility tests for retained legacy MVC paths.
  - Browserless / JS coverage:
    - existing `rankings-wos.js` coverage updated or replaced by Scholardex forum coverage,
    - `admin-scopus-forums.js` and `admin-scopus-affiliations.js` coverage updated or replaced for renamed canonical pages,
    - any new category-page script coverage if basic client filtering/navigation is introduced.
  - Determinism check:
    - run refreshed cleanup baseline command twice with identical pass/fail outcome.
  - Acceptance:
    - canonical Scholardex forum pages cover all known forums,
    - WoS status/filter behavior replaces WoS list-page forum discovery,
    - forum detail renders the correct journal/conference/book state,
    - guardrails enforce non-reintroduction of the old split navigation model.
  Assumptions:
  - API routes and DTO schemas remain intentionally stable in this wave.
  - Canonical public forum routes live under `/scholardex/**`.
  - Canonical WoS category pages live under `/rankings/**`.
  - Legacy MVC routes may remain temporarily as redirects, but no longer as equal first-class pages.
  - “All known forums” means the canonical Scholardex forum view remains the base discovery set, enriched with WoS availability/status rather than replaced by a separate WoS-only list.
