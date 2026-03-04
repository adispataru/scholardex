# H02-S03 Dependency Rules (Allow/Deny)

Date: 2026-03-03  
Status: active policy baseline for H02-S04 violation inventory.

## 1. Rule IDs and Zone Mapping

Zone mapping follows `docs/h02-boundaries-and-ownership.md`.

- `Z1` Entry and Transport: `core/view/**`, `core/controller/**`, selected transport config.
- `Z2` Application Orchestration: `core/service/*.java`, `core/service/scopus/**`, `core/service/importing/**`.
- `Z3` Domain Scoring and Rules: `core/service/reporting/**`.
- `Z4` Data Access and Persistence: `core/repository/**`, `core/model/**`.
- `Z5` External Integration Adapters: external client adapters and API-facing integration logic.
- `Z6` UI Composition and Asset Contract: templates/frontend/scripts.

## 2. Allow/Deny Matrix

`A` = allowed direct dependency, `D` = denied direct dependency.

| From \\ To | Z1 | Z2 | Z3 | Z4 | Z5 | Z6 |
|---|---|---|---|---|---|---|
| Z1 Entry/Transport | A | A | D | D (temporary exceptions only) | D | A |
| Z2 App Orchestration | D | A | A | A | A | D |
| Z3 Domain Scoring | D | D | A | A (read-oriented) | D | D |
| Z4 Data/Persistence | D | D | D | A | D | D |
| Z5 Integrations | D | A | D | A | A | D |
| Z6 UI/Templates/Assets | D | D | D | D | D | A |

Interpretation:
- Dependencies are directional and compile-time/runtime call dependencies.
- `Z1 -> Z4` is explicitly debt-only and must be tracked in H02-S04 until migrated behind Z2.

## 3. Explicit Rules

## R1. Controllers Must Orchestrate Through Services

- Applies to: `Z1`.
- Allow: call Z2 orchestration services and return view/API responses.
- Deny: introducing new direct repository dependencies (`core/repository/**`) from controllers/views.
- Temporary exception policy: existing direct repository usage may remain until planned remediation, but new usage is not allowed.
- Adoption update (2026-03-04): V01 repository-debt allowlist in transport layer was retired; controller/view repository imports are now expected to remain at zero.

## R2. Service Layer Is the Only Workflow Composition Layer

- Applies to: `Z2`.
- Allow: compose multiple repositories/services, manage async/scheduled orchestration, call integration adapters.
- Deny: template rendering logic, Thymeleaf/UI formatting, direct transport/security endpoint concerns.

## R3. Reporting Rules Stay in Reporting Package

- Applies to: `Z3`.
- Allow: scoring strategy selection, subtype/category rule helpers, score computation.
- Deny: route/controller logic and external API adapter calls.
- Constraint: scoring behavior changes must not be implemented in Z1/Z2 controllers.

## R4. Repositories and Models Stay Persistence-Focused

- Applies to: `Z4`.
- Allow: query declarations, persistence document definitions.
- Deny: business orchestration logic, formula or scoring logic.

## R5. Integration Adapters Are Isolated

- Applies to: `Z5`.
- Allow: external API calls, mapping external payloads, returning normalized data to Z2.
- Deny: direct controller ownership of external client logic.

## R6. Templates and Assets Have No Backend Logic Coupling

- Applies to: `Z6`.
- Allow: rendering and frontend asset composition only.
- Deny: backup runtime templates (`*-bak.html`), vendor-path fallback (`/vendor/`), and backend code dependencies.
- Enforcement baseline: `npm run verify-template-assets` and `npm run verify-assets`.

## R7. No Cross-Layer Back-Edges

- Applies to all zones.
- Deny:
  - Z4 -> Z2/Z3/Z1
  - Z3 -> Z2/Z1
  - Z2 -> Z1
  - Any backend Java zone -> Z6 scripts/templates as logic dependency

## 4. Concrete In/Out Examples

Allowed:
- `UserViewController` -> `ScientificProductionService` (Z1 -> Z2/Z3 boundary via service path).
- `ScopusUpdateScheduler` -> `ScopusDataService` + repositories (Z2 -> Z2/Z4).
- `ComputerScienceScoringService` -> ranking/cache repository-backed data (Z3 -> Z4).

Denied for new code:
- Controller directly adding a new `*Repository` field and composing query/business logic.
- Scoring rule branches placed in controllers.
- Repository methods containing business branching logic unrelated to query concerns.

## 5. Compliance Gates for Review

PR checklist items (effective immediately):

1. Does this change add any new Z1 -> Z4 direct dependency?
2. Does any scoring/ranking rule change happen outside `service/reporting/**`?
3. Does any template violate asset contract (`/assets/app.css`, `/assets/app.js`, no `*-bak.html`)?
4. Does any external API call get introduced outside Z5/Z2?

If any answer is yes, the PR requires explicit architecture waiver notes and a remediation follow-up task.

## 6. Automation Hooks (H02-S06)

These rules are now backed by a lightweight guard script:

- `npm run verify-architecture-boundaries`
  - blocks new controller/view imports from `core.repository` (debt-aware allowlist baseline)
  - blocks direct transport imports from `core.service.reporting`
  - blocks `CacheService` references/imports in `service/reporting/**` (enforces V04 closure)
- existing template/frontend checks remain mandatory:
  - `npm run verify-assets`
  - `npm run verify-template-assets`
