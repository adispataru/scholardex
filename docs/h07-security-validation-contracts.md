# H07-S04 Security, Validation, and Error Handling Contracts

Date: 2026-03-04  
Status: active baseline for H07 remediation and future transport/security changes.

## 1. Purpose

Define explicit, repository-wide contracts for:
- authentication/authorization behavior,
- request validation and binding safety,
- error/exception mapping semantics.

These contracts lock policy decisions derived from:
- `docs/h07-security-surface-map.md`
- `docs/h07-validation-drift-inventory.md`
- `docs/h07-error-handling-findings.md`

## 2. Normative Contracts

## C1. Authorization Scope Contract

- Administrative MVC and API routes must be explicitly authority-scoped.
- `anyRequest().authenticated()` may remain as fallback, but must not be the only guard for `/admin/**` or `/api/admin/**` surfaces.
- New privileged endpoints require explicit matcher or method-level authorization policy in PR notes.

## C2. Unauthorized and Access-Denied Contract

- Zone policy:
  - MVC pages: unauthenticated -> redirect to login.
  - API/`@ResponseBody` endpoints: unauthenticated -> `401` JSON error envelope.
- Access denied (`authenticated` but insufficient authority):
  - MVC: `403` error view.
  - API: `403` structured error payload (no redirect).
- Baseline path zoning:
  - privileged MVC: `/admin/**`
  - privileged API: `/api/admin/**`, `/api/export/**`, `/api/scrape/**`
  - user MVC zone: `/user/**` (redirect-to-login on unauthenticated access)

## C3. CSRF Contract

- State-changing browser form endpoints must be CSRF-protected.
- Disabling CSRF globally is not an acceptable end-state for MVC form flows.
- If a route is intentionally CSRF-exempt, exemption must be explicit and documented with rationale.

## C4. HTTP Verb Safety Contract

- Destructive/mutating operations must not be exposed as `GET`.
- `delete/duplicate/compute/import/update/create` actions must use `POST|PUT|PATCH|DELETE` according to intent.
- Existing `GET` mutation routes are legacy debt and must be migrated in remediation slices.

## C5. Validation Entry Contract

- New/changed write endpoints must validate request payloads at transport boundary.
- Default policy:
  - request DTO + Bean Validation annotations,
  - `@Valid` at controller boundary,
  - deterministic invalid-input response.
- Direct entity binding from request (`@ModelAttribute`/`@RequestBody` entity types) is legacy debt.

## C6. Parameter Parsing Contract

- User-controlled numeric/date parameters must use safe parsing and bounded validation.
- Invalid parse/range input must map to deterministic `400` behavior (not `500`).
- Ad-hoc `Integer.parseInt(...)` without guard in controllers is forbidden for new code.

## C7. File Upload Validation Contract

- Upload endpoints must enforce:
  - non-empty file check,
  - allowed content type/extension policy,
  - size limit policy,
  - parse/schema validation before persistence effects.
- Failures return user-safe errors without stack traces.

## C8. Exception Mapping Contract

- A global exception mapping policy must exist for high-frequency failure classes:
  - validation failure,
  - resource not found,
  - illegal argument/bad request,
  - access denied,
  - unexpected server error.
- Local controller try/catch should only handle endpoint-specific recovery; generic mapping belongs in centralized advice.

## C9. Error Payload/View Contract

- API errors should return a consistent JSON envelope:
  - `timestamp`, `status`, `error`, `path` (minimum fields for security `401/403` handlers).
- MVC errors should resolve to standard error templates by status (`403/404/500`) with safe, non-sensitive messages.

## C10. Logging and Disclosure Contract

- `printStackTrace()` is forbidden in controller/service runtime code.
- Use structured logging with sanitized messages.
- Error responses must avoid leaking stack traces, internals, or sensitive identifiers.

## C11. Login Form Browser Compatibility Contract

- Login template must preserve Spring Security form-login field names:
  - username input: `name="username"`
  - password input: `name="password"`
- Login template must include browser/password-manager autocomplete hints:
  - username/email: `autocomplete="username"`
  - password: `autocomplete="current-password"`
- Login processing endpoint contract remains `/login` POST with failure redirect to `/login?error`.
- Remember-me is deferred in current baseline and intentionally not required by this contract.

## 3. Applied Decisions for Current H07 Findings

- `S-H07-01` (`/admin/**` scope drift): explicit authz scoping is required (`C1`).
- `V-H07-01` / `V-H07-02`: DTO + `@Valid` transport validation contract adopted (`C5`).
- `V-H07-03` + `E-H07-05`: numeric parse failures become `400` by policy (`C6`).
- `V-H07-04`: upload validation hardening required (`C7`).
- `E-H07-01` / `E-H07-02` / `E-H07-04`: unified unauthorized/forbidden semantics required (`C2`, `C8`, `C9`).
- `E-H07-03` / `E-H07-06`: no swallowed exceptions or stacktrace printing (`C8`, `C10`).

## 4. Review Checklist (H07 Changes)

1. Is the endpoint authority-scoped explicitly if privileged?
2. Does auth failure behavior match zone contract (MVC redirect vs API status)?
3. Are mutating actions using non-GET verbs?
4. Is input validated at boundary (`DTO + @Valid`)?
5. Are numeric/date params parsed safely with deterministic `400` on invalid input?
6. Does file upload enforce type/size/schema checks?
7. Are exceptions mapped centrally instead of ad-hoc catch-and-print?
8. Are logs structured and response messages non-sensitive?

## 5. Out-of-Scope for S04

- No runtime behavior changes in this slice.
- No security matcher or CSRF config modifications in this slice.
- No test additions in this slice (covered in `H07-S05`).

## 6. B06 Status Notes (2026-03-04)

- `C5` validation entry contract: adopted for targeted R2 admin APIs.
  - `/api/admin/users` and `/api/admin/researchers` POST/PUT now use request DTOs with `@Valid`.
- `C6` parameter parsing contract: adopted for CNFIS export year params.
  - `start/end` now validate integer format, bounds (`1900..currentYear`), and `start <= end`.
  - Invalid year input returns deterministic `400` on both user and admin-group CNFIS export paths.
- Mixed invalid-input contract decision retained:
  - API/query parse failures -> `400`.
  - MVC invalid role on `/admin/users/create` -> redirect `/admin/users` + flash error.

## 7. B07 Status Notes (2026-03-04)

- `C8` exception mapping contract: adopted.
  - API endpoints now use centralized `@RestControllerAdvice` mappings for validation/bad-request/not-found/unexpected errors.
  - MVC endpoints in `core.view` now use centralized `@ControllerAdvice` mappings for deterministic `400/500` view outcomes.
- `C9` error payload/view contract: adopted in targeted R3 scope.
  - API JSON envelope fields remain standardized: `timestamp`, `status`, `error`, `path`, `message`.
  - MVC exception paths now resolve to deterministic templates (`errors/error`, `errors/error-500`) for mapped cases.
- `C10` logging and disclosure contract: reinforced.
  - transport stacktrace printing remains forbidden;
  - `/api/export` no longer swallows endpoint exceptions, and failures are surfaced through centralized error handling.

## 8. Login Baseline Status Notes (2026-03-04)

- Practical login standards alignment adopted:
  - login form semantics now include browser/password-manager-compatible metadata (`email` input + autocomplete hints).
  - form-login/logout behavior is explicitly configured in `WebSecurityConfig` (`/login` processing, `/login?error` failure, `/login?logout` logout success).
- Scope intentionally excludes remember-me and CSRF policy changes in this slice.

## 9. R4 Status Notes (2026-03-04)

- `C3` CSRF contract: adopted for MVC browser flows.
  - global CSRF disable removed from security config.
  - explicit CSRF exemption is limited to `/api/**`.
- `C4` HTTP verb safety contract: adopted.
  - mutating `delete/duplicate` transport routes were migrated from `GET` to `POST`.
  - UI actions now submit POST forms for those operations.
- `C7` upload validation contract: adopted for group CSV import.
  - `/admin/groups/import` now validates file size, extension/content-type, and CSV schema/row requirements before persistence effects.

## 10. H11 Contract Hardening Notes (2026-03-04)

- Duplicate-user API semantics were tightened:
  - `POST /api/admin/users` now returns `409 Conflict` when user already exists (instead of ambiguous success with null body risk).
- Null-safety contracts were hardened in targeted core paths:
  - `UserService#createUser(...)` overloads now return `Optional<User>`.
  - `CacheService#getCachedTopRankings(...)` now returns deterministic `0` on cache miss.
  - `GlobalControllerAdvice#currentUser` now exposes `Optional<User>` (`currentUser`) and no longer returns nullable contract state.
  - `PublicationWizardFacade#resolveForumId(...)` now returns `Optional<String>`.
- MVC wizard failure behavior was normalized:
  - invalid step-1 forum selection in publication wizard now redirects back with flash error instead of propagating null forum ID.
