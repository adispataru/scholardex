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

  - [x] `H49.1` **`ro.uvt.pokedex.core.handlers`** — coverage gap (3 classes). *(completed 2026-04-28)*
    Baseline: line 12% (3/25), mutation 14% (1/7), test strength 100% (1/1).
    Scope hint: minimal tests today; the few assertions that exist are tight. This is a coverage gap, not an assertion gap — adding happy-path and error-path tests for `ApiAccessDeniedHandler`, `ApiAuthenticationEntryPoint`, and `CustomAccessDeniedHandler` should move all three numbers fast.
    Handover:
    - Added direct handler tests for `ApiAccessDeniedHandler` and `ApiAuthenticationEntryPoint`, asserting status, JSON content type, envelope fields, request path, and parseable timestamps.
    - `CustomAccessDeniedHandler` was already covered and unchanged.
    - Added PIT property overrides in `build.gradle` so package slices can be verified with `-PpitTargetClasses=...` and `-PpitTargetTests=...` while preserving the default H49 scope.
    - Focused verification passed: `./gradlew test --tests '*handlers*Test' -q`.
    - Targeted PIT verification passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.handlers.*' -PpitTargetTests='ro.uvt.pokedex.core.handlers.*' -q`.
    - Post-remediation result: line 100% (25/25), mutation 100% (7/7), test strength 100% (7/7); no surviving handler mutants.

  - [x] `H49.2` **`ro.uvt.pokedex.core.service`** — top-level services (3 classes). *(completed 2026-04-28)*
    Baseline: line 28% (55/194), mutation 18% (21/114), test strength 72% (21/29).
    Scope hint: `CacheService`, `CustomUserDetailsService`, `UserService`. Where tests exist they're strong (72% test strength); the gap is breadth. Add tests covering the untested code paths in each service rather than strengthening existing ones.
    Handover:
    - Added direct unit coverage for `CustomUserDetailsService` success/missing-user behavior.
    - Added `UserService` tests for user CRUD helpers, role parsing/validation, password-encoding create flow, lock/delete role updates, researcher-profile save/update/delete, missing-user errors, and normalized author-name matching.
    - Expanded `CacheServiceTest` for cache misses, normalized-title lookup guards, duplicate conference-title indexing, mutable forum/author/affiliation caches, clear/save flush operations, and university author-id resolution from group members.
    - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.CacheServiceTest' --tests 'ro.uvt.pokedex.core.service.CustomUserDetailsServiceTest' --tests 'ro.uvt.pokedex.core.service.UserServiceTest' -q`.
    - Targeted PIT verification passed with explicit classes/tests: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.CacheService,ro.uvt.pokedex.core.service.CustomUserDetailsService,ro.uvt.pokedex.core.service.UserService' -PpitTargetTests='ro.uvt.pokedex.core.service.CacheServiceTest,ro.uvt.pokedex.core.service.CustomUserDetailsServiceTest,ro.uvt.pokedex.core.service.UserServiceTest' -q`.
    - Post-remediation result: line 94% (183/194), mutation 93% (106/114), test strength 94% (106/113).
    - Per-class PIT result: `CacheService` line 89% (87/98), mutation 89% (40/45), test strength 89% (40/45); `CustomUserDetailsService` line/mutation/test strength 100%; `UserService` line 100% (93/93), mutation 96% (64/67), test strength 97% (64/66).
    - Remaining mutants are low-value edge cases: `CacheService` guards for impossible/private null or blank normalized-title keys and private conference-ranking-key fallback behavior; `UserService` role-removal equivalent behavior where desired roles are re-added, plus empty-name matching edge behavior. No production changes were needed.

  - [x] `H49.3` **`ro.uvt.pokedex.core.service.scopus`** — worst test-strength in the report (2 classes). *(completed 2026-04-28)*
    Baseline: line 63% (280/443), mutation 16% (39/237), test strength 30% (39/132).
    Scope hint: tests run a lot of code without verifying outcomes. Open the package report, list surviving mutants per class, and add output-level assertions (return values, side-effect verification, exception messages). This is where the highest payoff per assertion lives.
    Assessment: `SchedulerCorrelationSupport` is already near threshold; `ScopusUpdateScheduler` is the real blocker. The scheduler mixes queue polling, retry policy, request planning, citation-date computation, Python client calls, ingestion orchestration, exception mapping, metrics, and MDC in one large component. Prefer extracting package-private collaborators with direct tests over adding more reflection-heavy tests to the monolith.

    Sub-slices:

    - [x] `H49.3a` **Correlation support closeout.** *(completed 2026-04-28)*
      Scope: add the missing `SchedulerCorrelationSupport` assertions for blank/null normalization and restoring pre-existing MDC values.
      Exit criteria: correlation support reaches or exceeds the H49 mutation/test-strength threshold; any remaining mutants are classified.
      Handover:
      - Added direct assertions for null/blank/whitespace normalization to `unknown` or trimmed MDC values.
      - Added direct assertions that pre-existing MDC values are restored after the scheduler context closes.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupportTest' -q`.
      - Targeted PIT verification passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupport' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupportTest' -q`.
      - Post-remediation result: line 90% (19/21), mutation 90% (9/10), test strength 90% (9/10).
      - Remaining survivor: `restoreOrRemove` equality-check mutation is low-value/equivalent for observable MDC restoration behavior; covered contexts either restore the previous value or remove the absent key.

    - [x] `H49.3b` **Retry/failure policy extraction.** *(completed 2026-04-28)*
      Scope: extract package-private retry readiness/backoff/failure-decision logic from `ScopusUpdateScheduler` into a directly testable collaborator.
      Candidate surface: `isReadyForAttempt`, `computeBackoffSeconds`, max-attempt fallback, retryable-vs-terminal status/message/next-attempt behavior for publication and citation tasks.
      Exit criteria: retry/backoff/failure paths are covered without reflection; publication and citation failure handling mutants are mostly killed.
      Handover:
      - Extracted package-private `ScopusSchedulerRetryPolicy` for attempt readiness, exponential backoff, max-attempt fallback, and retryable-vs-terminal failure decisions.
      - Wired `ScopusUpdateScheduler` publication/citation readiness and failure handling through the policy while keeping scheduler-owned persistence, execution-date stamping, and logging in the scheduler.
      - Added direct `ScopusSchedulerRetryPolicyTest` coverage for missing/past/future/malformed retry timestamps, backoff progression/capping, retry scheduling, terminal failures, error-code/message propagation, and default max-attempt fallback.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicyTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Targeted PIT for the extracted policy passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicy' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicyTest' -q`.
      - Policy PIT result: line 100% (22/22), mutation 94% (17/18), test strength 94% (17/18). Remaining survivor is low-value/equivalent around blank `nextAttemptAt`: mutating the blank guard still falls through to parse failure and returns ready.
      - H49.3 scoped PIT after H49.3a-b: line 66% (301/456), mutation 24% (55/233), test strength 38% (55/146). Remaining package gap is still concentrated in `ScopusUpdateScheduler`; continue with H49.3c+ extractions.

    - [x] `H49.3c` **Integration exception mapping extraction.** *(completed 2026-04-28)*
      Scope: extract exception mapping from `ScopusUpdateScheduler` into a directly testable collaborator.
      Candidate surface: existing `IntegrationException`, HTTP 4xx/5xx, request failures, buffer limit, decoding failures, generic nested runtime exceptions, root-cause traversal guard.
      Exit criteria: mapping behavior is covered by direct tests and no longer depends on scheduler-private reflection.
      Handover:
      - Extracted package-private `ScopusIntegrationExceptionMapper` for scheduler runtime wrapping and Python integration exception classification.
      - Wired `ScopusUpdateScheduler` Python error paths and task failure handling through the mapper; removed scheduler-private exception-mapping helpers.
      - Added direct mapper tests for already-mapped exceptions, unexpected runtime persistence fallback, HTTP 500/400 classification, request failures, buffer limit, decoding failures, generic nested failures, blank root-cause details, and the finite cause traversal guard.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapperTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Targeted PIT for the extracted mapper passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapper' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapperTest' -q`.
      - Mapper PIT result: line 100% (36/36), mutation 97% (31/32), test strength 97% (31/32). Remaining survivor is low-value around the finite traversal guard boundary.
      - H49.3 scoped PIT after H49.3a-c: line 70% (321/458), mutation 33% (78/233), test strength 48% (78/163). Remaining package gap is still concentrated in `ScopusUpdateScheduler`; continue with publication/citation planning extractions.

    - [x] `H49.3d` **Publication sync planning extraction.** *(completed 2026-04-28)*
      Scope: extract publication date/request planning from `ScopusUpdateScheduler`.
      Candidate surface: `resolveFromDate`, `computeFromDate`, `parseCoverDate`, `buildRequest`, FULL/PERIOD/SINCE_LAST_UPDATE behavior, page-size/cursor/enrichment/format fields.
      Exit criteria: publication request/date mutants are covered through direct planner tests; scheduler tests only verify orchestration.
      Handover:
      - Extracted package-private `ScopusPublicationSyncPlanner` for publication `fromDate` resolution, cover-date parsing, fallback date calculation, and author-works request construction.
      - Wired `ScopusUpdateScheduler` publication task orchestration through the planner; scheduler now fetches author publications once for publication planning and keeps ingestion/persistence/canonical rebuild orchestration.
      - Removed the scheduler reflection test for `computeFromDate`; date/request behavior is covered by direct planner tests.
      - Removed duplicate request setters for `include_enrichment` and `format` because `AuthorWorksRequest` already defaults them, reducing equivalent PIT noise without changing the outgoing request.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlannerTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Targeted PIT for the extracted planner passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlanner' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlannerTest' -q`.
      - Planner PIT result: line 94% (33/35), mutation 95% (21/22), test strength 95% (21/22). Remaining survivor is low-value/equivalent around the null/blank cover-date guard; removing the guard still returns `Optional.empty()` via parse failure/catch for observable inputs.
      - H49.3 scoped PIT after H49.3a-d: line 72% (334/464), mutation 40% (93/231), test strength 55% (93/168). Remaining package gap is still concentrated in citation planning and scheduler orchestration; continue with H49.3e.

    - [x] `H49.3e` **Citation sync planning extraction.** *(completed 2026-04-28)*
      Scope: extract citation EID/date/request planning from `ScopusUpdateScheduler`.
      Candidate surface: `computeEidLastCitationDatesForAuthor`, FULL/PERIOD/default citation modes, missing author publications, missing cited/citing publication rows, malformed/blank cover dates, latest citing-date selection, citation request fields.
      Exit criteria: citation planning mutants are covered through direct planner tests; edge cases are explicit and no longer buried in scheduler integration tests.
      Handover:
      - Extracted package-private `ScopusCitationSyncPlanner` for cited/citing publication ID selection, per-EID last citation date calculation, FULL/PERIOD/default mode transformation, and `CitationsByEidRequest` construction.
      - Wired `ScopusUpdateScheduler` citation task orchestration through the planner; scheduler now owns projection reads, task persistence, Python calls, ingestion, and canonical rebuild orchestration only.
      - Fixed a production edge case in PERIOD citation mode: author publications with no previous citation date now cap to the period start instead of attempting `null.compareTo(...)`.
      - Removed a duplicate `includeEnrichment` setter because `CitationsByEidRequest` already defaults it to `true`, reducing equivalent PIT noise without changing the outgoing request.
      - Added direct planner tests for author publication ID selection, distinct citing ID selection, no-publication handling, latest valid citing date selection, missing cited/citing rows, blank/malformed dates, FULL/PERIOD/default mode behavior, and request defaults.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlannerTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Targeted PIT for the extracted planner passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlanner' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlannerTest' -q`.
      - Citation planner PIT result: line 100% (62/62), mutation 86% (25/29), test strength 86% (25/29). Remaining survivors are low-value/equivalent around empty input behavior, null-EID guard behavior, and PERIOD boundary equality where the output string is unchanged.
      - H49.3 scoped PIT after H49.3a-e: line 79% (372/472), mutation 49% (116/238), test strength 61% (116/189). Remaining package gap is concentrated in scheduler orchestration side effects; continue with H49.3f.

    - [x] `H49.3f` **Scheduler orchestration assertions.** *(completed 2026-04-28)*
      Scope: after extractions, tighten `ScopusUpdateSchedulerTest` around observable orchestration only.
      Candidate surface: pending queue counts, future-attempt skips, saved task snapshots for publication/citation success, no-publications citation completion, canonical rebuild calls, failure counters, and MDC context closure.
      Exit criteria: package-scoped PIT for `ro.uvt.pokedex.core.service.scopus` reaches at least 60% mutation coverage and 65% test strength, with remaining survivors classified in the H49.3 handover.
      Handover:
      - Added scheduler orchestration assertions that snapshot repository-save state at save time, so intermediate `IN_PROGRESS` saves and final task saves are observable despite in-place task mutation.
      - Strengthened publication success assertions for start/final task status, message, execution date, retry metadata reset, canonical rebuild call, external-call success metric, and processed-task success metric.
      - Strengthened citation success assertions for start/final task status, imported publication/link counts, retry metadata reset, canonical rebuild call, external-call success metric, and processed-task success metric.
      - Added no-publications citation completion coverage that verifies no Python call, no ingestion/canonical rebuild, final completion message, default max-attempt fallback, and projection-read short-circuiting.
      - Added publication retry-failure coverage for retryable Python integration errors, including scheduled retry status/message, next-attempt presence, error code/message, failure counters, and no downstream ingestion/canonical rebuild.
      - Added citation terminal-failure coverage for empty Python response, including terminal failure status/message, execution date, cleared next-attempt, error code/message, and failure counters.
      - Post-closeout micro-pass addressed the best-ROI survivors: publication empty-response terminal failure, publication max-attempt fallback, PERIOD end-year filtering, and author-works pagination cursor handling.
      - Medium-ROI pass added citation response edge-case coverage for null/empty citing lists, missing/blank citing EIDs, and valid-item-only ingestion, and removed an unused `text(JsonNode, ...)` helper from the scheduler.
      - Follow-up state-reset pass seeded stale retry/error fields into publication/citation success and citation terminal-failure tests, proving final saves clear stale `nextAttemptAt`, `lastErrorCode`, and `lastErrorMessage` where applicable.
      - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupportTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicyTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapperTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlannerTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlannerTest' --tests 'ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Final H49.3 scoped PIT passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupport,ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicy,ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapper,ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlanner,ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlanner,ro.uvt.pokedex.core.service.scopus.ScopusUpdateScheduler' -PpitTargetTests='ro.uvt.pokedex.core.service.scopus.SchedulerCorrelationSupportTest,ro.uvt.pokedex.core.service.scopus.ScopusSchedulerRetryPolicyTest,ro.uvt.pokedex.core.service.scopus.ScopusIntegrationExceptionMapperTest,ro.uvt.pokedex.core.service.scopus.ScopusPublicationSyncPlannerTest,ro.uvt.pokedex.core.service.scopus.ScopusCitationSyncPlannerTest,ro.uvt.pokedex.core.service.scopus.ScopusUpdateSchedulerTest' -q`.
      - Final H49.3 result: line 96% (447/465), mutation 85% (195/230), test strength 86% (195/227).
      - Remaining survivors are classified as low-value or equivalent: MDC/context-close/log-duration observability mutants; duplicate/guard fallthroughs in retry/date parsing; planner boundary cases where output is unchanged; and scheduler text-helper null/blank guard mutations that do not justify further monolith-specific tests now that the package exceeds the H49 threshold.

  - [x] `H49.4` **`ro.uvt.pokedex.core.service.importing`** — top-level importing (8 classes). *(completed 2026-04-28)*
    Baseline: line 41% (307/756), mutation 16% (52/316), test strength 40% (52/131).
    Scope hint: both kinds of gap — missing tests and weak assertions. Triage by class size; pick the largest two or three first.
    Handover:
    - Added `GroupServiceTest`: 31 tests covering early-exit, group field propagation, new/existing user handling, position parsing (including CS III/II/I order bug fixed), scopusId filtering, and CSV validation. Fixed `parsePosition` CS order bug and removed dead `memberIds == null` guard. Post-remediation: line 98%, mutation 91% (52/57), test strength 91%.
    - Added `CoreConferenceRankingServiceTest`: ~22 tests covering all supported years (2008–2023), rank parsing, existing conference update, malformed row, unreadable file, async delegation. Post-remediation: line 100%, mutation 81% (44/54), test strength 81%.
    - Added `URAPRankingServiceTest`: 16 tests using real XSSFWorkbook files covering early return, file filter, score fields, year extraction, existing university, malformed rank, null cells, string numeric cells, directory filtering, unreadable file, missing cell, numeric cell. Post-remediation: line 98%, mutation 81% (30/37), test strength 81%.
    - Added `ScopusDataServiceTest` extensions: `loadScopusDataIfEmptySync`, IO error paths, `createUploadBatchId` normalization, `normalizeOptionalValue` "null"/"n/a" literals, `readInt` string fallback and numeric paths, `readRequiredText` null/blank node cases, `readDataSize` scalar eid, `applyIngestionOutcome` error path, `extractCitationsFromJson` blank eid and NUMBER node paths, citation batch skipped/error count assertions. Post-remediation: line 79%, mutation 51% (68/134), test strength 69%. Remaining survivors: logging-only arithmetic and heartbeat mutations (equivalent), in-loop batch flush path (needs 1000+ citations, impractical), dead `splitSemicolon` method.
    - Added `AdminUserServiceTest`: 3 tests for count>0 skip, email/password on create, role assignment.
    - Added `ArtisticEventsServiceTest`: 2 tests for count>0 skip and happy-path smoke test (file exists in repo).
    - Added `CNCSISServiceTest`: 4 tests for count>0 skip, IO error, valid xlsx, null row skip.
    - Added `SenseRankingServiceTest`: 4 tests for IO error, valid ranking, invalid ranking (no setRanking), null row skip.
    - Full package PIT (all 8 classes): line 90% (679/753), mutation 68% (212/314), test strength 77% (212/275).

  - [ ] `H49.5` **`ro.uvt.pokedex.core.service.importing.scopus`** — high coverage masking weak assertions (15 classes).
    Baseline: line 81% (3127/3872), mutation 33% (705/2149), test strength 40% (705/1754).
    Scope hint: looks well-tested by line coverage but isn't. Focus exclusively on adding assertions to existing tests; very little new test scaffolding needed. Walk the surviving-mutants list class by class.

  - [ ] `H49.6` **`ro.uvt.pokedex.core.service.importing.wos`** — same shape as scopus importing (10 classes).
    Baseline: line 81% (2129/2623), mutation 39% (587/1517), test strength 48% (587/1227).
    Scope hint: same playbook as `H49.5` — high line coverage, assertion-strengthening work. Slightly better starting point.

  - [x] `H49.7` **`ro.uvt.pokedex.core.service.importing.wos.model`** — WoS model classes (3 classes). *(completed 2026-04-29)*
    Baseline: line 80% (24/30), mutation 33% (5/15), test strength 45% (5/11).
    Scope hint: small surface; review surviving mutants on equality, mapping, and value-object behavior. Likely a single sitting of work.
    Handover:
    - Added `WosParserRunSummaryTest`: 11 tests covering counter increments (processed/parsed/skipped/error), sample collection up to cap, null/blank sample rejection, unmodifiable list guard, and negative-maxSamples clamp.
    - Added `WosParsedEventResultTest`: 4 tests covering all three factory methods (`parsed`/`skipped`/`error`) and `WosIdentitySourceContext.empty()`.
    - Post-remediation: line 100% (30/30), mutation 100% (15/15), test strength 100%.

  - [ ] `H49.8` **`ro.uvt.pokedex.core.service.application`** — largest package (79 classes, 4585 mutations).
    Baseline: line 63% (5193/8253), mutation 30% (1365/4585), test strength 50% (1365/2754).
    Scope hint: the absolute mutant count is highest here, so even modest percentage gains move the project number. Break this down by sub-area (per natural grouping inside `application`) when adding subtasks; do not try to attack it as a single sweep.

  - [ ] `H49.9` **`ro.uvt.pokedex.core.service.application.model`** — application model classes (5 classes).
    Baseline: line 69% (18/26), mutation 26% (8/31), test strength 44% (8/18).
    Scope hint: small but weak; mirror `H49.7` approach.

  - [ ] `H49.10` **`ro.uvt.pokedex.core.service.reporting`** — middle of the pack (21 classes).
    Baseline: line 57% (1047/1853), mutation 34% (433/1272), test strength 56% (433/767).
    Scope hint: more balanced than the importing packages — both line coverage and test strength have room. Mix of new tests and stronger assertions on existing ones.
