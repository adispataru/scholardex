# H08-S02 Logging and Diagnostics Drift Inventory
Status: active indexed source-of-truth document.

Date: 2026-03-04  
Scope: logging quality, diagnostic context propagation, and error-observability consistency in high-impact runtime paths.

## 1. Method

- Scanned transport, service, and scheduler layers for:
  - `printStackTrace`, `System.out.println`, and inconsistent logger usage.
  - missing correlation context (`MDC`, request/trace identifiers).
  - exception handling paths with weak diagnostic outcomes.
- Reviewed key files:
  - `src/main/java/ro/uvt/pokedex/core/controller/ExportController.java`
  - `src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java`
  - `src/main/java/ro/uvt/pokedex/core/view/AdminViewController.java`
  - `src/main/java/ro/uvt/pokedex/core/service/CacheService.java`
  - `src/main/java/ro/uvt/pokedex/core/service/ScopusService.java`
  - `src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java`
  - `src/main/java/ro/uvt/pokedex/core/service/reporting/ComputerScienceBookService.java`
  - `build.gradle`
  - `src/main/resources/application.properties`

## 2. Findings (Severity-Ordered)

| ID | Severity | Finding | Evidence | Impact | Initial direction |
|---|---|---|---|---|---|
| `L-H08-01` | high | **Transport-layer exception paths still use `printStackTrace`** | `ExportController` catch block (`src/main/java/ro/uvt/pokedex/core/controller/ExportController.java:53`), `AdminGroupController#importGroups` (`src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java:214`). | Inconsistent diagnostics, noisy stderr, and weak production observability on failure. | Replace with structured logger calls + consistent error outcome contract (paired with H07 remediation contract). |
| `L-H08-02` | high | **No request correlation context baseline** | No `MDC`, `traceId`, or request-id propagation found across controller/service code (`src/main/java/ro/uvt/pokedex/core/view/**`, `src/main/java/ro/uvt/pokedex/core/controller/**`, `src/main/java/ro/uvt/pokedex/core/service/**`). | Logs from concurrent requests/background tasks are hard to correlate; triage time increases. | Define correlation contract in H08-S04 and add request/job context enrichers. |
| `L-H08-03` | high | **No centralized logging configuration contract** | No `logback-spring.xml` or logging config artifacts under `src/main/resources`; no `logging.level.*` policy in `application.properties`. | Log format/level behavior is implicit and prone to drift per class/environment. | Introduce explicit logging baseline (format, levels, appenders, redaction policy). |
| `L-H08-04` | medium-high | **Direct `System.out.println` usage in production paths** | `AdminViewController` export loop debug print (`src/main/java/ro/uvt/pokedex/core/view/AdminViewController.java:167`), `CacheService` diagnostics (`src/main/java/ro/uvt/pokedex/core/service/CacheService.java:83,105,107,109,213`), `ScopusService#parseToken` (`src/main/java/ro/uvt/pokedex/core/service/ScopusService.java:48`). | Bypasses logging policy and level control; can leak data and produce non-structured output. | Replace `System.out` with logger at appropriate levels and context fields. |
| `L-H08-05` | medium-high | **Logger category drift in reporting service** | `ComputerScienceBookService` logger is bound to `ComputerScienceConferenceScoringService.class` (`src/main/java/ro/uvt/pokedex/core/service/reporting/ComputerScienceBookService.java:23`). | Misattributed log source complicates incident triage and ownership. | Rebind logger to owning class and add small regression guard for logger metadata consistency if feasible. |
| `L-H08-06` | medium | **Background scheduler has useful failure logs but no stable operation context IDs in log lines** | `ScopusUpdateScheduler` logs failures by task id (`src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java:75,136`) but no standardized request/run correlation fields. | Partial diagnosability; cross-component traces still fragmented. | Define scheduler/job log contract (`jobType`, `taskId`, `requestId`, `phase`) and standardize messages. |
| `L-H08-07` | medium | **Error observability remains mixed between redirects/runtime exceptions** | `UserReportFacade` returns `redirect:/error` view models (`src/main/java/ro/uvt/pokedex/core/service/application/UserReportFacade.java:276,291,296`) while other paths throw runtime exceptions (for example `AdminViewController` export path). | Similar failures generate inconsistent operational signals and user outcomes. | Align with H07 exception-mapping contract and emit uniform structured diagnostics for mapped failures. |
| `L-H08-08` | medium | **Potential sensitive payload leakage risk in raw-body debug output** | `ScopusService#parseToken` prints raw response body (`src/main/java/ro/uvt/pokedex/core/service/ScopusService.java:48`). | Token/API response content can leak to logs/console in production contexts. | Remove raw body print; if needed, log sanitized metadata only. |

## 3. Coverage Notes

Current strengths:
- Many services already use SLF4J-style parameterized logging.
- Scheduler failure paths include task identifiers (`ScopusUpdateScheduler`).
- H07 guardrails already prevent introducing new `printStackTrace` outside allowlisted debt files (`scripts/verify-h07-guardrails.js`).

Current weaknesses:
- No global logging contract (format, levels, correlation fields).
- Legacy console/stacktrace patterns remain in high-impact paths.
- Diagnostics semantics are still uneven across transport/service boundaries.

## 4. Priority Next-Look List (Top 5)

1. `L-H08-01` remove remaining `printStackTrace` in transport endpoints.
2. `L-H08-02` establish request/job correlation context propagation policy.
3. `L-H08-03` define and apply centralized logging configuration baseline.
4. `L-H08-04` remove `System.out.println` from runtime paths.
5. `L-H08-05` fix logger category drift (`ComputerScienceBookService`).

## 5. H08-S02 Exit Status

- Logging and diagnostics drift risks are inventoried with severity and file evidence.
- Highest-risk items have remediation direction and direct traceability for `H08-S04` contracts and `H08-S05` guardrails.

## 6. B03 Status Update (2026-03-04)

- Resolved in `B03 / H08-P0`:
  - `L-H08-01`: removed transport `printStackTrace` in `ExportController` and `AdminGroupController`.
  - `L-H08-04`: replaced active runtime `System.out/System.err` usage with structured logging in:
    - `AdminViewController`
    - `CacheService`
    - `ScopusService`
    - `RankingService`
    - `ArtisticEventsService`
    - `ActivityReportingService`
    - `WoSExtractor`
  - `L-H08-05`: corrected logger binding in `ComputerScienceBookService`.
  - `L-H08-08`: removed raw payload print in `ScopusService#parseToken` and kept only sanitized metadata logging.
- `O-H08-06` marked resolved for diagnostics signal quality:
  - export failure path now emits structured `ERROR` logs while preserving current transport response semantics (behavior mapping remains under H07-R3 scope).
