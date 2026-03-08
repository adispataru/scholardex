# H08-S03 Health/Readiness/Operability Findings

Date: 2026-03-04  
Scope: runtime operability posture for startup readiness, health signaling, background processing, and failure triage.

## 1. Method

- Reviewed runtime configuration and wiring:
  - `build.gradle`
  - `src/main/resources/application.properties`
  - `src/main/java/ro/uvt/pokedex/core/CoreApplication.java`
  - `src/main/java/ro/uvt/pokedex/core/config/AsyncConfiguration.java`
- Reviewed high-impact operational flows:
  - `src/main/java/ro/uvt/pokedex/core/service/application/AdminUserBootstrapRunner.java`
  - `src/main/java/ro/uvt/pokedex/core/service/application/GeneralInitializationService.java`
  - `src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java`
  - `src/main/java/ro/uvt/pokedex/core/controller/ExportController.java`
  - `src/main/java/ro/uvt/pokedex/core/controller/CustomErrorController.java`

## 2. Findings (Severity-Ordered)

| ID | Severity | Finding | Evidence | Impact | Initial direction |
|---|---|---|---|---|---|
| `O-H08-01` | high | **No actuator health/readiness/liveness baseline** | `build.gradle` has no `spring-boot-starter-actuator`; `application.properties` has no `management.endpoint.*`/`management.endpoints.*` exposure config. | No machine-readable runtime readiness/health contract for deployment/ops automation. | Add actuator baseline and explicit health/readiness exposure policy in H08-S04/S06. |
| `O-H08-02` | high | **Startup bootstrap needs explicit readiness and diagnostics contract** | `AdminUserBootstrapRunner` executes admin bootstrap at startup; non-Scopus/WoS imports are moved to explicit admin operations in `GeneralInitializationService`. | Missing/failed admin bootstrap can degrade startup readiness; heavy imports should remain operationally explicit. | Keep startup phase observability contract and critical-vs-optional split; keep imports admin-triggered, not automatic startup work. |
| `O-H08-03` | high | **Scheduled background worker has no explicit operability endpoint/signal beyond logs and DB status** | `ScopusUpdateScheduler` polls queue continuously via `@Scheduled` (`src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java:57`) and records task status, but no health/lag counters or heartbeat endpoints. | Hard to detect degraded scheduler throughput/backlog from outside the app. | Add scheduler health signals (queue depth/lag/error rate) and expose via metrics/health contributors. |
| `O-H08-04` | medium-high | **Async executor capacity is fixed and uninstrumented** | `AsyncConfiguration` uses `ThreadPoolTaskExecutor` with `core=2`, `max=2`, `queue=100` and no rejection/queue telemetry hooks (`src/main/java/ro/uvt/pokedex/core/config/AsyncConfiguration.java:12-19`). | Under load, saturation/backpressure is opaque and may silently degrade throughput. | Add executor monitoring and queue/rejection diagnostics contract; tune via config properties. |
| `O-H08-05` | medium-high | **Critical external dependency path lacks readiness surface** | `CoreApplication` configures `WebClient` to external scopus python service via `scopus.python.base-url` (`src/main/java/ro/uvt/pokedex/core/CoreApplication.java:17-32`) without explicit dependency readiness indicator. | External dependency outages may appear as runtime failures without pre-flight readiness signal. | Add dependency-specific readiness checks and failure mode mapping. |
| `O-H08-06` | medium | **Export streaming failure semantics remain weak for operability** | `ExportController` catches exceptions and prints stack trace during streaming (`src/main/java/ro/uvt/pokedex/core/controller/ExportController.java:52-54`). | Partial/broken export outcomes can surface without clear operational error state. | Align export error behavior with explicit exception mapping + structured diagnostics. |
| `O-H08-07` | medium | **Error controller maps templates but provides limited operational context** | `CustomErrorController` maps `/error` status codes to views (`src/main/java/ro/uvt/pokedex/core/controller/CustomErrorController.java:18-36`) with no operational error identifiers/correlation context. | Incident triage from user-reported failures remains manual and low-fidelity. | Add correlation ID surfacing and consistent error metadata across MVC/API boundaries. |

## 3. Operability Surface Snapshot

Current strengths:
- Background task lifecycle persistence exists (`PENDING/IN_PROGRESS/FAILED/COMPLETED`) in scheduler tasks.
- Scheduler failure paths log errors with task IDs.
- Custom error templates reduce raw whitelabel exposure (`server.error.whitelabel.enabled=false`).

Current blind spots:
- No standard health/readiness endpoints.
- No explicit dependency readiness for external integrations.
- No run-time capacity/lag telemetry for async and scheduler execution.

## 4. Priority Next-Look List (Top 5)

1. `O-H08-01` establish actuator health/readiness baseline.
2. `O-H08-02` define startup readiness phases for bootstrap/import tasks.
3. `O-H08-03` add scheduler operability signals (queue/lag/error visibility).
4. `O-H08-04` instrument async executor saturation and throughput.
5. `O-H08-05` add external dependency readiness checks.

## 5. H08-S03 Exit Status

- Major operational blind spots are identified and prioritized.
- Findings are ready to feed `H08-S04` observability contracts and `H08-S06` phased remediation planning.

## 6. B12 Adoption Update (2026-03-04)

- `B12 / H08-P2` implemented baseline operability controls for `O-H08-01..05`:
  - Actuator enabled with explicit probe exposure (`/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`) and restricted non-probe actuator surface.
  - Startup readiness is phase-tracked with critical-vs-optional semantics and readiness-aware health contributor (`startup`).
  - External dependency readiness contributor added for Scopus Python integration (`scopusPython`) with bounded timeout.
  - Baseline metrics added for startup phases, scheduler poll/task outcomes, forum export outcomes, and external Scopus Python calls.
  - Async executor queue/active/rejection diagnostics are now exported via metrics with rejection warning signal.
