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

- [ ] `B13` H02 residual V01 closure outside baseline pair.
  Goal: reduce remaining `Z1 -> Z4` controller repository debt in non-baseline controllers.
  Scope:
  - prioritize `AdminViewController` and smaller controllers still directly importing repositories;
  - migrate residual orchestration to Z2 facades while preserving behavior.
  Inputs: `docs/h02-remediation-plan.md` (`R1 residual`), `docs/h02-violations.md`.
  Done criteria: repository-import allowlist in `verify-architecture-boundaries` is materially reduced.
