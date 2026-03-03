# Project Recovery Tasks (High-Level)

Objective: turn the current feature bundle into a maintainable, testable, and evolvable product.

Done history moved to `TASKS-done.md`.

## Backlog

- [ ] `H01` Duplicate code and drift audit.
  Goal: identify copy-paste clusters (backend, frontend, templates, scripts) and detect behavior drift between near-identical implementations.
  Deliverable: duplication inventory with risk ranking and consolidation candidates.
  Exit criteria: top high-risk duplicates have an agreed merge strategy and owners.

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

## H01 First Subtask List (Planning Mode Seed)

Scope: `H01` Duplicate code and drift audit.

- [x] `H01-S01` Inventory likely duplicate clusters.
  Goal: produce an initial map of near-duplicate files/areas in backend, templates, and JS.
  Inputs: file tree, naming patterns (`*2025*`, `*-bak*`, similarly named services/controllers).
  Deliverable: `docs/h01-duplication-inventory.md` with cluster IDs and file lists.
  Exit criteria: at least one cluster identified per layer (Java, templates, JS if present).
  Notes: Completed. See `docs/h01-duplication-inventory.md`.

- [ ] `H01-S03` Identify behavioral drift inside top clusters.
  Goal: compare logic paths in near-copies and mark divergence that changes outcomes.
  Inputs: top clusters from `H01-S01`.
  Deliverable: `docs/h01-drift-findings.md` with drift type: harmless, intentional, risky, unknown.
  Exit criteria: each top cluster has a drift decision and evidence snippet references.
  Notes: In progress. `C01`, `C03`, `C04`, `C05`, and `C06` analyzed; `C02`, `C03`, and `C05` resolved by template deletion/consolidation; C06 shared asset contract extracted (`scripts/assets-contract.js`); H01 open questions resolved and recorded in `docs/h01-drift-findings.md`.

- [x] `H01-S04` Prioritize by risk and blast radius.
  Goal: rank duplicates by user impact, regression risk, and change cost.
  Inputs: drift findings, runtime criticality, code ownership uncertainty.
  Deliverable: priority table in `docs/h01-duplication-inventory.md` with `P0/P1/P2`.
  Exit criteria: clear “start here” ordering for refactor work.
  Notes: Completed. Ranked execution order is `C01 (P0)` -> `C04 (P1)` -> `C06 (P2)` in `docs/h01-duplication-inventory.md`.

- [x] `H01-S05` Define consolidation strategy per priority cluster.
  Goal: choose consolidation pattern per cluster (extract shared service, template fragment, utility module, configuration map).
  Inputs: prioritized cluster list.
  Deliverable: `docs/h01-consolidation-strategy.md` with target shape and migration steps.
  Exit criteria: every `P0/P1` cluster has an approved destination design.
  Notes: Completed. See `docs/h01-consolidation-strategy.md` for `C01 (P0)`, `C04 (P1)`, and `C06 (P2)`.

- [x] `H01-S06` Create regression guards before refactor.
  Goal: add tests that freeze behavior for clusters selected for consolidation.
  Inputs: selected clusters and drift notes.
  Deliverable: focused tests (unit/integration/template rendering) and coverage notes.
  Exit criteria: tests fail on behavior change and pass on current intended behavior.
  Notes: Completed on 2026-03-03. Added characterization tests for `C01` and `C04` plus command-level guard coverage for `C06`; see `docs/h01-regression-guards.md`.

- [x] `H01-S07` Execute first consolidation slice (small, high-value).
  Goal: merge one `P0` or two `P1` clusters with minimal public behavior change.
  Inputs: strategy + regression guards.
  Deliverable: code changes + migration notes in PR description or `docs/h01-slice-1.md`.
  Exit criteria: reduced duplicate footprint and all relevant checks green.
  Notes: Completed on 2026-03-03. First slice delivered for `C04` sub-cluster B: `ScoringFactoryService` now fails fast for null/unsupported strategies; `bk` path in combined CS scorer intentionally deferred and documented.

- [ ] `H01-S08` Prevent reintroduction.
  Goal: add lightweight guardrails to detect new duplication/drift early.
  Inputs: patterns and high-risk clusters identified in `H01-S01` and `H01-S03`.
  Deliverable: CI/local check command and contributor note in `CONTRIBUTING.md`.
  Exit criteria: duplication check is documented and runnable in standard workflow.

### H01 Candidate Clusters To Start With

- [ ] `C01` `CNFISScoringService` vs `CNFISScoringService2025` (high drift risk in scoring rules/subtype handling).
- [x] `C02` Admin template backups (`*-bak.html`) vs active templates (stale copy risk and accidental edits).
  Notes: Resolved on 2026-03-03 by deleting `admin/researchers-bak.html`.
- [ ] `C03` Reporting/scoring service family under `service/reporting` (parallel implementations with similar flow).
- [ ] `C04` Controller/view handlers with overlapping admin/user logic (potential copy/paste request-response assembly).
