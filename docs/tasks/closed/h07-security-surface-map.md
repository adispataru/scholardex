# H07-S01 Security Surface Map and Trust Boundaries
Status: active indexed source-of-truth document.

Date: 2026-03-04  
Scope: authentication/authorization entrypoints, request input surfaces, and trust boundaries across MVC + REST transport layers.

## 1. Method

- Reviewed Spring Security configuration:
  - `src/main/java/ro/uvt/pokedex/core/config/WebSecurityConfig.java`
- Enumerated controller route surfaces under:
  - `src/main/java/ro/uvt/pokedex/core/view/**`
  - `src/main/java/ro/uvt/pokedex/core/controller/**`
- Mapped HTML form and query-string entrypoints from templates:
  - `src/main/resources/templates/**`

## 2. Security Configuration Baseline

## 2.1 Global rules currently enforced

- `CSRF` is globally disabled.
- Explicit authority rules:
  - `/admin/**` -> `PLATFORM_ADMIN`
  - `/api/admin/**` -> `PLATFORM_ADMIN`
  - `/api/export` and `/api/export/**` -> `PLATFORM_ADMIN`
  - `/api/scrape` and `/api/scrape/**` -> `PLATFORM_ADMIN`
  - `/researcher/**` -> `RESEARCHER`
  - `/api/supervisor/**` -> `SUPERVISOR`
- Fallback rule:
  - `anyRequest().authenticated()`
- Transport auth mode configured:
  - form login enabled for MVC login redirect contract
  - HTTP Basic disabled
- Access denied handling:
  - matcher-aware handlers wired:
    - `/api/**` -> JSON `403` payload
    - MVC routes -> redirect `/custom-error?error=403`
- Unauthenticated handling:
  - `/api/**` -> JSON `401` payload
  - MVC routes -> redirect `/login`

## 2.2 Effective implication for trust boundaries

- Any authenticated principal can access non-matched paths (for example many `/admin/**` MVC endpoints) unless those routes enforce checks manually.
- No method-level security annotations are currently present (`@PreAuthorize`, `@Secured`, `@RolesAllowed` not found).

## 3. Route Surface Inventory (By Zone)

## Z1-MVC Admin zone (`/admin/**`)

Primary controllers:
- `AdminViewController`
- `AdminGroupController`
- `AdminActivityController`
- `AdminIndividualReportsController`
- `AdminGroupReportsController`
- `AdminURAPController`

Capabilities include:
- user/role management,
- indicator/domain/report CRUD,
- group management and import,
- ranking compute/merge actions,
- institution/group report export endpoints.

High-impact mutating operations currently exposed via `GET` in multiple places:
- `.../delete/{id}` and `.../duplicate/{id}` patterns in admin flows.

## Z1-MVC User zone (`/user/**`)

Primary controllers:
- `UserViewController`
- `view/user/ActivityInstanceController`
- `view/user/PublicationWizardController`

Capabilities include:
- profile updates,
- publication/task submission,
- report/indicator views and exports,
- activity instance CRUD.

Auth handling pattern:
- frequent inline runtime checks:
  - `authentication != null && principal instanceof User`.

## Z1-REST/API zone (`/api/**`)

Primary controllers:
- `UserController` (`/api/admin/users`)
- `AdminResearcherController` (`/api/admin/researchers`)
- `WebScrapingController` (`/api/scrape`)
- `ExportController` (`/api/export`)

Trust boundary characteristics:
- JSON body ingestion on admin REST routes.
- scrape/export endpoints are authenticated via fallback rule unless explicitly matched by stricter authority patterns.

## 4. Input Surface Map

## 4.1 Request-body and form binding

- REST JSON inputs:
  - `@RequestBody User`, `@RequestBody Researcher`
- MVC form/model bindings:
  - broad `@ModelAttribute` usage for domain entities (group, institution, indicator, report, publication, activity, activity indicator, etc.).

## 4.2 Query/path parameters

- High-volume query/path driven reads and exports:
  - publication IDs/eids,
  - report/indicator IDs,
  - export date ranges (`start`, `end`),
  - search input (`paperTitle` and related params).

## 4.3 File upload boundary

- `AdminGroupController` import path:
  - `POST /admin/groups/import` receives `MultipartFile`.

## 4.4 Template-posted state changes

- Many admin/user forms post state changes from Thymeleaf templates.
- CSRF token meta usage exists in some JS flows, but global CSRF enforcement is currently disabled.

## 5. Trust Boundary Diagram (Logical)

1. Browser/Client -> Controller endpoint (`/admin`, `/user`, `/api`).
2. Controller -> Application facade/service boundary.
3. Service/facade -> repository + external integrations (scraping/import/export flows).
4. Error boundary -> custom access denied handler + custom error controller.

The highest-risk boundary is step 1 for admin MVC routes due to broad `authenticated` fallback and mixed route-level role assumptions.

## 6. Initial Risk Snapshot for H07-S02/S03

1. Authorization surface drift risk (high):
- `/admin/**` MVC routes are not explicitly authority-scoped in `WebSecurityConfig`; they rely on fallback `authenticated`.

2. CSRF exposure risk (high):
- CSRF globally disabled while many state-changing POST forms exist.

3. Unsafe HTTP verb semantics (high):
- several destructive/duplication actions use `GET`, increasing accidental or forged trigger risk.

4. Validation coverage drift risk (medium-high):
- broad `@ModelAttribute`/`@RequestBody` usage with no visible `@Valid`/`BindingResult` coverage baseline.

5. Inconsistent authz enforcement style (medium-high):
- mixed centralized rule enforcement and per-controller principal checks.

## 7. H07-S01 Exit Status

- Endpoint families, auth gates, and trust boundaries are mapped.
- Critical surfaces for follow-up are identified and ranked.
- This artifact is ready to seed:
  - `H07-S02` validation drift inventory
  - `H07-S03` exception/error handling consistency inventory.
