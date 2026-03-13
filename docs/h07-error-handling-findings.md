# H07-S03 Error Handling Consistency Findings

Date: 2026-03-04  
Scope: exception mapping, transport-layer error behavior, and consistency of user/API outcomes across MVC and REST flows.

## 1. Method

- Reviewed global error/auth handlers:
  - `CustomErrorController`
  - `CustomAccessDeniedHandler`
  - `GlobalControllerAdvice`
- Reviewed representative MVC/REST controllers for:
  - explicit status/redirect behavior,
  - local try/catch usage,
  - exception propagation policy.
- Checked tests for explicit negative/error-path characterization.

## 2. Findings (Severity-Ordered)

| ID | Severity | Finding | Evidence | Impact | Initial direction |
|---|---|---|---|---|---|
| `E-H07-01` | high | **No centralized exception-to-response mapping contract** | No `@ExceptionHandler` methods found; `GlobalControllerAdvice` only injects current user model (`src/main/java/ro/uvt/pokedex/core/config/GlobalControllerAdvice.java`). | Error outcomes depend on local controller code or default framework behavior, creating drift between endpoints. | Add global exception mapping for MVC + REST with explicit status/view/payload policy. |
| `E-H07-02` | high | **Access denied flow uses redirect instead of explicit 403 contract** | `CustomAccessDeniedHandler` redirects to `/custom-error?error=403` (`src/main/java/ro/uvt/pokedex/core/handlers/CustomAccessDeniedHandler.java:10-13`). | APIs and non-browser clients may receive HTML redirect semantics where status-based 403 handling is expected. | Split denied handling by content type/zone (API returns 403 payload, MVC returns error view). |
| `E-H07-03` | high | **Streaming/export endpoints swallow exceptions** | `ExportController` catches `Exception` and prints stack trace without setting failure status (`src/main/java/ro/uvt/pokedex/core/controller/ExportController.java:52-54`). | Clients can receive partial/corrupt output with 200 semantics and no structured failure signal. | Replace catch-and-print with propagated domain exception and mapped 5xx/4xx response. |
| `E-H07-04` | high | **Mixed unauthorized behavior for similar auth failures** | In `UserViewController`, unauthenticated requests are handled by both redirects (`return "redirect:/login"`) and status responses (`SC_UNAUTHORIZED`) depending on endpoint (`src/main/java/ro/uvt/pokedex/core/view/UserViewController.java:91,129,359`). | Same auth failure yields inconsistent client behavior across routes, complicating UI/API integration and tests. | Define per-zone unauthorized contract (MVC redirect vs API 401 JSON) and enforce uniformly. |
| `E-H07-05` | medium-high | **Input parsing/runtime exceptions can escape as 500** | `Integer.parseInt(startYear/endYear)` in export endpoints without guard (`UserViewController:362-363`, `AdminGroupController:154-155`). | Malformed query params produce runtime exceptions and likely generic 500 path instead of deterministic 4xx validation response. | Add safe parsing/validation with explicit 400 mapping. |
| `E-H07-06` | medium-high | **Error paths leak internals through stacktrace printing** | Controller catches print stack trace directly (`AdminGroupController#importGroups` and `ExportController`) (`src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java:212-214`, `src/main/java/ro/uvt/pokedex/core/controller/ExportController.java:53`). | Operational noise and potential information leakage; inconsistent observability quality. | Replace `printStackTrace` with structured logging and user-safe error messaging. |
| `E-H07-07` | medium-high | **Service-layer null/exception behavior can cause unhandled controller errors** | `UserService.updateUser(...).orElseGet(null)` (`src/main/java/ro/uvt/pokedex/core/service/UserService.java:41`) is exception-prone when user is missing. | Missing-resource updates may fail with server errors instead of clean 404/validation feedback. | Return `Optional`/explicit not-found path and map consistently in controller layer. |
| `E-H07-08` | medium | **Negative-path regression coverage is largely absent** | No tests found characterizing `403/401/400/500` controller behavior in `src/test/java/**` for current transport layer. | Error-handling regressions are likely to pass unnoticed. | Add focused characterization tests for top auth/validation/error paths before remediation. |

## 3. Current Error Boundary Summary

- `/error` route maps selected status codes to templates (`error-404`, `error-500`, `error-403`) via `CustomErrorController`.
- Access-denied is currently redirect-driven (`/custom-error` route), not status/payload policy-driven.
- Most endpoints rely on inline controller checks rather than one global error policy.

## 4. Priority Next-Look List (for H07-S04/H07-S05)

1. `E-H07-01` define canonical global exception mapping contract.
2. `E-H07-02` + `E-H07-04` unify auth failure and access-denied behavior by endpoint zone.
3. `E-H07-03` + `E-H07-06` harden export/streaming exception handling and logging.
4. `E-H07-05` validate numeric query parameters with deterministic 400 mapping.
5. `E-H07-08` add regression tests for negative/error paths.

## 5. Open Decisions Seeded

- Should API endpoints return structured JSON error envelopes while MVC keeps template-based error views?
- Which exceptions become first-class mapped cases in H07 (validation, access denied, resource not found, illegal argument)?
- What is the standard unauthorized behavior contract for mixed MVC + `@ResponseBody` controllers?

## 6. H07-S05 Guard Coverage (Characterization Baseline)

The following regression guards were added to lock current behavior before remediation:

- `UserViewControllerContractTest`
  - unauthenticated `/user/publications` redirects to login,
  - unauthenticated `/user/exports/cnfis` returns `401`,
  - invalid `start` year on CNFIS export currently throws `ServletException` (no explicit `400/500` mapping contract in place),
  - missing indicator workbook on export returns `404`.
- `AdminGroupControllerContractTest`
  - invalid `start` year on group CNFIS export currently throws `ServletException`,
  - empty import file redirects with error flash message,
  - `GET /admin/groups/delete/{id}` redirects successfully (mutating-GET debt baseline).
- `AdminViewControllerContractTest`
  - invalid role input on user create path currently throws `ServletException`.
- `ExportControllerContractTest`
  - facade exception during streaming currently preserves started response contract (`200` + headers).
- `CustomErrorControllerTest`
  - `/error` status mapping to `403/404/500/generic` templates.
- `CustomAccessDeniedHandlerTest`
  - access denied redirects to `/custom-error?error=403`,
  - no API-specific JSON payload contract yet.

These tests intentionally characterize current behavior, including known flaws, and are expected to be updated in `H07-S06` remediation slices.

## 7. R1 Status Update (2026-03-04)

- `E-H07-02` resolved in `B02/H07-R1`:
  - access denied behavior is now zone-aware (`/api/**` returns JSON `403`; MVC continues 403 view flow via `/custom-error?error=403`).
- `E-H07-04` resolved in `B02/H07-R1`:
  - `/user/**` unauthenticated behavior is aligned to MVC redirect-to-login contract.
  - API unauthenticated behavior is aligned to JSON `401` envelope contract.

## 8. R3 Status Update (2026-03-04)

- `E-H07-01` resolved in `B07/H07-R3`:
  - centralized split exception mapping is active:
    - API zone via `ApiExceptionHandler` (`@RestControllerAdvice`)
    - MVC zone via `MvcExceptionHandler` (`@ControllerAdvice` for `core.view`).
- `E-H07-03` and `E-H07-06` resolved in `B07/H07-R3`:
  - `/api/export` no longer uses swallow-catch behavior; failures propagate to deterministic server error mapping.
  - transport-layer stacktrace printing remains forbidden and guarded.
- `E-H07-07` resolved in `B07/H07-R3`:
  - `UserService.updateUser(...)` uses `Optional<User>` contract;
  - missing-user update path maps to deterministic `404` in controller.
- `E-H07-05` mapping consistency resolved in `B07/H07-R3`:
  - parse/argument exceptions in API endpoints are now mapped centrally to `400` JSON envelope.
