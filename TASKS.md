# Project Recovery Tasks (High-Level)

Objective: raise runtime functional quality from baseline-safe behavior to production-grade correctness and resilience.

Done history moved to `TASKS-done.md`.

## Backlog

- [x] `H11` Functional contract hardening and null-safety normalization.
  Goal: eliminate ambiguous/null-based success/failure paths in core services/controllers and replace them with deterministic contracts.
  Deliverable: audited and upgraded service/controller contracts (`Optional`/typed results), plus targeted regression tests for conflict/not-found/error semantics.
  Exit criteria: high-impact runtime paths no longer rely on `return null` or silent fallbacks for control flow, and failure semantics are deterministic and test-covered.
  Status: completed on 2026-03-04.
  Note: migrated core nullable contracts in-scope (`UserService#createUser` overloads, `GlobalControllerAdvice#currentUser`, `CacheService#getCachedTopRankings`, `PublicationWizardFacade#resolveForumId`), added API `409` duplicate-user mapping and MVC redirect+flash wizard failure contract, and extended regression/guardrail coverage.

- [x] `H12` External integration and import correctness uplift.
  Goal: harden external data acquisition/import flows so parser, mapping, and partial-failure behavior are explicit and reliable.
  Deliverable: completed/parity-checked Scopus/import processing paths, strict mapping validation, and robust retry/error contracts for scheduler/import jobs.
  Exit criteria: external-data and import workflows have deterministic outcomes (including degraded/failure paths), with integration tests covering critical mapping and error scenarios.
  Status: completed on 2026-03-04.
  Note: added bounded retry/backoff metadata and transitions for Scopus tasks, removed scheduler forced reimport/date hack paths, hardened Scopus JSON mapping with skip+warn partial ingest behavior, added deterministic importer summary accounting (Ranking/CORE/URAP), and introduced `npm run verify-h12-integrations`.

- [ ] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical user/admin business workflows end-to-end under realistic conditions.
  Deliverable: focused high-value functional test suite (multi-step user/admin/report/export flows) with deterministic fixtures and clear pass/fail contracts.
  Exit criteria: top business workflows are validated across success and failure paths, and functional regressions are caught before merge by repeatable automated checks.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
