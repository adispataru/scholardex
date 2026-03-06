# Project Recovery Tasks (High-Level)

Objective: raise runtime functional quality from baseline-safe behavior to production-grade correctness and resilience.

Done history moved to `TASKS-done.md`.

## Backlog

- [ ] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical user/admin business workflows end-to-end under realistic conditions.
  Deliverable: focused high-value functional test suite (multi-step user/admin/report/export flows) with deterministic fixtures and clear pass/fail contracts.
  Exit criteria: top business workflows are validated across success and failure paths, and functional regressions are caught before merge by repeatable automated checks.
  Subtasks:
  - [ ] `H13.1` Admin WoS maintenance end-to-end flow.
    Deliverable: deterministic workflow test for admin-triggered WoS maintenance chain (ingest -> facts -> projections -> verify) including authorization checks.
    Exit criteria: success and unauthorized/failure paths are both asserted.
  - [ ] `H13.2` User indicator refresh/export workflow.
    Deliverable: deterministic workflow test covering indicator refresh and export from user-facing flow.
    Exit criteria: refresh updates persisted score state and export contract remains stable.
  - [ ] `H13.3` Failure-path workflow gate.
    Deliverable: at least one full workflow-level degraded/failure scenario with deterministic error handling assertions.
    Exit criteria: failure mode is reproducible and blocks regressions.

- [ ] `H16` Java and Gradle modernization uplift.
  Goal: upgrade the runtime/build toolchain to newer Java + Gradle versions with deterministic local/CI behavior.
  Deliverable: aligned Java/Gradle versions, dependency/plugin compatibility fixes, and green baseline gates.
  Exit criteria: `java-smoke`, `quality-full`, and local `./gradlew check` pass on the upgraded toolchain without environment-specific hacks.
  Subtasks:
  - [x] `H16.1` Baseline and target matrix.
    Deliverable: documented current Java/Gradle/plugin/dependency versions and an explicit target upgrade matrix with compatibility notes.
    Exit criteria: upgrade scope and order are fixed, with rollback path and known risk hotspots identified.
    Status note (2026-03-06): completed in `docs/h16-toolchain-modernization-matrix.md` with pinned target direction (Java 25, Gradle 9.1.x+, Spring Boot 4.0.x LTS-target line), compatibility ownership, and rollback guards.
  - [x] `H16.2` Gradle wrapper and build tooling bump.
    Deliverable: upgraded Gradle wrapper and required build script/property updates to match the target Java/toolchain baseline.
    Exit criteria: `./gradlew --version`, configuration phase, and core build lifecycle start cleanly on the new wrapper.
    Status note (2026-03-06): completed with wrapper `9.1.0`, Java toolchain/launchers moved to `25`, macOS wrapper guard updated for JDK 25, and dependency-management plugin bumped to `1.1.7` for Gradle 9 compatibility (`--version`, `help`, `compileJava` all pass).
  - [x] `H16.3` Plugin and dependency compatibility remediation.
    Deliverable: minimal set of plugin/dependency upgrades or config changes required to restore compile/test/check behavior.
    Exit criteria: no deprecated/broken build integrations remain on critical paths (`compileJava`, `test`, `check`).
    Status note (2026-03-06): completed by upgrading Spring Boot to `4.0.2`, adding Boot 4 test-slice modules (`spring-boot-webmvc-test`, `spring-boot-data-mongodb-test`), pinning Testcontainers to `1.19.7`, migrating security/health/error APIs to Boot 4/Security 7 namespaces, and updating affected tests (`@MockBean -> @MockitoBean`, Boot 4 test annotation imports, redirect expectations); `compileJava`, `test`, and `check` pass.
  - [ ] `H16.4` CI parity and deterministic execution hardening.
    Deliverable: workflow and environment alignment updates so local and CI use the same Java/Gradle assumptions.
    Exit criteria: `java-smoke` and `quality-full` run with identical toolchain intent across local and CI.
  - [ ] `H16.5` Validation and closeout evidence.
    Deliverable: run log + short closeout note capturing command results, residual risks, and follow-ups.
    Exit criteria: local `./gradlew check` and CI gates (`java-smoke`, `quality-full`) are green on the upgraded stack.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
