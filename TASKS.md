# Project Recovery Tasks (High-Level)

Objective: turn the current feature bundle into a maintainable, testable, and evolvable product.

Done history moved to `TASKS-done.md`.

## Backlog

- `H01` completed and archived in `TASKS-done.md`.

- [ ] `H02` Architecture boundaries and ownership.
  Goal: define module boundaries, responsibilities, and allowed dependencies between layers.
  Deliverable: lightweight architecture map and dependency rules.
  Exit criteria: new code placement rules are documented and enforceable in review.

- [ ] `H03` Contract and behavior baseline.
  Goal: capture current expected behavior for key flows before refactors.
  Deliverable: minimal contract suite (controller/service integration + key UI/API flows).
  Exit criteria: high-impact flows have regression coverage and a known pass/fail baseline.

- [ ] `H04` Test strategy and pyramid rebalance.
  Goal: reduce fragile end-to-end reliance and improve unit/integration signal quality.
  Deliverable: test taxonomy, gap matrix, and priority test additions.
  Exit criteria: each critical feature has at least one stable automated regression test.

- [ ] `H05` Frontend structure and asset discipline.
  Goal: standardize JS/CSS/template patterns to avoid divergent implementations.
  Deliverable: frontend conventions (entrypoints, shared utilities, template composition patterns).
  Exit criteria: duplicated UI logic is centralized and new pages follow the same conventions.

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

- [ ] `H02-S05` Define phased remediation plan for violations.
  Goal: prioritize fixes by blast radius and effort without blocking delivery.
  Inputs: violation inventory + ownership matrix.
  Deliverable: `docs/h02-remediation-plan.md` with phased slices (`R1`, `R2`, ...).
  Exit criteria: top-priority violations have actionable implementation slices and sequencing.

- [ ] `H02-S06` Add lightweight enforcement in workflow.
  Goal: add practical checks/review guardrails so boundaries stay intact.
  Inputs: dependency rules + remediation strategy.
  Deliverable: checks and contributor guidance updates (`CONTRIBUTING.md`, optional scripts/CI rule).
  Exit criteria: at least one automated or checklist-based gate prevents new boundary violations.

- [ ] `H02-S07` Close H02 with adoption notes.
  Goal: finalize architecture baseline and usage guidance for future tasks.
  Inputs: completed H02 artifacts and enforcement setup.
  Deliverable: H02 closeout note in `docs/h02-boundaries-and-ownership.md` + `TASKS.md` status updates.
  Exit criteria: H02 can be treated as reference baseline for H03+ planning and implementation.
