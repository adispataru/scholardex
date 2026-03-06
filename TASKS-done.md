# Done Tasks

Archived completed tasks moved from `TASKS.md` on 2026-03-03.

## H11-H14 Recovery Wave

Archived from `TASKS.md` on 2026-03-06 after closure and cleanup.

- [x] `H11` Functional contract hardening and null-safety normalization.
  Status: completed on 2026-03-04.
  Notes: core nullable contracts normalized to deterministic behavior and guarded with regression checks.

- [x] `H12` External integration and import correctness uplift.
  Status: completed on 2026-03-04.
  Notes: importer/scheduler behavior hardened with deterministic error accounting and integration guardrails.

- [x] `H14` WoS Approach 3 implementation (immutable ingestion ledger + rebuildable views).
  Status: completed on 2026-03-06.
  Notes: H14.1-H14.16 resolved; H14.14 and H14.15 were explicitly dropped by decision.
  Highlights:
  - canonical WoS schema + identity + immutable import events + parser adapters + fact builders delivered,
  - IF source-policy enforced (`OFFICIAL_WOS_EXTRACT` only) while `IMPACT_FACTOR` remains operational,
  - projections/indexes/read-path/reporting cutover completed with cache-independent WoS lookup paths,
  - admin-triggered big-bang migration and parity reconciliation gates delivered,
  - residual H14 checks converted to automated tests (bundled SCIE/SSCI split, replay determinism, AIS/RIS/CNFIS parity stability).

## H15 CI Guardrail Realignment and Quality-Gate Restoration

Archived from `TASKS.md` on 2026-03-06 after closure and CI stabilization.

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

## Vendor Asset Migration Tasks

Tracking migration from `/vendor/*` assets to bundled `/assets/*` assets.

- [x] `T01` Goal: Create task tracker and migration guardrails.
  Files/areas: `/TASKS.md`
  Automated checks: `./gradlew test`
  Done criteria: tracker exists with ordered, test-gated tasks.
  Notes: Completed.

- [x] `T02` Goal: Introduce frontend toolchain (npm + bundler) without switching templates yet.
  Files/areas: `/package.json`, lockfile, bundler config, `frontend/` source dir.
  Automated checks: `npm ci`, `npm run build`, `./gradlew test`
  Done criteria: deterministic assets generated under `src/main/resources/static/assets/`.
  Notes: Completed. `package.json` + lockfile present and install/build checks pass.

- [x] `T03` Goal: Wire baseline vendor equivalents into bundled entrypoints.
  Files/areas: `package.json`, frontend entrypoint files, build scripts.
  Automated checks: `npm run build`, `npm run verify-assets`, `./gradlew test`
  Done criteria: bundle contract includes Bootstrap, jQuery, DataTables, Chart.js, Font Awesome, jquery-easing.
  Notes: Completed with committed `app.css`/`app.js` and npm entrypoint definitions.

- [x] `T04` Goal: Add automated template asset-path validation.
  Files/areas: `scripts/verify-template-assets.js`, npm script wiring.
  Automated checks: `npm run verify-template-assets`, `./gradlew test`
  Done criteria: validator fails on reintroduced `/vendor/` usage.
  Notes: Completed.

- [x] `T05` Goal: Incremental migration batch A (shared pages/fragments).
  Files/areas: shared template patterns used by migrated pages.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: migrated batch has no direct `/vendor/` references.
  Notes: Completed.

- [x] `T06` Goal: Incremental migration batch B (admin pages).
  Files/areas: `src/main/resources/templates/admin/**`
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: admin templates use bundled assets and no `/vendor/...` remains.
  Notes: Completed (excluding `*-bak.html` backups from strict validator).

- [x] `T07` Goal: Incremental migration batch C (user pages).
  Files/areas: `src/main/resources/templates/user/**`
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: user templates no longer depend on `/vendor/...`.
  Notes: Completed.

- [x] `T08` Goal: Remove obsolete vendor tree and machine artifacts.
  Files/areas: `src/main/resources/static/vendor/**`, `.gitignore`.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`, `rg -n '/vendor/' src/main/resources/templates`
  Done criteria: no production template refs to `/vendor/`; `.DS_Store` ignored.
  Notes: Completed.

- [x] `T09` Goal: Documentation and developer workflow finalization.
  Files/areas: `README.md`, `CONTRIBUTING.md`.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: docs reflect reproducible frontend + backend verification commands.
  Notes: Completed.

- [x] `T10` Goal: Final regression gate and signoff.
  Files/areas: `TASKS.md` status updates.
  Automated checks: `npm ci`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: all checks green and tasks complete.
  Notes: Completed. Full gate passed: `npm ci`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `./gradlew check`.

## H01 Duplicate and Drift Audit

Archived from `TASKS.md` on 2026-03-03 after H01 closure.

- [x] `H01` Duplicate code and drift audit.
  Goal: identify copy-paste clusters (backend, frontend, templates, scripts) and detect behavior drift between near-identical implementations.
  Deliverable: duplication inventory with risk ranking and consolidation candidates.
  Exit criteria: top high-risk duplicates have an agreed merge strategy and owners.
  Notes: Completed on 2026-03-03. C01/C03/C04 prioritized slices were executed and stabilized with regression guards and reintroduction checks.

### H01 Subtasks

- [x] `H01-S01` Inventory likely duplicate clusters.
  Deliverable: `docs/h01-duplication-inventory.md`.
  Notes: Completed.

- [x] `H01-S03` Identify behavioral drift inside top clusters.
  Deliverable: `docs/h01-drift-findings.md`.
  Notes: Completed on 2026-03-03. `C01`, `C03`, `C04`, `C05`, and `C06` analyzed with decisions/evidence; C04 closure slices completed (`D01/D02/D03/D04/D05/D06/D07` resolved for C04 scope).

- [x] `H01-S04` Prioritize by risk and blast radius.
  Deliverable: priority table in `docs/h01-duplication-inventory.md`.
  Notes: Completed (`C01 (P0)` -> `C04 (P1)` -> `C06 (P2)`).

- [x] `H01-S05` Define consolidation strategy per priority cluster.
  Deliverable: `docs/h01-consolidation-strategy.md`.
  Notes: Completed.

- [x] `H01-S06` Create regression guards before refactor.
  Deliverable: focused tests + coverage notes in `docs/h01-regression-guards.md`.
  Notes: Completed.

- [x] `H01-S07` Execute first consolidation slice (small, high-value).
  Deliverable: C04 sub-cluster B consolidation slices.
  Notes: Completed on 2026-03-03. Factory fail-fast + CS dispatch alignment completed.

- [x] `H01-S08` Prevent reintroduction.
  Deliverable: CI/local duplication check command + contributor note.
  Notes: Completed on 2026-03-03. Added `npm run verify-duplication-guardrails`, wired into `./gradlew check`.

### H01 Cluster Closures

- [x] `C01` `CNFISScoringService` vs `CNFISScoringService2025`.
  Notes: Closed on 2026-03-03. Canonical spec in `docs/c01-cnfis-rule-spec.md`, edge-case tests expanded, no-behavior cleanup applied.

- [x] `C02` Admin template backups (`*-bak.html`) vs active templates.
  Notes: Resolved on 2026-03-03 by deleting `admin/researchers-bak.html`.

- [x] `C03` Admin rankings backup template pair.
  Notes: Resolved on 2026-03-03 by deleting `admin/rankings-view-bak.html`.

- [x] `C04` Reporting/scoring service family.
  Notes: Resolved on 2026-03-03 by slices 2-5 (shared category/subtype contracts, dispatch/factory alignment, metadata/logger cleanup).

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

## H03 Contract and Behavior Baseline

Archived from `TASKS.md` on 2026-03-03 after H03 closure.

- [x] `H03` Contract and behavior baseline.
  Goal: capture current expected behavior for key flows before refactors.
  Deliverable: minimal contract suite (controller/service integration + key UI/API flows).
  Exit criteria: high-impact flows have regression coverage and a known pass/fail baseline.
  Notes: Completed on 2026-03-03. H03 is now the default pre-refactor safety baseline for reporting/export/ranking flows.

### H03 Subtasks

- [x] `H03-S01` Identify and rank critical runtime flows for contract coverage.
  Deliverable: `docs/h03-flow-priority-map.md`.
  Notes: Completed.

- [x] `H03-S02` Define contract schema for prioritized flows.
  Deliverable: `docs/h03-contract-schema.md`.
  Notes: Completed.

- [x] `H03-S03` Capture reporting/service characterization contracts.
  Deliverable: `docs/h03-reporting-contracts.md`.
  Notes: Completed.

- [x] `H03-S04` Add controller-level behavior characterization tests for top flows.
  Deliverable: controller contract tests for User/AdminGroup/AdminView/Export high-priority routes.
  Notes: Completed.

- [x] `H03-S05` Add facade/application contract tests for orchestration outputs.
  Deliverable: expanded characterization tests for `UserReportFacade`, `GroupCnfisExportFacade`, `RankingMaintenanceFacade`.
  Notes: Completed.

- [x] `H03-S06` Assemble and enforce H03 baseline gate.
  Deliverable: `npm run verify-h03-baseline` + `CONTRIBUTING.md` usage guidance.
  Notes: Completed.

- [x] `H03-S07` Close H03 with adoption notes and forward links to H04.
  Deliverable: H03 closeout/adoption note in `docs/h03-reporting-contracts.md` and task archive updates.
  Notes: Completed on 2026-03-03.

## H04 Test Strategy and Pyramid Rebalance

Archived from `TASKS.md` on 2026-03-03 after H04 closure.

- [x] `H04` Test strategy and pyramid rebalance.
  Goal: reduce fragile end-to-end reliance and improve unit/integration signal quality.
  Deliverable: test taxonomy, gap matrix, and priority test additions.
  Exit criteria: each critical feature has at least one stable automated regression test.
  Notes: Completed on 2026-03-03. H04 is now the active testing playbook baseline for refactor safety.

### H04 Subtasks

- [x] `H04-S01` Build current test inventory and taxonomy map.
  Deliverable: `docs/h04-test-inventory.md`.
  Notes: Completed.

- [x] `H04-S02` Define target pyramid and quality criteria.
  Deliverable: `docs/h04-test-strategy.md`.
  Notes: Completed.

- [x] `H04-S03` Create risk-weighted gap matrix for critical flows.
  Deliverable: `docs/h04-gap-matrix.md`.
  Notes: Completed.

- [x] `H04-S04` Add missing unit tests for scorer/support logic hotspots.
  Deliverable: focused unit coverage additions for `G01-G03`.
  Notes: Completed.

- [x] `H04-S05` Add integration/slice tests for cross-layer seams.
  Deliverable: targeted contract/slice coverage additions for `G04-G07`.
  Notes: Completed; `G08` deferred to S06 infrastructure policy and then partially resolved.

- [x] `H04-S06` Introduce reliability and runtime guardrails for test execution.
  Deliverable: `verify-h04-baseline`, `verify-h04-mongo-integration`, `docs/h04-reliability-guardrails.md`.
  Notes: Completed; `G09` resolved and `G08` initial Testcontainers tranche implemented.

- [x] `H04-S07` Close H04 with adoption notes and handoff to H05.
  Deliverable: H04 closeout section in `docs/h04-test-strategy.md` + task archive updates.
  Notes: Completed on 2026-03-03.

## H05 Frontend Structure and Asset Discipline

Archived from `TASKS.md` on 2026-03-03 after H05 closure.

- [x] `H05` Frontend structure and asset discipline.
  Goal: standardize JS/CSS/template patterns to avoid divergent implementations.
  Deliverable: frontend conventions (entrypoints, shared utilities, template composition patterns).
  Exit criteria: duplicated UI logic is centralized and new pages follow the same conventions.
  Notes: Completed on 2026-03-03. H05 baseline is active via `docs/h05-frontend-map.md`, `docs/h05-frontend-conventions.md`, shared frontend modules, and template guardrails.

### H05 Subtasks

- [x] `H05-S01` Build frontend structure map and duplication baseline.
  Deliverable: `docs/h05-frontend-map.md`.
  Notes: Completed.

- [x] `H05-S02` Define frontend conventions and ownership rules.
  Deliverable: `docs/h05-frontend-conventions.md`.
  Notes: Completed.

- [x] `H05-S03` Extract shared template composition primitives.
  Deliverable: shared core template fragments + migrated includes.
  Notes: Completed (`core-styles`/`core-scripts` fragments and template migrations).

- [x] `H05-S04` Introduce frontend utility modules for repeated JS behavior.
  Deliverable: shared modules under `frontend/src/modules/shared/**`.
  Notes: Completed (`domBehaviors.js`, `publicationSubtypeSync.js`, module-backed template behavior).

- [x] `H05-S05` Add guardrails for template/asset composition drift.
  Deliverable: hardened `scripts/verify-template-assets.js`.
  Notes: Completed (CDN allowlist enforcement, inline-script transitional allowlist, canonical datatables path check).

- [x] `H05-S06` Add focused frontend behavior regression checks.
  Deliverable: expanded frontend-facing controller contract tests.
  Notes: Completed (`UserViewControllerContractTest`, `AdminViewControllerContractTest`).

- [x] `H05-S07` Close H05 with adoption notes and handoff to H06.
  Deliverable: H05 closeout note in H05 docs + task archive updates.
  Notes: Completed on 2026-03-03.

## H06 Data and Persistence Consistency Review

Archived from `TASKS.md` on 2026-03-03 after H06 closure.

- [x] `H06` Data and persistence consistency review.
  Goal: verify entity design, migrations/data files, transaction boundaries, and query patterns for inconsistencies.
  Deliverable: persistence risk report and remediation plan.
  Exit criteria: integrity risks and performance hotspots are tracked with clear fixes.
  Notes: Completed on 2026-03-03. H06 is now the persistence baseline for future remediation and H07 planning.

### H06 Subtasks

- [x] `H06-S01` Build persistence architecture map and entity ownership baseline.
  Deliverable: `docs/h06-persistence-map.md`.
  Notes: Completed.

- [x] `H06-S02` Inventory schema and data-shape drift risks.
  Deliverable: `docs/h06-schema-drift-inventory.md`.
  Notes: Completed.

- [x] `H06-S03` Review query patterns and consistency semantics.
  Deliverable: `docs/h06-query-consistency-findings.md`.
  Notes: Completed.

- [x] `H06-S04` Define canonical persistence contracts.
  Deliverable: `docs/h06-persistence-contracts.md`.
  Notes: Completed.

- [x] `H06-S05` Add focused persistence regression tests for highest risks.
  Deliverable: targeted repository/service characterization tests + minimal consistency fixes.
  Notes: Completed on 2026-03-03 (`PersistenceYearSupport`, CNFIS year-filter hardening, ranking ISSN cache alignment, guard tests).

- [x] `H06-S06` Define phased remediation plan and guardrails.
  Deliverable: `docs/h06-remediation-plan.md` + lightweight persistence verification.
  Notes: Completed on 2026-03-03. Added `npm run verify-h06-persistence`.

- [x] `H06-S07` Close H06 with adoption notes and handoff to H07.
  Deliverable: H06 closeout note + archive updates.
  Notes: Completed on 2026-03-03. Handoff direction: keep `R1 -> R4` order (`R1` citation uniqueness index/migration first) when resuming persistence remediation.

## H07 Error Handling, Validation, and Security Hardening

Archived from `TASKS.md` on 2026-03-04 after H07 closure.

- [x] `H07` Error handling, validation, and security hardening.
  Goal: unify input validation, exception mapping, auth/authz checks, and security defaults.
  Deliverable: standardized error/validation/security checklist with implementation gaps.
  Exit criteria: critical endpoints and forms comply with one consistent policy.
  Notes: Completed on 2026-03-04. H07 is now the security/validation/error baseline for H08+ planning and remediation sequencing.

### H07 Subtasks

- [x] `H07-S01` Build endpoint and trust-boundary security map.
  Deliverable: `docs/h07-security-surface-map.md`.
  Notes: Completed.

- [x] `H07-S02` Inventory validation and binding drift risks.
  Deliverable: `docs/h07-validation-drift-inventory.md`.
  Notes: Completed.

- [x] `H07-S03` Inventory exception/error handling consistency gaps.
  Deliverable: `docs/h07-error-handling-findings.md`.
  Notes: Completed.

- [x] `H07-S04` Define canonical H07 contracts and policies.
  Deliverable: `docs/h07-security-validation-contracts.md`.
  Notes: Completed.

- [x] `H07-S05` Add focused regression guards for highest H07 risks.
  Deliverable: targeted characterization tests for auth/validation/error paths + error boundary tests.
  Notes: Completed on 2026-03-04 (mixed unauthorized semantics, parse/role exception baselines, upload baseline, mutating-GET baseline, access-denied redirect and error template mappings).

- [x] `H07-S06` Define phased remediation plan and lightweight enforcement.
  Deliverable: `docs/h07-remediation-plan.md` + `npm run verify-h07-guardrails`.
  Notes: Completed on 2026-03-04 (`R1..R4` remediation sequence and debt-aware guardrails).

- [x] `H07-S07` Close H07 with adoption notes and handoff to H08.
  Deliverable: H07 closeout/adoption note + archive updates.
  Notes: Completed on 2026-03-04. H08 handoff: keep H07 contracts (`C1..C10`) as fixed inputs; preserve guardrail command until remediation slices are executed.

## H08 Observability and Operability Foundation

Archived from `TASKS.md` on 2026-03-04 after H08 closure.

- [x] `H08` Observability and operability foundation.
  Goal: make failures diagnosable with structured logs, metrics, and health/readiness signals.
  Deliverable: minimum observability baseline and runbook starter.
  Exit criteria: common production failure modes are detectable and actionable.
  Notes: Completed on 2026-03-04. H08 baseline is active via H08 maps/findings/contracts, observability guardrails, and `verify-h08-baseline` enforcement command.

### H08 Subtasks

- [x] `H08-S01` Build observability surface map and signal inventory.
  Deliverable: `docs/h08-observability-map.md`.
  Notes: Completed.

- [x] `H08-S02` Inventory logging and diagnostics drift risks.
  Deliverable: `docs/h08-logging-drift-inventory.md`.
  Notes: Completed.

- [x] `H08-S03` Inventory health/readiness/operability gaps.
  Deliverable: `docs/h08-operability-findings.md`.
  Notes: Completed.

- [x] `H08-S04` Define canonical observability and operability contracts.
  Deliverable: `docs/h08-observability-contracts.md`.
  Notes: Completed.

- [x] `H08-S05` Add focused observability regression guards.
  Deliverable: `docs/h08-regression-guards.md` + `npm run verify-h08-observability-guardrails`.
  Notes: Completed on 2026-03-04.

- [x] `H08-S06` Define phased remediation plan and lightweight enforcement.
  Deliverable: `docs/h08-remediation-plan.md` + `npm run verify-h08-baseline`.
  Notes: Completed on 2026-03-04.

- [x] `H08-S07` Close H08 with adoption notes and handoff to H09.
  Deliverable: H08 closeout note + archive updates.
  Notes: Completed on 2026-03-04. H09 handoff: promote `verify-h08-baseline` into CI-required gates and keep remediation slices ordered `P0 -> P1 -> P2`.

## TASKS.md Archive Snapshot (2026-03-04)

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

- [x] `H06` Data and persistence consistency review.
  Goal: verify entity design, migrations/data files, transaction boundaries, and query patterns for inconsistencies.
  Deliverable: persistence risk report and remediation plan.
  Exit criteria: integrity risks and performance hotspots are tracked with clear fixes.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H06-S01..S07 completion details, guardrails, and H07 handoff guidance.

- [x] `H07` Error handling, validation, and security hardening.
  Goal: unify input validation, exception mapping, auth/authz checks, and security defaults.
  Deliverable: standardized error/validation/security checklist with implementation gaps.
  Exit criteria: critical endpoints and forms comply with one consistent policy.
  Status: completed on 2026-03-04.
  Note: archived in `TASKS-done.md` with H07-S01..S07 completion details, regression guards, and H08 handoff guidance.

- [x] `H08` Observability and operability foundation.
  Goal: make failures diagnosable with structured logs, metrics, and health/readiness signals.
  Deliverable: minimum observability baseline and runbook starter.
  Exit criteria: common production failure modes are detectable and actionable.
  Status: completed on 2026-03-04.
  Note: archived in `TASKS-done.md` with H08-S01..S07 completion details, guardrails, and H09 handoff guidance.

- [x] `H09` Build, CI, and quality gates.
  Goal: ensure every change passes reproducible checks and prevents regressions from merging.
  Deliverable: CI pipeline definition with lint/test/build/security gates.
  Exit criteria: required checks are automated and block broken changes.
  Status: completed on 2026-03-04.
  Note: CI hardening is enforced via `.github/workflows/h09-quality-gates.yml` (`guardrails`, `java-smoke`, `quality-full`) and `.github/workflows/h09-security-gates.yml` (`dependency-review`, `codeql-analysis`), with local parity command `npm run verify-h09-baseline`.

- [x] `H10` Documentation and contribution workflow.
  Goal: align README/CONTRIBUTING with actual architecture, setup, and delivery flow.
  Deliverable: contributor playbook for local dev, testing, and release hygiene.
  Exit criteria: a new contributor can run, test, and modify the project without tribal knowledge.
  Status: completed on 2026-03-04.
  Note: completed via `H10-S01..S08` with consolidated docs pack (`docs/h10-*.md`) and walkthrough validation evidence in `docs/h10-validation-walkthrough.md`.

### H10 Subtasks (Planned)

- [x] `H10-S01` Documentation inventory and gap map.
  Goal: map current docs (`README`, `CONTRIBUTING`, `docs/*`) against actual workflows and guardrails.
  Deliverable: `docs/h10-doc-inventory.md` with outdated/missing sections and owners.
  Exit criteria: all contributor-critical gaps are identified and prioritized.
  Status: completed on 2026-03-04.
  Note: added `docs/h10-doc-inventory.md` with source coverage matrix, owner mapping, and prioritized closure order for `H10-S02..S08`.

- [x] `H10-S02` Local setup and runbook alignment.
  Goal: make first-run setup deterministic for new contributors.
  Deliverable: updated `README.md` with prerequisites, local run, config overrides, and troubleshooting.
  Exit criteria: a new contributor can boot the app and run smoke checks without tribal knowledge.
  Status: completed on 2026-03-04.
  Note: `README.md` now includes a deterministic first-run quickstart, explicit config override options, health endpoint contract, and local troubleshooting baseline aligned with H09 parity checks.

- [x] `H10-S03` Contributor workflow playbook.
  Goal: define one clear change workflow from branch creation to PR merge.
  Deliverable: updated `CONTRIBUTING.md` (branching, commit conventions, required local checks, PR expectations).
  Exit criteria: workflow is explicit and consistent with enforced CI gates.
  Status: completed on 2026-03-04.
  Note: `CONTRIBUTING.md` now defines an end-to-end contributor workflow and change-type verification matrix aligned with enforced H09 CI checks.

- [x] `H10-S04` Quality gate command matrix.
  Goal: document when to run each verification command (`H03`-`H09` baselines and guardrails).
  Deliverable: `docs/h10-quality-gates-matrix.md` (`change type -> required commands`).
  Exit criteria: contributors can select required checks by change scope.
  Status: completed on 2026-03-04.
  Note: added `docs/h10-quality-gates-matrix.md` and linked it from `CONTRIBUTING.md` as the canonical change-type command selector.

- [x] `H10-S05` Failure triage and debugging guide.
  Goal: reduce time-to-fix for common guardrail/CI failures.
  Deliverable: troubleshooting sections for architecture, persistence, security, observability, and CI jobs.
  Exit criteria: each required CI check has a `failure -> likely cause -> fix path`.
  Status: completed on 2026-03-04.
  Note: added `docs/h10-failure-triage.md` with guardrail/build/security CI triage matrix and linked it from `CONTRIBUTING.md`.

- [x] `H10-S06` Release hygiene baseline.
  Goal: define minimal release-safe merge hygiene.
  Deliverable: PR checklist + merge/release checklist (risk notes, rollback notes, evidence commands).
  Exit criteria: release-affecting changes follow a documented checklist.
  Status: completed on 2026-03-04.
  Note: added `docs/h10-release-hygiene.md` with PR/merge/evidence/rollback baseline and linked it from `CONTRIBUTING.md`.

- [x] `H10-S07` Docs governance and ownership.
  Goal: prevent documentation drift after H10 completion.
  Deliverable: docs ownership table, update triggers, and review cadence policy.
  Exit criteria: each key doc has an owner and mandatory update triggers.
  Status: completed on 2026-03-04.
  Note: added `docs/h10-doc-governance.md` with ownership matrix, mandatory update triggers, and review cadence; linked policy from `CONTRIBUTING.md`.

- [x] `H10-S08` Validation and closure.
  Goal: verify the documentation workflow works in practice.
  Deliverable: one walkthrough by a fresh-contributor path plus fixes, then H10 closeout note in `TASKS.md`.
  Exit criteria: all H10 docs are updated, cross-linked, and validated with current commands.
  Status: completed on 2026-03-04.
  Note: added `docs/h10-validation-walkthrough.md` with executed command evidence (`npm run verify-h09-baseline`, `./gradlew bootRun -m`) and successful outcomes.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.



`H01`-`H02` subtasks and closure details are archived in `TASKS-done.md`.

## Remediation Execution Backlog (Actionable)

Source set reviewed: `docs/h02-remediation-plan.md`, `docs/h06-remediation-plan.md`, `docs/h07-remediation-plan.md`, `docs/h08-remediation-plan.md` and linked findings/contracts inventories.

### P0 (High Priority)

- [x] `B01` H06-R1: Enforce citation pair uniqueness at DB level.
  Goal: close `Q-H06-02` with persistence-layer guarantees.
  Scope:
  - add compound unique index for citation (`citedId`, `citingId`);
  - implement one-time safe dedupe migration for existing duplicates;
  - keep app-level duplicate guard as defense in depth.
  Inputs: `docs/h06-remediation-plan.md` (`R1`), `docs/h06-query-consistency-findings.md`.
  Done criteria: duplicate citation writes are rejected by DB; migration is reproducible and documented.
  Status: completed on 2026-03-04.
  Note: added `CitationUniquenessMigrationService` + gated runner (`off|report|apply`) with keep-lowest-id dedupe and runtime unique index `uniq_cited_citing`; added unit + integration coverage.

- [x] `B02` H07-R1: Authorization scope and 401/403 semantics alignment.
  Goal: close `S-H07-01`, `E-H07-02`, `E-H07-04`.
  Scope:
  - explicitly scope privileged MVC/API routes;
  - enforce zone contract (MVC redirect-to-login, API 401 JSON; denied -> MVC 403 view/API 403 JSON).
  Inputs: `docs/h07-remediation-plan.md` (`R1`), `docs/h07-security-validation-contracts.md`.
  Done criteria: no privileged route depends only on `anyRequest().authenticated()`; behavior is consistent by zone.
  Status: completed on 2026-03-04.
  Note: added explicit `/admin/**`, `/api/admin/**`, `/api/export/**`, `/api/scrape/**` authority scoping and API-aware JSON `401/403` handlers; normalized `/user/**` unauthenticated flow to login redirect with filter-enabled security contract tests.

- [x] `B03` H08-P0: Logging hygiene and disclosure cleanup.
  Goal: close `L-H08-01`, `L-H08-04`, `L-H08-08`, `L-H08-05`, `O-H08-06`.
  Scope:
  - remove runtime `printStackTrace` and `System.out/System.err` in active paths;
  - fix logger owner drift (`ComputerScienceBookService`);
  - remove raw external payload logging in `ScopusService#parseToken`;
  - preserve endpoint behavior while improving diagnostics.
  Inputs: `docs/h08-remediation-plan.md` (`P0`), `docs/h08-logging-drift-inventory.md`.
  Done criteria: H08 allowlists shrink accordingly; failures are logged with structured context.
  Status: completed on 2026-03-04.
  Note: replaced active runtime `printStackTrace` and targeted `System.out/System.err` in transport/service/importing/reporting paths; fixed `ComputerScienceBookService` logger owner drift; removed raw payload print in `ScopusService#parseToken`; tightened `verify-h08-observability-guardrails` allowlists.

### P1 (Medium-High Priority)

- [x] `B04` H06-R2: Complete year-parsing safety rollout.
  Goal: close remaining `Q-H06-03` paths under contract `C3`.
  Scope:
  - replace remaining raw year parsing in high-impact report/export/search/grouping flows with `PersistenceYearSupport`;
  - finalize policy for `ActivityInstance#getYear`.
  Inputs: `docs/h06-remediation-plan.md` (`R2`), `docs/h06-persistence-contracts.md`.
  Done criteria: no raw `substring(0,4)` year filtering/grouping remains in targeted high-impact flows.
  Status: completed on 2026-03-04.
  Note: rolled out helper-based year parsing across scoring/grouping/export hotspots; added `PersistenceYearSupport.extractYearString(...)` and `ActivityInstance#getYearOptional()`; expanded `verify-h06-persistence` to enforce no raw year parsing regression on remediated files.

- [x] `B05` H06-R3: Identity/order/dedupe consistency.
  Goal: close `Q-H06-04`, `Q-H06-06`, `Q-H06-07`.
  Scope:
  - normalize `id`/`eid`/`doi` lookup usage per contract;
  - enforce deterministic sorting for user-visible lists/exports;
  - remove author-aggregation duplicate amplification.
  Inputs: `docs/h06-remediation-plan.md` (`R3`), `docs/h06-query-consistency-findings.md`.
  Done criteria: stable ordering and deduped outputs are covered by tests.
  Status: completed on 2026-03-04.
  Note: user publication aggregation now dedupes by publication ID; deterministic publication/citation ordering contract applied across user/admin/group hotspots; user edit/save flow naming normalized to canonical DB `id`; `verify-h06-persistence` extended with `R3` guard checks.

- [x] `B06` H07-R2: Validation boundary hardening.
  Goal: close `V-H07-01`, `V-H07-02`, `V-H07-03`, `V-H07-06`.
  Scope:
  - DTO + `@Valid` rollout for top-risk write and import endpoints;
  - safe/bounded parsing for `start/end` and role conversion;
  - deterministic 4xx behavior for malformed input.
  Inputs: `docs/h07-remediation-plan.md` (`R2`), `docs/h07-validation-drift-inventory.md`.
  Done criteria: boundary validation enforced on targeted endpoints; invalid input no longer escapes as 5xx.
  Status: completed on 2026-03-04.
  Note: migrated `/api/admin/users` + `/api/admin/researchers` create/update to DTO + `@Valid`; replaced CNFIS start/end `Integer.parseInt` with bounded year-range validation returning `400`; added role allowlist validation in `/admin/users/create` with redirect+flash fallback; updated H07 guardrails and regression tests.

- [x] `B07` H07-R3: Centralized exception mapping and transport logging cleanup.
  Goal: close `E-H07-01`, `E-H07-03`, `E-H07-05`, `E-H07-06`, `E-H07-07`.
  Scope:
  - introduce `@ControllerAdvice` mappings for common failure classes;
  - remove catch-and-print/swallowed exceptions on transport paths;
  - align API/MVC error envelopes/views.
  Inputs: `docs/h07-remediation-plan.md` (`R3`), `docs/h07-error-handling-findings.md`.
  Done criteria: consistent mapped error behavior with structured diagnostics.
  Status: completed on 2026-03-04.
  Note: added split centralized exception mapping (`ApiExceptionHandler` + `MvcExceptionHandler`), switched `UserService.updateUser` to `Optional` with deterministic `404` in controller, tightened `/api/export` to deterministic failure behavior, and extended `verify-h07-guardrails` to block generic export swallow-catch regressions.

- [x] `B07A` H07 login flow practical standards alignment.
  Goal: align login flow with modern browser/password-manager and explicit form-login contracts.
  Scope:
  - login template semantic/autocomplete metadata;
  - explicit Spring form-login + logout endpoints/redirects;
  - security regression tests for login success/failure/logout;
  - H07 guardrail checks for login input naming/autocomplete contract.
  Inputs: login baseline plan (practical scope), `docs/h07-security-validation-contracts.md`.
  Done criteria: deterministic login/logout contract + test/guardrail coverage.
  Status: completed on 2026-03-04.
  Note: `/login` GET/POST contract is explicit; invalid credentials redirect to `/login?error`, logout redirects to `/login?logout`; login template now uses `name=\"username\"/\"password\"` with `autocomplete=\"username\"/\"current-password\"`; guardrails enforce these attributes.

- [x] `B08` H08-P1: Correlation context propagation.
  Goal: close `L-H08-02`, `L-H08-06`, `L-H08-07`, `O-H08-07`.
  Scope:
  - add request correlation IDs for HTTP flows;
  - standardize scheduler context (`jobType`, `taskId`, phase);
  - ensure error logs include correlation context and align with H07 mappings.
  Inputs: `docs/h08-remediation-plan.md` (`P1`), `docs/h08-observability-contracts.md`.
  Done criteria: request/job traces are diagnosable end-to-end.
  Status: completed on 2026-03-04.
  Note: implemented `X-Request-Id` adopt-and-propagate filter + request MDC (`requestId`, `route`, `userId`); added Scopus scheduler context helper and phase-aware MDC (`jobType`, `taskId`, `phase`) for batch/per-task logs; centralized exception handlers now include request correlation context; `verify-h08-observability-guardrails` extended with B08 checks.

- [x] `B09` H09 bootstrap: Promote local guardrails to required CI checks.
  Goal: operationalize H02/H06/H07/H08 enforcement in pipeline.
  Scope:
  - include `verify-architecture-boundaries`, `verify-h06-persistence`, `verify-h07-guardrails`, `verify-h08-baseline` as required CI checks;
  - document policy for tightening/allowlist shrink.
  Inputs: `docs/h08-remediation-plan.md` (H09 handoff), remediation guardrail docs.
  Done criteria: CI blocks merges on guardrail failure.
  Status: completed on 2026-03-04.
  Note: added GitHub Actions workflow `.github/workflows/h09-quality-gates.yml` with `guardrails` and `java-smoke` jobs plus failure artifact upload; documented Stage 1 soft rollout and Stage 2 required-check transition in `docs/h09-ci-gates.md`; included H08 baseline handoff confirmation.

### P2 (Planned / Structural)

- [x] `B10` H06-R4: Persistence consistency cleanup and namespace hygiene.
  Goal: close `Q-H06-05`, `Q-H06-08`, `Q-H06-09`, `D-H06-03`.
  Scope:
  - text-search normalization policy rollout;
  - retire typo’d repo API (`findAllByeIssn`) via compatibility step;
  - forum export dedupe normalization (remove sentinel checks);
  - plan and execute collection naming migration (`schodardex` -> `scholardex`).
  Inputs: `docs/h06-remediation-plan.md` (`R4`), `docs/h06-schema-drift-inventory.md`.
  Done criteria: API naming and data-shape drift items have closed implementation path.
  Status: completed on 2026-03-04.
  Note: delivered case-insensitive admin title search normalization, forum export dedupe normalization (`issn -> eIssn -> sourceId`), and single-step task namespace cutover to `scholardex.tasks.*` with startup-gated migration runner (`off|report|apply`) and integration coverage.

- [x] `B10A` H06-R4 follow-up: remove `findAllByeIssn` compatibility alias.
  Goal: complete typo-method retirement after stabilization window.
  Scope:
  - remove deprecated `findAllByeIssn` from `RankingRepository`;
  - tighten `verify-h06-persistence` to zero allowlist for typo method.
  Inputs: `B10` compatibility bridge completion evidence.
  Done criteria: no `findAllByeIssn` references remain in codebase.
  Status: completed on 2026-03-04.
  Note: deprecated alias removed from `RankingRepository`; compatibility test scaffolding removed; `verify-h06-persistence` now enforces zero-allowlist for typo method usage.

- [x] `B11` H07-R4: CSRF, mutating-GET migration, and upload hardening.
  Goal: close `C3`, `C4`, `V-H07-04`.
  Scope:
  - re-enable CSRF for browser form flows with explicit exemptions only when justified;
  - migrate `delete/duplicate` mutating GET routes to safe verbs;
  - enforce upload size/type/schema validation in group import.
  Inputs: `docs/h07-remediation-plan.md` (`R4`), `docs/h07-security-validation-contracts.md`.
  Done criteria: browser mutation routes are CSRF-protected and non-GET; upload policy enforced.
  Status: completed on 2026-03-04.
  Note: CSRF is re-enabled for MVC flows with explicit `/api/**` exemption; mutating `delete/duplicate` GET routes were migrated to POST across targeted controllers/templates; group CSV import now enforces strict size/type/schema validation.

- [x] `B12` H08-P2: Actuator/metrics/readiness baseline implementation.
  Goal: close `O-H08-01`, `O-H08-02`, `O-H08-03`, `O-H08-04`, `O-H08-05`.
  Scope:
  - add actuator and explicit readiness/liveness policy;
  - add minimum metrics coverage for startup/scheduler/export/external dependency calls;
  - add async executor saturation/queue diagnostics;
  - define startup phase readiness semantics.
  Inputs: `docs/h08-remediation-plan.md` (`P2`), `docs/h08-operability-findings.md`, `docs/h08-observability-contracts.md`.
  Done criteria: production failure modes are machine-detectable via health and metrics endpoints.
  Status: completed on 2026-03-04.
  Note: actuator baseline and readiness/liveness groups are active, startup/external dependency health contributors are wired, scheduler/export/startup/external metrics are instrumented, async queue/rejection diagnostics are exposed, and H08 observability guardrails now assert P2 baseline wiring.

- [x] `B13` H02 residual V01 closure outside baseline pair.
  Goal: reduce remaining `Z1 -> Z4` controller repository debt in non-baseline controllers.
  Scope:
  - prioritize `AdminViewController` and smaller controllers still directly importing repositories;
  - migrate residual orchestration to Z2 facades while preserving behavior.
  Inputs: `docs/h02-remediation-plan.md` (`R1 residual`), `docs/h02-violations.md`.
  Done criteria: repository-import allowlist in `verify-architecture-boundaries` is materially reduced.
  Status: completed on 2026-03-04.
  Note: residual controllers were migrated to Z2 facades (`AdminCatalogFacade`, `UserRankingFacade`, `ActivityManagementFacade`, `GroupReportsManagementFacade`, `IndividualReportsManagementFacade`, `UrapRankingFacade`, `UserActivityInstanceFacade`, `PublicationWizardFacade`); controller/view repository imports are now zero and architecture allowlist is empty.
