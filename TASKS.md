# Project Recovery Tasks (High-Level)

Objective: turn the current feature bundle into a maintainable, testable, and evolvable product.

Done history moved to `TASKS-done.md`.

## Backlog

- `H01` completed and archived in `TASKS-done.md`.

- [x] `H02` Architecture boundaries and ownership.
  Goal: define module boundaries, responsibilities, and allowed dependencies between layers.
  Deliverable: lightweight architecture map and dependency rules.
  Exit criteria: new code placement rules are documented and enforceable in review.
  Status: completed on 2026-03-03.
  Note: architecture baseline and enforcement are active via `docs/h02-*.md` and `npm run verify-architecture-boundaries`.

- [x] `H03` Contract and behavior baseline.
  Goal: capture current expected behavior for key flows before refactors.
  Deliverable: minimal contract suite (controller/service integration + key UI/API flows).
  Exit criteria: high-impact flows have regression coverage and a known pass/fail baseline.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H03-S01..S07 completion details and adoption guidance.

- [x] `H04` Test strategy and pyramid rebalance.
  Goal: reduce fragile end-to-end reliance and improve unit/integration signal quality.
  Deliverable: test taxonomy, gap matrix, and priority test additions.
  Exit criteria: each critical feature has at least one stable automated regression test.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H04-S01..S07 completion details and adoption guidance.

- [x] `H05` Frontend structure and asset discipline.
  Goal: standardize JS/CSS/template patterns to avoid divergent implementations.
  Deliverable: frontend conventions (entrypoints, shared utilities, template composition patterns).
  Exit criteria: duplicated UI logic is centralized and new pages follow the same conventions.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H05-S01..S07 completion details and adoption guidance.

- [ ] `H06` Data and persistence consistency review.
  Goal: verify entity design, migrations/data files, transaction boundaries, and query patterns for inconsistencies.
  Deliverable: persistence risk report and remediation plan.
  Exit criteria: integrity risks and performance hotspots are tracked with clear fixes.

- [ ] `H07` Error handling, validation, and security hardening.
  Goal: unify input validation, exception mapping, auth/authz checks, and security defaults.
  Deliverable: standardized error/validation/security checklist with implementation gaps.
  Exit criteria: critical endpoints and forms comply with one consistent policy.

- [ ] `H08` Observability and operability foundation.
  Goal: make failures diagnosable with structured logs, metrics, and health/readiness signals.
  Deliverable: minimum observability baseline and runbook starter.
  Exit criteria: common production failure modes are detectable and actionable.

- [ ] `H09` Build, CI, and quality gates.
  Goal: ensure every change passes reproducible checks and prevents regressions from merging.
  Deliverable: CI pipeline definition with lint/test/build/security gates.
  Exit criteria: required checks are automated and block broken changes.

- [ ] `H10` Documentation and contribution workflow.
  Goal: align README/CONTRIBUTING with actual architecture, setup, and delivery flow.
  Deliverable: contributor playbook for local dev, testing, and release hygiene.
  Exit criteria: a new contributor can run, test, and modify the project without tribal knowledge.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.

## H02 First Subtask List (Planning Mode Seed)

Scope: `H02` Architecture boundaries and ownership.

- [x] `H02-S01` Map current runtime architecture and dependency directions.
  Goal: produce a factual map of layers/modules and how requests/flows travel through them.
  Inputs: package structure, controller/service/repository wiring, frontend template/script entrypoints.
  Deliverable: `docs/h02-architecture-map.md` (current-state diagram + dependency table).
  Exit criteria: all major runtime paths (web -> service -> data and template/script flow) are represented.
  Status: completed on 2026-03-03.

- [x] `H02-S02` Define target boundaries and ownership zones.
  Goal: define what belongs in each layer/module and who owns cross-cutting areas.
  Inputs: `H02-S01` map + current drift/findings from H01.
  Deliverable: `docs/h02-boundaries-and-ownership.md` (zones, responsibilities, ownership matrix).
  Exit criteria: each major package/area has a declared owner and allowed responsibilities.
  Status: completed on 2026-03-03.

- [x] `H02-S03` Specify allowed dependency rules.
  Goal: convert boundaries into explicit allow/deny dependency rules.
  Inputs: boundary definitions and known problematic couplings.
  Deliverable: dependency rule set in `docs/h02-boundaries-and-ownership.md` (or `docs/h02-dependency-rules.md`).
  Exit criteria: developers can decide placement/dependencies without ambiguity.
  Status: completed on 2026-03-03 (`docs/h02-dependency-rules.md`).

- [x] `H02-S04` Identify and classify current boundary violations.
  Goal: detect concrete code locations that violate the declared dependency rules.
  Inputs: declared rules + current codebase scan.
  Deliverable: `docs/h02-violations.md` with severity (`high|medium|low`) and rationale.
  Exit criteria: every violation has a file reference and a proposed remediation direction.
  Status: completed on 2026-03-03 (`docs/h02-violations.md`).
  Note: V01 follow-up slice 4 completed (`AdminGroupController` export/CNFIS via `GroupExportFacade` and `GroupCnfisExportFacade`); tracked baseline pair is now at 73.9% repository-field reduction (`23 -> 6`), and AdminGroup repository debt is closed.
  Note: V02 baseline slice completed for the same pair (User/AdminGroup): direct controller imports of `core.service.reporting` removed; export/reporting coupling now facade-backed.
  Note: V02 AdminView verification slice completed: no direct `Z1 -> Z3` reporting-service coupling found in `AdminViewController`; transport-layer scan baseline is clean.
  Note: V03 focused AdminView slice delivered: institution publications/export data assembly and ranking compute/merge flows moved behind `AdminInstitutionReportFacade` and `RankingMaintenanceFacade`.
  Note: V03 final closure slice delivered: remaining transport assembly moved behind `AdminScopusFacade` and `ForumExportFacade` (`/admin/scopus/publications/search`, `/admin/scopus/publications/citations`, `/api/export`); V03 marked complete for current H02 scope.
  Note: V04 execution slice completed: reporting back-edge to `CacheService` removed via `ReportingLookupPort` + `CacheBackedReportingLookupFacade`; `service/reporting/**` now has zero `CacheService` references/imports.

- [x] `H02-S05` Define phased remediation plan for violations.
  Goal: prioritize fixes by blast radius and effort without blocking delivery.
  Inputs: violation inventory + ownership matrix.
  Deliverable: `docs/h02-remediation-plan.md` with phased slices (`R1`, `R2`, ...).
  Exit criteria: top-priority violations have actionable implementation slices and sequencing.
  Status: completed on 2026-03-03 (`docs/h02-remediation-plan.md` with `R1..R4`).

- [x] `H02-S06` Add lightweight enforcement in workflow.
  Goal: add practical checks/review guardrails so boundaries stay intact.
  Inputs: dependency rules + remediation strategy.
  Deliverable: checks and contributor guidance updates (`CONTRIBUTING.md`, optional scripts/CI rule).
  Exit criteria: at least one automated or checklist-based gate prevents new boundary violations.
  Status: completed on 2026-03-03.
  Note: added `npm run verify-architecture-boundaries` (`scripts/verify-architecture-boundaries.js`) to enforce: no new `Z1 -> Z4` controller repository imports (debt-aware allowlist), no `Z1 -> Z3` reporting imports in transport, and no `CacheService` usage in `service/reporting/**`.

- [x] `H02-S07` Close H02 with adoption notes.
  Goal: finalize architecture baseline and usage guidance for future tasks.
  Inputs: completed H02 artifacts and enforcement setup.
  Deliverable: H02 closeout note in `docs/h02-boundaries-and-ownership.md` + `TASKS.md` status updates.
  Exit criteria: H02 can be treated as reference baseline for H03+ planning and implementation.
  Status: completed on 2026-03-03.
  Note: H02 is now the active architecture reference baseline; reopen H02 only for boundary-rule changes or newly detected violations.

`H05` subtasks and closure details are archived in `TASKS-done.md`.

## H06 First Subtask List (Planning Mode Seed)

Scope: `H06` Data and persistence consistency review.

- [x] `H06-S01` Build persistence architecture map and entity ownership baseline.
  Goal: map collections/entities, repository ownership, and write/read flow boundaries.
  Inputs: package structure, Mongo repositories/models, H02 boundary baseline.
  Deliverable: `docs/h06-persistence-map.md` with entity-to-repository/service usage matrix.
  Exit criteria: all high-impact entities have explicit owners and primary write paths.
  Status: completed on 2026-03-03 (`docs/h06-persistence-map.md`).

- [x] `H06-S02` Inventory schema and data-shape drift risks.
  Goal: detect inconsistent field usage, duplicated semantic fields, and optional/null drift.
  Inputs: models, repositories, import/export paths, existing data assumptions from H01/H03.
  Deliverable: `docs/h06-schema-drift-inventory.md` with risk-ranked drift clusters.
  Exit criteria: top drift candidates have concrete file evidence and impact notes.
  Status: completed on 2026-03-03 (`docs/h06-schema-drift-inventory.md`).

- [x] `H06-S03` Review query patterns and consistency semantics.
  Goal: identify risky query patterns (implicit ordering, partial filters, case sensitivity, stale assumptions).
  Inputs: repository methods, service query usage, existing integration tests.
  Deliverable: `docs/h06-query-consistency-findings.md`.
  Exit criteria: high/medium query risks have remediation direction and guard strategy.
  Status: completed on 2026-03-03 (`docs/h06-query-consistency-findings.md`).

- [x] `H06-S04` Define canonical persistence contracts.
  Goal: lock conventions for IDs, timestamps, subtype/source fields, enum/text normalization, and update semantics.
  Inputs: findings from S01-S03 + H02 dependency rules.
  Deliverable: `docs/h06-persistence-contracts.md`.
  Exit criteria: contributors can make persistence changes without ambiguity.
  Status: completed on 2026-03-03 (`docs/h06-persistence-contracts.md`).

- [x] `H06-S05` Add focused persistence regression tests for highest risks.
  Goal: protect critical repository/service data behaviors before remediation.
  Inputs: H06 findings + H04 integration strategy (Testcontainers where needed).
  Deliverable: targeted repository/service characterization tests for selected high-risk paths.
  Exit criteria: each P0/P1 persistence risk has at least one automated guard.
  Status: completed on 2026-03-03.
  Note: added `PersistenceYearSupport` + CNFIS filter hardening (`skip + warn`), aligned ranking ISSN cache semantics in `CacheService`, and added regression guards (`PersistenceYearSupportTest`, `CacheServiceTest`, CNFIS facade tests, citation pair characterization in `ScopusCitationRepositoryIntegrationTest`).

- [ ] `H06-S06` Define phased remediation plan and guardrails.
  Goal: sequence cleanup work by blast radius and effort; prevent reintroduction.
  Inputs: contracts + test baseline + risk ranking.
  Deliverable: `docs/h06-remediation-plan.md` and lightweight verification updates (script/checklist as needed).
  Exit criteria: remediation slices are actionable and new persistence drift can be detected early.

- [ ] `H06-S07` Close H06 with adoption notes and handoff to H07.
  Goal: finalize persistence baseline and operational usage guidance for security/validation hardening.
  Inputs: completed H06 docs, tests, and guardrails.
  Deliverable: H06 closeout note in H06 docs + `TASKS.md`/`TASKS-done.md` status updates.
  Exit criteria: H06 is usable as source-of-truth for persistence changes and future audits.
