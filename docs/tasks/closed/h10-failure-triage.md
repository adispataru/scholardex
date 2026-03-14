# H10 Failure Triage Guide

Date: 2026-03-04  
Status: active troubleshooting baseline for contributor and CI triage.

## 1. Purpose

Provide deterministic triage paths for required local/CI checks so contributors can move from failure to fix quickly.

## 2. Fast Triage Workflow

1. Identify failing command/job (`guardrails`, `java-smoke`, `quality-full`, `dependency-review`, `codeql-analysis`).
2. Reproduce locally with the exact command.
3. Read the first explicit failure line from script/test output.
4. Apply a focused fix.
5. Re-run only the failing command first; then re-run full baseline if CI-sensitive.

## 3. Guardrail Failure Matrix

## 3.1 `npm run verify-architecture-boundaries`

Typical failures:

1. `new controller/view repository import is forbidden (Z1 -> Z4)`
2. `direct Z1 -> Z3 reporting import is forbidden`
3. `reporting must depend on ReportingLookupPort, not CacheService`

Likely cause:

1. New direct dependency from transport layer to repository/reporting package.
2. Back-edge reintroduced in reporting package.

Fix path:

1. Move logic behind an application facade (`core/service/application/**`) and remove forbidden import.
2. Replace direct reporting-cache dependency with `ReportingLookupPort`.
3. Re-run:
   - `npm run verify-architecture-boundaries`

## 3.2 `npm run verify-h06-persistence`

Typical failures:

1. raw year parsing forbidden (`substring(0,4)` / `split("-")[0]`)
2. typo API usage (`findAllByeIssn` retired)
3. missing canonical methods/patterns in persistence-sensitive facades
4. task namespace mismatch (`scholardex.tasks.*` expected)

Likely cause:

1. Legacy parsing/query path reintroduced.
2. Model/repository naming drift.

Fix path:

1. Use `PersistenceYearSupport` helper APIs.
2. Use canonical `findAllByEIssn`.
3. Keep task collection namespace as `scholardex.tasks.*`.
4. Re-run:
   - `npm run verify-h06-persistence`

## 3.3 `npm run verify-h07-guardrails`

Typical failures:

1. mutating `GET` route introduced
2. transport `printStackTrace` introduced
3. unsafe year parsing in controllers (`Integer.parseInt(startYear|endYear)`)
4. missing `@Valid` on targeted admin API `@RequestBody`
5. login form contract drift (`name="username"/"password"`, autocomplete attrs)
6. CSRF global disable found or missing `/api/**` ignore marker
7. strict import validation hooks missing in group import path

Likely cause:

1. Security/validation contract regression in controller/template config.

Fix path:

1. Migrate mutating routes to `POST`/safe verbs.
2. Replace parse branches with validated year-range support.
3. Add `@Valid` on targeted body DTO routes.
4. Restore login form field naming/autocomplete contract.
5. Keep CSRF enabled for MVC and explicit `/api/**` ignore only.
6. Re-run:
   - `npm run verify-h07-guardrails`

## 3.4 `npm run verify-h08-baseline`

This chain runs:

1. `verify-architecture-boundaries`
2. `verify-assets`
3. `verify-template-assets`
4. `verify-h08-observability-guardrails`

If it fails, fix the first failing sub-command and rerun baseline.

## 3.5 `npm run verify-h08-observability-guardrails`

Typical failures:

1. runtime `printStackTrace` / `System.out|err.println` usage
2. missing request correlation markers (`X-Request-Id`, MDC fields)
3. missing scheduler correlation context (`jobType`, `taskId`, `phase`)
4. missing actuator/readiness wiring markers in config/security
5. missing metrics markers in startup/scheduler/export code paths

Likely cause:

1. Observability contract drift after controller/service/config refactor.

Fix path:

1. Replace stdout/stack traces with structured logging.
2. Preserve request/scheduler MDC markers in touched code.
3. Keep actuator/readiness/metrics markers present in expected files.
4. Re-run:
   - `npm run verify-h08-observability-guardrails`

## 4. Build/Test Failures

## 4.1 `./gradlew compileJava`

Likely cause:

1. compile-time API mismatch after refactor.
2. missing bean wiring or constructor dependency changes.

Fix path:

1. inspect first compile error;
2. align constructor injection, imports, and DTO signatures;
3. rerun `./gradlew compileJava`.

## 4.2 `./gradlew test --tests "*CoreApplicationTests"` / `java-smoke`

Likely cause:

1. Spring context wiring drift.
2. missing/misaligned bean definitions in security/config.

Fix path:

1. inspect `UnsatisfiedDependencyException` root cause;
2. add/update mocked beans in slice tests or runtime wiring in config;
3. rerun targeted tests, then smoke.

## 4.3 `./gradlew check` / `quality-full`

Likely cause:

1. targeted tests failing after contract drift,
2. frontend verification failure chained in Gradle tasks.

Fix path:

1. run failing test class directly:
   - `./gradlew test --tests "*FailingClassName*" --stacktrace`
2. apply focused fix;
3. rerun `./gradlew check`.

## 5. CI Security Jobs

## 5.1 `dependency-review`

Likely cause:

1. PR introduces vulnerable dependency version.

Fix path:

1. bump to non-vulnerable range,
2. justify any unavoidable temporary risk in PR + follow-up issue with expiry.

## 5.2 `codeql-analysis`

Likely cause:

1. static-analysis finding or build setup issue for CodeQL extract.

Fix path:

1. inspect SARIF result in GitHub Security tab;
2. remediate flagged sink/source path or sanitize input flow;
3. rerun PR checks.

## 6. When to Run Full Parity

Run full parity before merge for CI-sensitive changes:

```bash
npm run verify-h09-baseline
```

Use targeted commands during inner-loop development; switch to parity before push.
