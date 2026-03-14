# H07-S06 Remediation Plan

Date: 2026-03-04  
Status: active phased plan for error-handling, validation, and security hardening.

## 1. Objective

Sequence H07 hardening by blast radius while preserving current behavior where needed and preventing reintroduction of known risks through lightweight guardrails.

## 2. Risk-to-Slice Mapping

| Slice | Target risks | Scope | Expected impact |
|---|---|---|---|
| `R1` | `S-H07-01`, `E-H07-02`, `E-H07-04` | Authorization scope and unauthorized/forbidden contract alignment. | Remove privilege-scope ambiguity and make auth failures deterministic by endpoint zone. |
| `R2` | `V-H07-01`, `V-H07-02`, `V-H07-03`, `V-H07-06` | Boundary validation rollout (DTO + `@Valid`) and safe parameter parsing. | Prevent malformed input from reaching business/persistence layers; replace exception escapes with 4xx behavior. |
| `R3` | `E-H07-01`, `E-H07-03`, `E-H07-05`, `E-H07-06`, `E-H07-07` | Centralized exception mapping and transport logging cleanup. | Normalize API/MVC error outcomes and eliminate swallowed exceptions/internal leakage. |
| `R4` | `C3`, `C4`, `V-H07-04` | CSRF re-enablement policy, mutating-GET migration, and upload validation hardening. | Close browser-flow security debt and enforce safer transport semantics. |

## 3. Decision-Complete Slice Details

## R1: Authorization and Access Semantics (`P0`)

- Explicitly scope `/admin/**` and relevant privileged MVC routes in security config.
- Keep zone contract:
  - MVC unauthenticated -> login redirect.
  - API unauthenticated -> `401` payload/status.
  - access denied -> MVC `403` view / API `403` JSON.
- Ensure no privileged route depends solely on `anyRequest().authenticated()`.

Exit criteria:
- privileged routes are explicitly scoped;
- unauthorized/forbidden behavior is consistent by zone.

Status update (2026-03-04):
- `R1` implemented via `B02`.
- Explicit privileged scoping now includes `/admin/**`, `/api/admin/**`, `/api/export/**`, `/api/scrape/**`.
- Zone contract now enforced by security handlers:
  - MVC unauthenticated -> redirect `/login`
  - API unauthenticated -> JSON `401`
  - MVC forbidden -> 403 view flow (`/custom-error?error=403`)
  - API forbidden -> JSON `403`

## R2: Validation Boundary Hardening (`P0/P1`)

- Introduce DTO-bound request contracts for selected write endpoints first (admin user/researcher/group imports + CNFIS export params).
- Add `@Valid` + deterministic invalid-input mapping.
- Replace unsafe `Integer.parseInt(startYear/endYear)` transport parsing with validated conversion and bounded ranges.
- Add explicit role allowlist validation before enum conversion.

Exit criteria:
- top-risk write/input endpoints validate at boundary;
- malformed input returns deterministic 4xx behavior.

Status update (2026-03-04):
- `R2` implemented via `B06`.
- Targeted DTO + `@Valid` rollout completed for:
  - `/api/admin/users` (POST/PUT)
  - `/api/admin/researchers` (POST/PUT)
- Unsafe CNFIS year parsing was replaced with bounded validation (`1900..currentYear`, `start <= end`) in:
  - `UserViewController#createCNFISReport2025`
  - `AdminGroupController#createCNFISReport2025`
- Invalid role handling in `AdminViewController#createUser` now enforces allowlist validation with deterministic MVC recovery (redirect + flash), preventing exception-driven 5xx.
- `verify-h07-guardrails` now enforces:
  - no transport `Integer.parseInt(startYear/endYear)` regressions,
  - `@Valid` presence on targeted admin API `@RequestBody` endpoints.

## R3: Exception Mapping and Logging (`P1`)

- Add centralized exception handling (`@ControllerAdvice`) for:
  - validation errors,
  - illegal argument/bad request,
  - not found,
  - generic unexpected errors.
- Stop catch-and-print transport patterns:
  - replace `printStackTrace` with structured logging;
  - avoid swallowing export streaming failures without mapped status.
- Normalize service missing-resource behavior (`Optional`/explicit null-safe contract) for affected paths.

Exit criteria:
- consistent error envelopes/views;
- no `printStackTrace` in transport layer;
- no silent exception swallowing in targeted endpoints.

Status update (2026-03-04):
- `R3` implemented via `B07`.
- Centralized split mapping is active:
  - API: `ApiExceptionHandler` -> deterministic JSON envelopes for `400/404/500`.
  - MVC: `MvcExceptionHandler` -> deterministic mapped views for `400/500`.
- `/api/export` now has explicit failure semantics (deterministic server error mapping) instead of silent/partial success behavior.
- `UserService.updateUser(...)` contract now returns `Optional<User>`, and `UserController` maps missing-resource updates to `404`.
- H07 guardrails were tightened for:
  - zero transport `printStackTrace`,
  - no generic catch in `ExportController` endpoint logic.

## R4: CSRF, Verb Safety, and Upload Hardening (`P1/P2`)

- Re-enable CSRF for browser state-changing flows, with explicit exemptions only where justified.
- Migrate mutating `GET` routes (`delete/duplicate`) to non-GET verbs with template/form updates.
- Add multipart upload constraints (size/type/schema) for group CSV import.

Exit criteria:
- mutating-GET debt removed from targeted routes;
- CSRF contract is active for browser forms;
- upload validation policy is enforced.

## 4. Lightweight Enforcement (S06)

### 4.1 Automated command

- New command: `npm run verify-h07-guardrails`
- Enforcement scope (debt-aware):
  - blocks any mutating `GET` routes (`delete/duplicate`) in transport layer,
  - blocks new `printStackTrace` usage in transport layer outside current allowlist,
  - blocks new unsafe `Integer.parseInt(startYear/endYear)` transport parsing outside current allowlist.

### 4.2 Contributor workflow

For H07-related changes:
1. Run `npm run verify-h07-guardrails`.
2. Run targeted H07 regression tests (contract/error boundary tests).
3. For policy-changing slices, document expected behavior changes in H07 docs.

## 5. Recommended Execution Order

1. `R1` (authorization scope + unauthorized/forbidden consistency)  
2. `R2` (validation boundaries + safe parsing)  
3. `R3` (exception mapping + logging cleanup)  
4. `R4` (CSRF, verb migration, upload hardening)

## 6. H07-S06 Completion Notes

- H07 remediation is now decision-complete (`R1..R4`).
- Debt-aware guardrails are executable locally via `verify-h07-guardrails`.
- Remaining H07 work moves to `H07-S07` closeout/adoption and H08 handoff.

## 7. H07-S07 Closeout and H08 Handoff

Status date: 2026-03-04

- H07 baseline is complete for planning and implementation:
  - security surface/trust boundaries: `docs/tasks/closed/h07-security-surface-map.md`
  - validation drift inventory: `docs/tasks/closed/h07-validation-drift-inventory.md`
  - error-handling findings: `docs/tasks/closed/h07-error-handling-findings.md`
  - canonical contracts: `docs/tasks/closed/h07-security-validation-contracts.md`
  - phased remediation and enforcement: `docs/tasks/closed/h07-remediation-plan.md`
- Active local H07 guard commands:
  - `npm run verify-h07-guardrails`
  - targeted H07 characterization tests from `H07-S05`
- H08 handoff constraints:
  - keep H07 contracts (`C1..C10`) as fixed policy inputs for observability/runbook design,
  - preserve debt-aware H07 guardrails until remediation slices (`R1..R4`) are implemented,
  - any H08 proposal that changes auth/validation/error behavior must explicitly reference impacted H07 contracts.

## 8. Login Practical Baseline Alignment (2026-03-04)

- Added explicit Spring form-login contract configuration:
  - login page `/login`
  - processing URL `/login`
  - failure redirect `/login?error`
  - logout success redirect `/login?logout`
- Added login form browser/password-manager compatibility metadata in template:
  - `autocomplete="username"` and `autocomplete="current-password"`
  - username input set to email semantics for autofill reliability.
- Added regression coverage for:
  - login view access,
  - form metadata contract,
  - valid/invalid login processing redirects,
  - logout redirect contract.
- Scope remains practical baseline only:
  - remember-me deferred,
  - CSRF policy unchanged (still tracked under `R4`).

## 9. R4 Status Update (2026-03-04)

- `R4` implemented via `B11`.
- CSRF policy:
  - CSRF is enabled for MVC browser flows.
  - `/api/**` is explicitly exempted to preserve API client behavior.
- Verb safety:
  - all previously allowlisted mutating `GET` routes (`delete/duplicate`) were migrated to `POST`.
  - corresponding template actions were migrated from link-based GET to form POST submissions.
- Upload hardening:
  - `/admin/groups/import` now enforces size, extension, content-type, and strict CSV schema/row validation.
  - invalid inputs return deterministic redirect + flash error behavior.
