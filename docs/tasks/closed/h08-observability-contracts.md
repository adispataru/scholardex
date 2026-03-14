# H08-S04 Observability and Operability Contracts

Date: 2026-03-04  
Status: active baseline for H08 remediation and future runtime diagnostics changes.

## 1. Purpose

Define explicit repository-wide contracts for:
- structured logging and diagnostics context,
- metrics naming and minimum instrumentation coverage,
- health/readiness/liveness and operability signaling.

These contracts formalize decisions from:
- `docs/tasks/closed/h08-observability-map.md`
- `docs/tasks/closed/h08-logging-drift-inventory.md`
- `docs/tasks/closed/h08-operability-findings.md`
- `docs/tasks/closed/h07-security-validation-contracts.md` (error/logging compatibility input)

## 2. Normative Contracts

## C1. Logging API Contract

- Runtime code must use SLF4J logger APIs (`logger`/`log`) for diagnostics.
- `printStackTrace()` and `System.out/System.err` are forbidden in non-test runtime paths.
- Log messages must be parameterized (`{}`) rather than string concatenation for dynamic values.

## C2. Log Level Contract

- `ERROR`: operation failed and user/system outcome is degraded.
- `WARN`: degraded/invalid-but-handled conditions that do not abort full operation.
- `INFO`: major lifecycle transitions (startup phase completion, scheduler batch start/end, import completion).
- `DEBUG`: high-volume diagnostics useful during investigation only.

## C3. Correlation Context Contract

Every high-impact operation log line should carry stable context IDs:
- HTTP flows: `requestId`, authenticated user identifier (email or user id), route.
- Scheduler flows: `jobType`, `taskId`, phase (`start|progress|complete|failed`).
- Startup/bootstrap flows: `startupPhase` and source component.

Context propagation policy:
- correlation context must survive service/facade boundaries for the same operation.
- when a context ID is unavailable, log an explicit placeholder (`unknown`) rather than omitting field semantics.

## C4. Sensitive Data Logging Contract

- Never log secrets/tokens/raw credentials or full third-party auth payloads.
- For external API responses, log sanitized metadata only (status, source, identifiers safe for diagnostics).
- User-facing errors and logs must not expose stack traces in response payloads/views.

## C5. Error Diagnostics Contract (H07-compatible)

- Runtime exception paths must emit one structured error log with operation context and exception object.
- Transport-level error mapping semantics remain governed by H07 contracts (`C8-C10`) and must be observability-compatible:
  - deterministic status/view mapping,
  - safe error disclosure,
  - consistent diagnostics emission.

## C6. Metrics Naming Contract

When metrics are introduced, use a stable namespace and tags:
- metric name prefix: `core.`
- naming style: `core.<area>.<operation>.<signal>` (dot-separated lower-case)
- required common tags where applicable: `component`, `operation`, `outcome`.

Examples:
- `core.scheduler.scopus.poll.duration`
- `core.scheduler.scopus.tasks.processed`
- `core.export.forum.requests`
- `core.startup.bootstrap.duration`

## C7. Minimum Metrics Coverage Contract

Critical operational hotspots must have at least one metric each:
1. startup/bootstrap path (`AdminUserBootstrapRunner` + startup phases),
2. scheduler queue processing (`ScopusUpdateScheduler`),
3. export streaming endpoints,
4. external integration calls (scopus python service path).

Minimum signals per hotspot:
- one latency/duration metric,
- one success/failure count metric.

## C8. Health/Readiness/Liveness Contract

- Application must expose machine-readable health endpoints via actuator baseline.
- Health semantics:
  - `liveness`: process can continue serving.
  - `readiness`: app is ready to handle traffic (dependencies and essential startup phases complete).
- Dependency-aware readiness must include critical external integrations (scopus python service) with bounded timeout behavior.

## C9. Startup Operability Contract

- Startup must be phase-explicit in diagnostics:
  - `bootstrap.begin`, `bootstrap.phase.<name>.complete`, `bootstrap.complete`.
- Startup critical vs optional tasks must be distinguishable in logs and readiness outcomes.
- Long-running bootstrap tasks should emit progress markers at bounded intervals.

## C10. Async/Scheduler Capacity Contract

- Async executor configuration must be externally visible and diagnosable.
- Queue saturation/rejection/latency conditions require warning-level diagnostics with operation context.
- Scheduler processing loops must log batch summary at completion:
  - tasks processed,
  - failures,
  - duration.

## C11. Operability Guardrail Contract

- Repository guardrails must prevent reintroduction of known diagnostics debt:
  - no new runtime `printStackTrace` / `System.out` in production paths,
  - no regression of explicit high-impact diagnostics coverage.
- Guardrails can begin as characterization checks and be hardened in H08-S06.

## 3. Applied Decisions for Current H08 Findings

- `L-H08-01` + `L-H08-04` + `L-H08-08`: enforce logging API and sensitive-data contracts (`C1`, `C4`).
- `L-H08-02`: introduce correlation context policy (`C3`).
- `L-H08-03`: centralize logging behavior policy (`C2`, `C11`).
- `O-H08-01` + `O-H08-05`: readiness and dependency-health contract (`C8`).
- `O-H08-02` + `O-H08-03` + `O-H08-04`: startup/scheduler/async operability contracts (`C9`, `C10`).
- H07 compatibility is preserved via `C5`.

## 4. Review Checklist (H08 Changes)

1. Are runtime diagnostics emitted via logger APIs only (no stdout/stacktrace printing)?
2. Do logs include operation context IDs appropriate to flow type (HTTP/scheduler/startup)?
3. Are log levels aligned with contract semantics (`ERROR/WARN/INFO/DEBUG`)?
4. Are sensitive payloads/tokens excluded from logs?
5. For touched hotspot flows, are latency + outcome metrics present or explicitly deferred with tracking?
6. Do readiness/health semantics remain explicit for critical dependencies?
7. Do scheduler/async paths expose capacity or batch-summary diagnostics?
8. Do changes maintain H07 error-mapping compatibility?

## 5. Out-of-Scope for S04

- No runtime behavior/code changes in this slice.
- No actuator/metrics implementation in this slice.
- No new automated guardrail scripts in this slice (planned under `H08-S05`/`H08-S06`).

## 6. H08-S05 Status Update

- `H08-S05` delivered initial observability guardrails:
  - `npm run verify-h08-observability-guardrails`
  - `scripts/verify-h08-observability-guardrails.js`
- Implemented checks are characterization-first and debt-aware:
  - block new runtime `printStackTrace` usage,
  - block new runtime `System.out/System.err` usage,
  - preserve scheduler diagnostics floor markers in `ScopusUpdateScheduler`.

## 7. B03 Adoption Update (2026-03-04)

- `C1` and `C4` are now applied on the `H08-P0` target set (`B03`):
  - runtime `printStackTrace` and active `System.out/System.err` usage in targeted classes replaced by logger-based diagnostics;
  - sensitive raw payload console output removed from `ScopusService#parseToken`;
  - guardrail allowlists tightened after cleanup.

## 8. B08 Adoption Update (2026-03-04)

- `C3` correlation context is now partially implemented in `H08-P1` (`B08`):
  - HTTP request correlation uses `X-Request-Id` adopt-and-propagate policy with response header echo/generation.
  - Request-scoped MDC keys are set for `requestId`, `route`, and `userId`.
  - Scopus scheduler flow now emits stable scheduler MDC context (`jobType`, `taskId`, `phase`) for batch and per-task execution.
- `C5` error diagnostics compatibility was aligned:
  - centralized API/MVC exception handlers include correlation-aware log context (`requestId`) while preserving H07 response mappings.

## 9. B12 Adoption Update (2026-03-04)

- `C6..C10` are now operationalized for the H08 baseline:
  - actuator health/readiness/liveness baseline is active with probe-only public exposure and restricted non-probe actuator endpoints,
  - startup readiness semantics are phase-explicit with critical-vs-optional status tracking (`startup` health contributor),
  - external dependency readiness coverage is active for Scopus Python service with bounded timeout checks (`scopusPython`),
  - minimum hotspot metric coverage is active for startup, scheduler, export, and external Scopus integration paths,
  - async executor capacity/rejection diagnostics are exposed via queue/active/rejection signals.
