# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.

- [ ] `H49` Test quality remediation — PIT mutation gaps in `service` + `handlers`.
  Goal: lift mutation coverage and test strength across `ro.uvt.pokedex.core.service.*` and `.handlers.*` by closing assertion gaps where line coverage already exists and adding tests where coverage is missing entirely.
  Baseline (PIT 1.20.4, 153 classes, 2026-04-28): line 67% (12230/18134), **mutation 31%** (3224/10259), **test strength 47%** (3224/6836). Per-package numbers in each subtask below.
  Reference: `docs/test-quality.md`; reports under `build/reports/pitest/` and `build/reports/jacoco/test/`.
  Tooling unblockers (apply once before subtask work to make the measurement reliable): bump `jacoco.toolVersion` to a Java 25-compatible release so branch coverage stops reporting 0% on Java 25 bytecode; add the Arcmutate Spring plugin to the `pitest` config to remove Spring-pattern equivalent-mutant noise.
  Exit criteria: each subtask records a per-package baseline and post-remediation mutation score; non-trivial packages reach at least 60% mutation coverage with at least 65% test strength; remaining surviving mutants are explicitly classified as equivalent or out-of-scope in the subtask handoff; JaCoCo branch report parses cleanly under Java 25 and the Arcmutate Spring plugin is wired into PIT runs.
  Note: `service.importing.model` (67% mutation), `service.integration` (3 mutations, 67%), and `service.model` (8 lines, suspected dead code) are intentionally excluded from this initiative; `service.model` should be triaged for deletion under a separate cleanup if confirmed unused.

  Subtasks:

  - [ ] `H49.1` **`ro.uvt.pokedex.core.handlers`** — coverage gap (3 classes).
    Baseline: line 12% (3/25), mutation 14% (1/7), test strength 100% (1/1).
    Scope hint: minimal tests today; the few assertions that exist are tight. This is a coverage gap, not an assertion gap — adding happy-path and error-path tests for `ApiAccessDeniedHandler`, `ApiAuthenticationEntryPoint`, and `CustomAccessDeniedHandler` should move all three numbers fast.

  - [ ] `H49.2` **`ro.uvt.pokedex.core.service`** — top-level services (3 classes).
    Baseline: line 28% (55/194), mutation 18% (21/114), test strength 72% (21/29).
    Scope hint: `CacheService`, `CustomUserDetailsService`, `UserService`. Where tests exist they're strong (72% test strength); the gap is breadth. Add tests covering the untested code paths in each service rather than strengthening existing ones.

  - [ ] `H49.3` **`ro.uvt.pokedex.core.service.scopus`** — worst test-strength in the report (2 classes).
    Baseline: line 63% (280/443), mutation 16% (39/237), test strength 30% (39/132).
    Scope hint: tests run a lot of code without verifying outcomes. Open the package report, list surviving mutants per class, and add output-level assertions (return values, side-effect verification, exception messages). This is where the highest payoff per assertion lives.

  - [ ] `H49.4` **`ro.uvt.pokedex.core.service.importing`** — top-level importing (8 classes).
    Baseline: line 41% (307/756), mutation 16% (52/316), test strength 40% (52/131).
    Scope hint: both kinds of gap — missing tests and weak assertions. Triage by class size; pick the largest two or three first.

  - [ ] `H49.5` **`ro.uvt.pokedex.core.service.importing.scopus`** — high coverage masking weak assertions (15 classes).
    Baseline: line 81% (3127/3872), mutation 33% (705/2149), test strength 40% (705/1754).
    Scope hint: looks well-tested by line coverage but isn't. Focus exclusively on adding assertions to existing tests; very little new test scaffolding needed. Walk the surviving-mutants list class by class.

  - [ ] `H49.6` **`ro.uvt.pokedex.core.service.importing.wos`** — same shape as scopus importing (10 classes).
    Baseline: line 81% (2129/2623), mutation 39% (587/1517), test strength 48% (587/1227).
    Scope hint: same playbook as `H49.5` — high line coverage, assertion-strengthening work. Slightly better starting point.

  - [ ] `H49.7` **`ro.uvt.pokedex.core.service.importing.wos.model`** — WoS model classes (3 classes).
    Baseline: line 80% (24/30), mutation 33% (5/15), test strength 45% (5/11).
    Scope hint: small surface; review surviving mutants on equality, mapping, and value-object behavior. Likely a single sitting of work.

  - [ ] `H49.8` **`ro.uvt.pokedex.core.service.application`** — largest package (79 classes, 4585 mutations).
    Baseline: line 63% (5193/8253), mutation 30% (1365/4585), test strength 50% (1365/2754).
    Scope hint: the absolute mutant count is highest here, so even modest percentage gains move the project number. Break this down by sub-area (per natural grouping inside `application`) when adding subtasks; do not try to attack it as a single sweep.

  - [ ] `H49.9` **`ro.uvt.pokedex.core.service.application.model`** — application model classes (5 classes).
    Baseline: line 69% (18/26), mutation 26% (8/31), test strength 44% (8/18).
    Scope hint: small but weak; mirror `H49.7` approach.

  - [ ] `H49.10` **`ro.uvt.pokedex.core.service.reporting`** — middle of the pack (21 classes).
    Baseline: line 57% (1047/1853), mutation 34% (433/1272), test strength 56% (433/767).
    Scope hint: more balanced than the importing packages — both line coverage and test strength have room. Mix of new tests and stronger assertions on existing ones.
