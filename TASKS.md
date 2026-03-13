# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Backlog

- [ ] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical user/admin workflows across the current canonical architecture (Mongo ingest/facts, WoS/Scopus initialization flows, PostgreSQL reporting projections, and user-facing report/export reads).
  Deliverable: focused workflow-level tests for the highest-value operational paths, using deterministic fixtures and asserting state transitions across controller -> orchestration -> persistence/read-model boundaries.
  Exit criteria: top workflows that now span canonical ingest, projection rebuilds, Postgres-backed reads, and user report/export surfaces are validated across success and failure paths, and regressions are caught before merge by repeatable automated checks.
  Subtasks:
  - [ ] `H13.1` Admin WoS maintenance end-to-end flow.
    Deliverable: deterministic workflow test for the modern admin WoS initialization path, covering canonical WoS steps (`ingest -> build facts/onboarding -> enrich category rankings -> build projections`) plus verification/readiness assertions for the resulting read models.
    Exit criteria: the workflow validates the current initialization surface under `/admin/initialization`, confirms step ordering and expected persistence/read-model effects, and asserts both authorized execution and unauthorized access behavior.
  - [ ] `H13.2` User indicator refresh/export workflow.
    Deliverable: deterministic workflow test covering a representative user reporting path across persisted indicator refresh, individual-report refresh, and workbook export using the current user surfaces and projection-backed read dependencies.
    Exit criteria: refresh actions persist/update the expected run/result state, downstream user-facing views resolve from the refreshed state without recomputation drift, and export response contracts remain stable for the selected workflow.
  - [ ] `H13.3` Failure-path workflow gate.
    Deliverable: at least one full workflow-level degraded scenario exercising a modern critical path failure, such as initialization/projection verification failure, Postgres reporting readiness mismatch, or user report refresh/export degradation.
    Exit criteria: the selected failure mode is reproducible with deterministic fixtures, produces explicit user/operator-visible behavior, and blocks regressions in the corresponding workflow.

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

- [ ] `H25` Uniform entity routes and shared read-view consolidation.
  Goal: eliminate duplicate MVC pages/routes for shared read surfaces across `/user/*` and `/admin/*`, and align navigation with canonical entity-based routes while keeping admin-only management tools separate.
  Deliverable: canonical authenticated MVC routes for shared entities (`/forums`, `/wos/categories`, `/core/rankings`, `/universities`, `/events`), trimmed `/user/*` routes for user-owned surfaces, removal of duplicate admin read views, and role-driven sidebar selection instead of hardcoded admin/user sidebar fragments per template.
  Exit criteria: shared entity reads resolve through one canonical route family regardless of role; duplicate admin read pages for forums/rankings/universities/events are removed; user-owned surfaces remain under `/user/*`; sidebar/navigation is selected by role at runtime rather than hardcoded per template; legacy duplicate read routes are removed and all callers/tests/docs are aligned to the new route model.
  Subtasks:
  - [x] `H25.1` Lock canonical route and ownership contract.
    Deliverable: implementation-ready route contract defining which pages remain user-owned (`/user/dashboard`, `/user/publications`, `/user/activities`) versus which become shared authenticated entity routes (`/forums`, `/wos/categories`, `/core/rankings`, `/universities`, `/events`), plus explicit non-goals for admin-only management pages that stay under `/admin/*`.
    Exit criteria: every currently duplicated read surface has one decision-locked canonical route family and one owner context before code changes start.
    Notes: locked by `docs/h25.1-canonical-route-ownership-contract.md`; H25.2 starts from this mapping as the route ownership gate.
  - [x] `H25.2` Consolidate shared entity MVC routes and remove duplicate admin read pages.
    Deliverable: shared MVC route consolidation for forums, WoS categories, CORE rankings, university rankings, and events, with duplicate `/admin/*` read/list/detail pages removed and callers/templates updated to use the canonical shared routes.
    Exit criteria: shared read entities are no longer exposed through separate admin/user route families, and no duplicate admin MVC page remains for the covered read surfaces.
    Notes: complete with canonical-only shared routes (`/forums`, `/wos/categories`, `/core/rankings`, `/universities`, `/events`), removed legacy public aliases (`/rankings/*`, `/scholardex/forums*`, `/core*`, `/urap*`), and removed admin duplicate read GET aliases under `/admin/rankings/*`.
  - [x] `H25.3` Normalize remaining user-owned route families.
    Deliverable: user route cleanup so personal/user-owned pages remain under a consistent `/user/*` model, including keeping `/user/dashboard` and `/user/publications` and renaming activity-instance reads to `/user/activities`.
    Exit criteria: user-owned routes follow a consistent naming model and no leftover user route uses the old inconsistent entity naming where a cleaner canonical `/user/*` alternative is defined by H25.
    Notes: complete with canonical user routes (`/user/activities*`, `/user/individual-reports*`, `/user/publications/scopus-tasks`, `/user/tasks/scopus/update-publications`, `/user/tasks/scopus/update-citations`, `/user/exports/cnfis`) and immediate removal of legacy aliases (no redirects).
  - [x] `H25.4` Replace hardcoded admin/user sidebar composition with role-based layout selection.
    Deliverable: unified sidebar/layout mechanism that selects navigation content based on the authenticated role/context at render time instead of templates hardcoding `admin-sidebar` vs `user-sidebar`.
    Exit criteria: covered templates no longer choose sidebar fragments manually by route family, and shared entity pages render the correct role-aware navigation from one central mechanism.
    Notes: complete with centralized `fragments :: sidebar(activeSection)` + `sidebarContext` model attribute in `GlobalControllerAdvice`; runtime templates now use one sidebar fragment, with admin-first shared-route behavior and `/user/**` override.
  - [x] `H25.5` Remove stale route debt and align verification/docs.
    Deliverable: delete obsolete duplicate read templates/routes, update route/UI/security tests and any route-map/docs/guardrails that still reference the removed read paths, and record the steady-state route model.
    Exit criteria: automated tests and docs reflect the new canonical route families only, and no removed duplicate read route remains referenced by runtime navigation or verification artifacts.
    Notes: complete with removal of `/admin/scopus/**` MVC compatibility mappings, deletion of dormant runtime templates, updated route guardrails/verifiers, and H25 steady-state route-map documentation.

- [ ] `H26` Canonical user dashboard route and post-H25 naming cleanup.
  Goal: finish the post-H25 cleanup by aligning the remaining runtime route contract, live template/view names, and active docs with the canonical MVC route model already adopted in H25.
  Deliverable: canonical `/user/dashboard` route with `/user` retained only as a compatibility redirect, renamed live MVC template/view names that match canonical entities/routes, and active docs/tests/guardrails updated to reflect the steady-state route model without stale pre-H25 naming.
  Exit criteria: `/user/dashboard` is the documented and implemented dashboard route; `/user` no longer serves as the primary route; live runtime template/view names no longer use stale `scholardex` or camelCase report/activity naming where canonical names now exist; active docs/tests/guardrails describe only current route families except where old routes are intentionally referenced as removal assertions.
  Subtasks:
  - [x] `H26.1` Canonicalize the dashboard route.
    Deliverable: implement `/user/dashboard` as the canonical dashboard endpoint and convert `/user` into a compatibility redirect only.
    Exit criteria: controller mappings, sidebar links, and MVC/security tests treat `/user/dashboard` as canonical; `/user` remains only as a redirect and is not documented as the steady-state route.
    Notes: complete with canonical `GET /user/dashboard`, compatibility-only `GET /user -> redirect:/user/dashboard`, and route/link/test guardrail alignment.
  - [x] `H26.2` Rename live runtime views/templates to canonical entity names.
    Deliverable: rename active template/view names so they match the canonical route/entity model, including the shared forums pages and the normalized user activity/report pages.
    Exit criteria: live templates no longer use stale names such as `scholardex/forums`, `scholardex/forum-detail`, `user/individualReports`, `user/individualReport-view`, `user/activity-instances`, `user/activity-instances-edit`, or `user/ranking-not-found` when canonical route/entity-aligned names are available.
    Notes: complete with canonical shared/user view-name migration (`forums/*`, `wos/*`, `core/*`, `universities/*`, `events/*`, `shared/not-found`, `user/individual-reports*`, `user/activities*`), physical template moves, and guardrail/contract test alignment without MVC route-path changes.
  - [ ] `H26.3` Clean up active route-documentation drift.
    Deliverable: update active docs and route/flow maps that still present removed aliases as current runtime behavior.
    Exit criteria: active docs are aligned to `/user/dashboard`, `/user/individual-reports`, `/user/publications/scopus-tasks`, `/user/exports/cnfis`, and the H25 shared route families; only historical inventory docs retain removed-route references.
  - [ ] `H26.4` Tighten verification around canonical naming and aliases.
    Deliverable: refresh controller/security/guardrail coverage so canonical routes and renamed views are protected, while old-route references remain only in explicit removed-alias assertions.
    Exit criteria: tests fail if `/user` reverts to primary-dashboard behavior, if canonical dashboard links regress, or if removed aliases are reintroduced; guardrails/docs distinguish active routes from historical removals.
