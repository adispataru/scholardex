# H07-S02 Validation and Binding Drift Inventory
Status: active indexed source-of-truth document.

Date: 2026-03-04  
Scope: input validation, request binding safety, and parameter parsing resilience across MVC and REST endpoints.

## 1. Method

- Scanned controller/view layers for validation hooks:
  - `@Valid`, `@Validated`, `BindingResult`
- Scanned models for Bean Validation constraints:
  - `@NotNull`, `@NotBlank`, `@Size`, `@Email`, `@Pattern`, `@Min`, `@Max`
- Reviewed high-impact input paths:
  - `@RequestBody`, `@ModelAttribute`, `@RequestParam`, `MultipartFile`
- Reviewed existing tests for invalid input behavior characterization.

## 2. Findings (Severity-Ordered)

| ID | Severity | Finding | Evidence | Impact | Initial direction |
|---|---|---|---|---|---|
| `V-H07-01` | high | **No Bean Validation pipeline active in transport layer** | No `@Valid`, `@Validated`, or `BindingResult` usage found in controllers/views; no model-level validation annotations found in `core/model/**`. | Invalid payloads/forms can reach business/persistence layers unchecked, causing inconsistent behavior and silent bad data acceptance. | Introduce DTO-level validation contracts for critical endpoints first; add `@Valid` + structured error mapping. |
| `V-H07-02` | high | **Direct entity binding from untrusted input (`@ModelAttribute`/`@RequestBody`)** | Broad binding of persistence entities: `UserController#createUser(@RequestBody User)` (`src/main/java/ro/uvt/pokedex/core/controller/UserController.java:31`), `AdminResearcherController#addResearcher(@RequestBody Researcher)` (`src/main/java/ro/uvt/pokedex/core/controller/AdminResearcherController.java:23`), multiple admin/user form handlers with `@ModelAttribute` domain entities (`AdminViewController`, `AdminGroupController`, `UserViewController`). | Over-posting/mass-assignment risk and unclear field ownership boundaries; transport inputs can mutate fields not intended for direct client control. | Replace entity binding with explicit request DTOs and allowlist mapping in facades/services. |
| `V-H07-03` | high | **Unsafe numeric parsing on query parameters in export flows** | `Integer.parseInt(startYear/endYear)` in `UserViewController#createCNFISReport2025` (`src/main/java/ro/uvt/pokedex/core/view/UserViewController.java:362-363`) and `AdminGroupController#createCNFISReport2025` (`src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java:154-155`) without local validation/error mapping. | Malformed `start/end` input can raise unhandled runtime exceptions and return inconsistent error responses. | Add bounded numeric validation (`@RequestParam Integer` + range checks or safe parse helper) and consistent 4xx mapping. |
| `V-H07-04` | medium-high | **File upload validation is minimal (empty-only check)** | `AdminGroupController#importGroups` validates only `file.isEmpty()` before import (`src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java:203-219`). | Content-type/size/format abuse risk and weak feedback quality for malformed files. | Add multipart size/type checks and CSV schema validation before import processing. |
| `V-H07-05` | medium-high | **Validation/negative-path regression tests are missing** | No controller tests matching invalid input expectations (`400`, bad request, validation exception mapping) found under `src/test/java/ro/uvt/pokedex/core/view` and `.../controller`. | Validation regressions can be introduced without automated detection. | Add focused invalid-input characterization tests for top-risk endpoints before remediation. |
| `V-H07-06` | medium | **User role input not explicitly validated at boundary** | `AdminViewController#createUser` accepts `roles` directly from request params and delegates conversion (`src/main/java/ro/uvt/pokedex/core/view/AdminViewController.java:558-560`); `UserService` uses `UserRole.valueOf` directly (`src/main/java/ro/uvt/pokedex/core/service/UserService.java:70-72`). | Invalid role strings can trigger runtime exceptions and inconsistent user-facing outcomes. | Validate roles against enum allowlist at request boundary and return deterministic 4xx/flash error. |

## 3. Coverage and Gaps

- Current test baseline emphasizes happy-path contract behavior (H03/H04), not invalid input handling.
- No centralized validation contract currently enforces:
  - required fields,
  - type/range bounds,
  - binding allowlists,
  - standardized 4xx responses for malformed inputs.

## 4. Priority Next-Look List (for H07-S03/H07-S04/H07-S05)

1. `V-H07-01` define canonical validation policy and scope.
2. `V-H07-02` decide DTO boundaries for admin/user write endpoints.
3. `V-H07-03` lock numeric parsing/error mapping contract for export/report params.
4. `V-H07-05` add invalid-input regression guard tests.
5. `V-H07-04` define upload validation contract for group import.

## 5. Open Decisions Seeded

- Should validation be centralized via DTO + `@Valid` for all new/changed write endpoints, with entity binding treated as legacy debt?
- What is the canonical error response contract for invalid MVC form inputs vs REST JSON payloads?
- Which upload limits (size/type/schema) are mandatory for CSV import paths?

## 6. B06 Status Update (2026-03-04)

- `V-H07-01`: resolved in `B06`.
  - Added DTO + `@Valid` boundary validation for targeted admin APIs:
    - `/api/admin/users` (POST/PUT)
    - `/api/admin/researchers` (POST/PUT)
- `V-H07-02`: resolved in `B06` for targeted R2 scope.
  - Removed direct entity request binding in those APIs and introduced explicit mapping from request DTOs.
- `V-H07-03`: resolved in `B06`.
  - Replaced unsafe CNFIS year parsing in:
    - `UserViewController#createCNFISReport2025`
    - `AdminGroupController#createCNFISReport2025`
  - Enforced bounded parsing contract: `1900..currentYear` and `start <= end`; invalid input now returns deterministic `400`.
- `V-H07-06`: resolved in `B06`.
  - Added explicit role allowlist validation at `AdminViewController#createUser`.
  - Invalid role input now follows deterministic MVC contract (redirect + flash error), no exception escape.

## 7. B11 Status Update (2026-03-04)

- `V-H07-04`: resolved in `B11`.
  - `/admin/groups/import` now enforces strict upload validation at boundary (size + extension + content-type).
  - CSV parsing now applies schema and row-level validation (required columns/fields and email format), with deterministic reject behavior and no partial persistence on malformed input.
