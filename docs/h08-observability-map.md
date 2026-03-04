# H08-S01 Observability Surface Map and Signal Inventory

Date: 2026-03-04  
Scope: runtime diagnostics surface for logging, metrics, health/readiness, and operational touchpoints across startup, scheduler, exports, and key background flows.

## 1. Method

- Reviewed dependency/config baseline:
  - `build.gradle`
  - `src/main/resources/application.properties`
- Reviewed runtime hooks and transport error surfaces:
  - `src/main/java/ro/uvt/pokedex/core/CoreApplication.java`
  - `src/main/java/ro/uvt/pokedex/core/DataLoaderNew.java`
  - `src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java`
  - `src/main/java/ro/uvt/pokedex/core/controller/CustomErrorController.java`
  - `src/main/java/ro/uvt/pokedex/core/handlers/CustomAccessDeniedHandler.java`
  - `src/main/java/ro/uvt/pokedex/core/controller/ExportController.java`
- Reviewed existing repo-local guardrail scripts:
  - `scripts/verify-architecture-boundaries.js`
  - `scripts/verify-test-runtime.js`

## 2. Baseline Capability Snapshot

| Capability | Current state | Evidence |
|---|---|---|
| Structured application logging | Partial (SLF4J/Logback available, inconsistent usage patterns) | `build.gradle`, multiple services use `LoggerFactory` / `@Slf4j`; `ExportController` and `AdminGroupController` still use `printStackTrace` paths |
| Correlation/request tracing | Missing | no MDC/request-id/trace-id usage found in `src/main/java/**` |
| Metrics instrumentation | Missing | no Micrometer/Actuator dependencies or `@Timed`/counter/gauge usage in `build.gradle` and code |
| Health/readiness/liveness endpoints | Missing explicit operational setup | no actuator dependency and no `management.*` exposure config in `application.properties` |
| Startup signalization | Present but coarse | `DataLoaderNew` startup import/bootstrap runs on `CommandLineRunner` |
| Background job observability | Partial | `ScopusUpdateScheduler` logs failures and writes task status (`PENDING/IN_PROGRESS/FAILED/COMPLETED`) |
| Error routing observability | Partial | `CustomErrorController` maps status to error templates; no centralized structured exception logging contract |
| Runtime guardrail scripts | Present for architecture/tests, not observability-specific yet | `verify-architecture-boundaries`, `verify-test-runtime`, H07 guardrails |

## 3. Signal Inventory by Surface

## 3.1 Logging signals

Observed signal producers:
- Importing and ranking flows produce info/warn/error logs (for example `RankingService`, `ScopusDataService`, `URAPRankingService`, `CNFISScoringService2025`).
- Scheduler path logs operational failures with task context (`ScopusUpdateScheduler`).

Observed logging gaps/drift candidates (inventory-level only):
- Legacy `printStackTrace` remains in transport layer (`ExportController`, `AdminGroupController` import path).
- No global logging pattern config (`logback-spring.xml` absent under `src/main/resources`).
- No correlation context fields (request id/user id/operation id) enforced.

## 3.2 Metrics signals

Current state:
- No explicit metrics stack configured.
- No business counters/timers/gauges in runtime code.
- No endpoint-level or job-level latency/error-rate metrics.

Resulting implication:
- Runtime and SLO-style visibility currently depends on logs and database task states, not on metrics.

## 3.3 Health/readiness signals

Current state:
- `server.error.whitelabel.enabled=false` is configured, but this only affects error view behavior.
- No explicit health/readiness endpoint exposure configuration (`management.endpoints.*`, `management.endpoint.health.*` absent).
- No custom `HealthIndicator` implementations found.

Resulting implication:
- No standard machine-readable readiness/liveness baseline is visible in current app config.

## 3.4 Operational touchpoints

1. Startup bootstrap:
- `DataLoaderNew` executes admin bootstrap + data imports + ranking/cncsis/urap setup during application startup.
- This is high impact for startup time and failure diagnosis.

2. Scheduled background updates:
- `ScopusUpdateScheduler` polls queue every `${scopus.update.poll-ms:60000}`.
- Writes task lifecycle status into task repositories and logs failures.

3. Async ingestion/import paths:
- Multiple `@Async("taskExecutor")` methods (importing services) share fixed executor settings from `AsyncConfiguration` (`core=2`, `max=2`, `queue=100`).

4. Export/streaming endpoints:
- Export path streams XLSX output; exception path currently prints stacktrace and may not emit structured failure details.

## 4. Existing Operational Guardrails (Non-observability-specific)

- `npm run verify-architecture-boundaries`: layering and dependency drift prevention.
- `npm run verify-h04-baseline`: architecture/assets/critical tests + soft runtime budget report.
- `node scripts/verify-test-runtime.js`: soft warning report for slow test suites.

Gap note:
- These are useful quality gates, but they do not yet validate production observability contracts (log schema, readiness semantics, metric coverage).

## 5. High-Impact Coverage Notes for H08 Follow-ups

1. Logging quality is present but inconsistent (mixed structured logs and stacktrace printing).
2. Metrics surface is effectively absent.
3. Health/readiness surface is not operationalized.
4. Startup/scheduler/export flows are the most important observability hotspots due to blast radius.

## 6. S01 Exit Status

- Current observability surface is mapped across code/config/scripts.
- Signal inventory baseline is established for:
  - `H08-S02` logging/diagnostics drift inventory,
  - `H08-S03` operability and readiness gap inventory,
  - `H08-S04` contract definition.
