# Done Tasks

Archived completed tasks moved from `TASKS.md` on 2026-03-03.

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
