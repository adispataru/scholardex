# H02-S02 Boundaries and Ownership (Target State)

Date: 2026-03-03  
Status: H02 closed; this is the active architecture baseline. Explicit allow/deny dependency rules are defined in `docs/tasks/closed/h02-dependency-rules.md`.

## 1. Purpose

Define a clear target architecture for this codebase so new work has predictable placement, ownership, and review routing.

Inputs used:
- `docs/tasks/closed/h02-architecture-map.md`
- H01 drift cleanup outcomes (C01/C03/C04/C05/C06 closures and guardrails)

## 2. Target Zones

## Z1. Entry and Transport

Scope:
- `src/main/java/ro/uvt/pokedex/core/view/**`
- `src/main/java/ro/uvt/pokedex/core/controller/**`
- `src/main/java/ro/uvt/pokedex/core/config/WebSecurityConfig.java`
- `src/main/java/ro/uvt/pokedex/core/config/GlobalControllerAdvice.java`

Responsibilities:
- HTTP route mapping, auth entry checks, request/response mapping, model assembly for templates.
- No business scoring rules, no ranking/cache mutation rules, no direct formula evaluation logic.

Allowed dependencies:
- Z2 Application Orchestration
- Z6 UI Composition

Restricted:
- Direct Z4 Data Access usage should be temporary and explicitly tracked as debt in H02-S04.

## Z2. Application Orchestration

Scope:
- `src/main/java/ro/uvt/pokedex/core/service/*.java` (except heavy domain/scoring implementations)
- `src/main/java/ro/uvt/pokedex/core/service/scopus/**`
- `src/main/java/ro/uvt/pokedex/core/service/importing/**`

Responsibilities:
- Use-case orchestration, async/scheduled workflows, external service integration coordination.
- Transaction-like flow orchestration across repositories/services.

Allowed dependencies:
- Z3 Domain Scoring/Rules
- Z4 Data Access
- Z5 External Integrations

## Z3. Domain Scoring and Rules

Scope:
- `src/main/java/ro/uvt/pokedex/core/service/reporting/**`

Responsibilities:
- Indicator scoring strategies, scoring factory routing, category/subtype support, CNFIS 2025 scoring/export rules.
- Pure domain/rule decisions with minimal transport concerns.

Allowed dependencies:
- Z4 Data Access (read-oriented)
- shared utility/helpers inside reporting package

Restricted:
- No dependency back to controllers/views.

## Z4. Data Access and Persistence Model

Scope:
- `src/main/java/ro/uvt/pokedex/core/repository/**`
- `src/main/java/ro/uvt/pokedex/core/model/**`

Responsibilities:
- Mongo repository interfaces, query methods, persistence document shape.

Allowed dependencies:
- Spring Data + model classes only.

Restricted:
- No service-level workflow logic.

## Z5. External Integration Adapters

Scope:
- `scopusPythonClient` bean in `CoreApplication`
- `src/main/java/ro/uvt/pokedex/core/service/ScopusService.java`
- integration points in `service/scopus/**` and `service/importing/**`

Responsibilities:
- API adapters, request/response translation, network boundary behavior.

Allowed dependencies:
- Z2 orchestration callers
- Z4 for persistence of imported data

## Z6. UI Composition and Asset Contract

Scope:
- `src/main/resources/templates/**`
- `frontend/src/**`
- `scripts/build-assets.js`
- `scripts/verify-assets.js`
- `scripts/verify-template-assets.js`
- `scripts/assets-contract.js`

Responsibilities:
- Thymeleaf composition, frontend bundle entry and static asset contract checks.
- Enforcement of runtime template policies (including `*-bak.html` prohibition).

Allowed dependencies:
- static resources and template fragments
- no backend Java package dependencies

## 3. Ownership Matrix (Role-Based)

| Zone | Primary Owner | Secondary Owner | Review Trigger |
|---|---|---|---|
| Z1 Entry and Transport | Platform Web Owner | Security Owner | route additions, auth changes, controller growth |
| Z2 Application Orchestration | Platform Backend Owner | Data Owner | workflow orchestration, async/scheduler changes |
| Z3 Domain Scoring and Rules | Reporting Domain Owner | Platform Backend Owner | scoring strategy changes, CNFIS/ranking rule edits |
| Z4 Data Access and Persistence Model | Data Owner | Platform Backend Owner | repository query additions, model field changes |
| Z5 External Integration Adapters | Integrations Owner | Platform Backend Owner | external API contract/client behavior changes |
| Z6 UI Composition and Asset Contract | Frontend/Template Owner | Platform Web Owner | template structure, asset contract, build scripts |

Note: owner labels are role responsibilities; map them to specific people/team handles in repository CODEOWNERS follow-up.

## 4. Allowed Responsibility Checklist by Layer

Controllers (`view`/`controller`):
- Allowed: request parsing, auth principal checks, model population, delegation to services.
- Not allowed (target state): scoring formula logic, deep repository composition across multiple aggregates.

Services (`service/**`):
- Allowed: use-case orchestration, domain logic, integration coordination.
- Not allowed: template concerns, direct view model formatting.

Reporting (`service/reporting/**`):
- Allowed: scoring/rule computations and support helpers.
- Not allowed: transport concerns, route-level security logic.

Repositories (`repository/**`):
- Allowed: persistence access/query declarations.
- Not allowed: orchestration/business branching.

Templates/frontend/scripts:
- Allowed: rendering/composition/assets verification.
- Not allowed: hidden runtime fallback assets or backup runtime templates.

## 5. Boundary Priorities for Next Steps

Priority boundary corrections to drive in H02-S03/H02-S04:

1. Reduce direct repository orchestration in large controllers (`AdminViewController`, `UserViewController`, `AdminGroupController`) by introducing/expanding Z2 service facades.
2. Keep all scoring-rule decisions in Z3 and avoid route-level branching drift.
3. Preserve/extend Z6 guardrails so templates remain asset-contract compliant.
4. Make integration adapters explicit in Z5 so network concerns do not leak into route handlers.

## 6. Adoption Notes

- This document defines target zone ownership and responsibilities.
- Dependency allow/deny rules are defined in `docs/tasks/closed/h02-dependency-rules.md`.
- H02-S04 will classify current violations against this baseline.

## 7. H02 Closeout Notes (2026-03-03)

- H02 artifacts are now complete and should be treated as the default architecture reference for H03+ work:
  - `docs/tasks/closed/h02-architecture-map.md`
  - `docs/tasks/closed/h02-boundaries-and-ownership.md`
  - `docs/tasks/closed/h02-dependency-rules.md`
  - `docs/tasks/closed/h02-violations.md`
  - `docs/tasks/closed/h02-remediation-plan.md`
- Workflow gate is active:
  - `npm run verify-architecture-boundaries`
- Operating policy for new work:
  - run boundary checks with regular local verification;
  - do not add new controller/view repository imports without explicit waiver and follow-up task;
  - do not introduce direct transport imports of `service/reporting`;
  - do not reintroduce `CacheService` coupling inside `service/reporting/**`.
- Reopen H02 only when:
  - boundary rules change intentionally, or
  - new violations are detected and need remediation planning.
