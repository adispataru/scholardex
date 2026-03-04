# H08-S06 Phased Remediation Plan and Lightweight Enforcement

Date: 2026-03-04  
Status: active execution plan for observability hardening.

## 1. Objective

Close top H08 observability and operability risks with behavior-safe, blast-radius ordered slices while keeping enforcement lightweight and runnable locally.

Inputs:
- `docs/h08-observability-map.md`
- `docs/h08-logging-drift-inventory.md`
- `docs/h08-operability-findings.md`
- `docs/h08-observability-contracts.md`
- `docs/h08-regression-guards.md`

## 2. Prioritized Slices (P0/P1/P2)

## P0: Logging and Failure-Signal Hygiene (High Risk / Low-to-Medium Change Size)

Targets:
- `L-H08-01`, `L-H08-04`, `L-H08-08`, `L-H08-05`, `O-H08-06`

Scope:
1. Replace runtime `printStackTrace` and `System.out/System.err` in active paths with structured logger calls.
2. Fix logger category drift (`ComputerScienceBookService` logger owner class).
3. Remove raw external payload debug logging in `ScopusService#parseToken`.
4. Preserve endpoint behavior while upgrading diagnostics quality.

Exit criteria:
- No non-allowlisted runtime `printStackTrace`/`System.out|err`.
- Export/import failure paths emit structured error logs with context.

Status update (2026-03-04):
- `P0` implemented via `B03`.
- Active runtime `printStackTrace` and targeted `System.out/System.err` usages were replaced with structured SLF4J logging in transport/service/reporting/importing paths.
- Logger owner drift for `ComputerScienceBookService` corrected.
- Raw external payload print in `ScopusService#parseToken` removed and replaced with sanitized metadata logging.
- H08 observability guardrail allowlists were reduced to zero for active runtime debt in this scope.

## P1: Correlation and Context Propagation (High Risk / Medium Change Size)

Targets:
- `L-H08-02`, `L-H08-06`, `L-H08-07`, `O-H08-07`

Scope:
1. Introduce request correlation ID propagation for MVC/API flows.
2. Add scheduler/job context fields (`jobType`, `taskId`, phase) in operational logs.
3. Align error diagnostics context with H07 exception mapping contract.

Exit criteria:
- HTTP and scheduler logs contain stable correlation fields.
- Error diagnostics are traceable end-to-end for one request/job execution.

Status update (2026-03-04):
- `P1` partially implemented via `B08` (Scopus-scheduler-first scope).
- Delivered:
  - HTTP correlation filter with `X-Request-Id` adopt-and-propagate behavior,
  - request-scoped MDC (`requestId`, `route`, `userId`),
  - Scopus scheduler MDC context (`jobType`, `taskId`, `phase`) at batch/task phases,
  - correlation-aware exception logging in centralized API/MVC handlers.
- Deferred:
  - broader non-Scopus scheduler/startup context rollout (if needed),
  - global log output pattern changes (explicitly out of scope for `B08`).

## P2: Health/Readiness and Metrics Baseline (High Risk / Higher Change Size)

Targets:
- `O-H08-01`, `O-H08-02`, `O-H08-03`, `O-H08-04`, `O-H08-05`

Scope:
1. Add actuator baseline and explicit health/readiness/liveness exposure policy.
2. Define startup phase readiness semantics (critical vs optional bootstrap tasks).
3. Add minimum metrics for startup, scheduler, export, and external dependency calls.
4. Add async executor visibility (queue/saturation/rejection diagnostics).

Exit criteria:
- Readiness/liveness endpoints are operationalized.
- Critical hotspots have latency + outcome metrics.
- Startup/scheduler capacity signals are externally diagnosable.

Status update (2026-03-04):
- `P2` implemented via `B12`.
- Delivered:
  - actuator baseline (`health`, `info`, `metrics`) with explicit liveness/readiness health groups,
  - probe-only public exposure policy for `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`,
  - startup phase readiness tracker (`critical-only` gating) and `startup` health contributor,
  - Scopus dependency readiness contributor (`scopusPython`) with bounded timeout,
  - baseline metrics for startup phases, scheduler poll/task outcomes, export outcomes, and external Scopus calls,
  - async executor queue/active/rejection diagnostics with configurable pool settings.

## 3. Lightweight Enforcement

Implemented in this slice:
- `npm run verify-h08-observability-guardrails`
- `npm run verify-h08-baseline`

`verify-h08-baseline` policy:
1. `npm run verify-architecture-boundaries`
2. `npm run verify-assets`
3. `npm run verify-template-assets`
4. `npm run verify-h08-observability-guardrails`

Enforcement intent:
- Prevent new diagnostics regressions while remediation proceeds.
- Keep checks fast and local-first.

## 4. Rollout Sequence

1. Execute `P0` logging hygiene slice and shrink allowlists.
2. Execute `P1` correlation/context slice with targeted characterization tests.
3. Execute `P2` health/readiness/metrics slice, then tighten guardrails where practical.

## 5. Validation Policy per Slice

Minimum required commands:
1. `npm run verify-h08-baseline`
2. `./gradlew compileJava`
3. `./gradlew test --tests "*CoreApplicationTests"`

When touching scheduler/report/export behavior:
4. Run targeted affected test classes where available.

## 6. Residual Risks

- Non-Scopus scheduler families may still need explicit context/metric rollout if added in future slices.
- Current baseline does not include a Prometheus-specific exporter contract.
- Global logging pattern centralization remains outside this slice.

## 7. H08-S07 Closeout and H09 Handoff

H08 baseline is now operational for day-to-day development:
- observability surface/finding docs are in place (`h08-observability-map`, `h08-logging-drift-inventory`, `h08-operability-findings`);
- canonical contracts are defined (`h08-observability-contracts`);
- local guardrails are executable (`verify-h08-observability-guardrails`, `verify-h08-baseline`).

Adoption guidance:
1. Run `npm run verify-h08-baseline` for observability-related code/config changes.
2. Keep debt-aware allowlists shrinking as remediation slices remove legacy diagnostics debt.
3. Treat H08 contracts as mandatory review criteria for new scheduler/startup/export changes.

H09 handoff constraints:
- H09 quality-gate work should include `verify-h08-baseline` in CI-required checks.
- Any CI hardening that changes observability guard strictness must reference H08 contracts and open risks (`P0/P1/P2`).

H09 adoption status (2026-03-04):
- CI bootstrap (`B09`) now includes `verify-h08-baseline` in GitHub Actions quality gates (`.github/workflows/h09-quality-gates.yml`).
