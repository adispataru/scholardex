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

  - [x] `H49.5` **`ro.uvt.pokedex.core.service.importing.scopus`** — high coverage masking weak assertions (15 classes). *(completed 2026-04-29)*
    Baseline: line 81% (3127/3872), mutation 33% (705/2149), test strength 40% (705/1754).
    Scope hint: looks well-tested by line coverage but isn't. Focus exclusively on adding assertions to existing tests; very little new test scaffolding needed. Walk the surviving-mutants list class by class.
    Current narrowed slice (10 touched classes, 2026-04-29): line 82% (2895/3547), mutation 45% (867/1914), test strength 55% (867/1583). This is not the full package yet; it reflects the current assertion-strengthening pass around projection building, canonicalization, and fact/materialization flows.

    - [x] `H49.5a` **Projection builder rename + regression carryover.** *(completed 2026-04-29)*
      Scope: preserve and re-anchor projection builder coverage after the `ScopusProjectionBuilderService` -> `ScholardexProjectionBuilderService` rename.
      Handover:
      - Updated production wiring to inject `ScholardexProjectionBuilderService` from canonical materialization and application-layer callers.
      - Renamed the large projection-builder test suite to `ScholardexProjectionBuilderServiceTest` and kept the existing behavioral regression coverage attached to the renamed service.
      - Targeted verification passed as part of the `ro.uvt.pokedex.core.service.importing.scopus.*` test slice.

    - [x] `H49.5b` **Canonicalization assertion strengthening.** *(completed 2026-04-29)*
      Scope: tighten observable assertions in existing canonicalization tests instead of adding new scaffolding.
      Handover:
      - Strengthened `ScholardexAffiliationCanonicalizationServiceTest`, `ScholardexAuthorCanonicalizationServiceTest`, `ScholardexCitationCanonicalizationServiceTest`, and `ScholardexPublicationCanonicalizationServiceTest`.
      - Added stronger assertions around duplicate recovery, normalized fields, alias handling, source-link projection, and valid-edge retention so previously covered but weakly asserted branches now fail on behavioral drift.
      - Targeted verification passed as part of the `ro.uvt.pokedex.core.service.importing.scopus.*` test slice.

    - [x] `H49.5c` **Fact/materialization assertion strengthening.** *(completed 2026-04-29)*
      Scope: tighten facts, ingestion, and canonical materialization tests around saved state rather than broad new fixtures.
      Handover:
      - Strengthened `ScopusFactBuilderServiceTest`, `ScopusImportEventIngestionServiceTest`, `ScopusCanonicalMaterializationServiceTest`, `UserDefinedCanonicalizationServiceTest`, and `UserDefinedFactBuilderServiceTest`.
      - Added concrete assertions for persisted field values, normalized values, source batch/correlation metadata, forum/publication linkage, and downstream rebuild invocation.
      - Targeted verification passed as part of the `ro.uvt.pokedex.core.service.importing.scopus.*` test slice.

    - [x] `H49.5d` **Remaining survivor sweep.** *(completed 2026-04-29)*
      Scope: use the narrowed PIT survivor list to continue class-by-class assertion hardening before running the full package slice.
      Current evidence:
      - Focused tests passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.*' -q`.
      - Narrowed scoped PIT passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService,ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService,ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService,ro.uvt.pokedex.core.service.importing.scopus.UserDefinedCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.UserDefinedFactBuilderService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionServiceTest,ro.uvt.pokedex.core.service.importing.scopus.UserDefinedCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.UserDefinedFactBuilderServiceTest' -q`.
      - Narrowed PIT result: line 80% (2834/3547), mutation 34% (653/1914), test strength 43% (653/1531), 383 no-coverage mutations. Remaining gap is still broad; likely next ROI is in `ScholardexProjectionBuilderService` and `ScopusFactBuilderService`, with secondary cleanup in user-defined and citation/publication canonicalization paths.
      - Focused follow-up for `ScholardexProjectionBuilderService` + `ScopusFactBuilderService` added SQL-row and backfill/merge assertions, then passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderServiceTest' -q`
      - Two-class PIT passed:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService,ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderServiceTest' -q`
      - Initial two-class PIT result: line 93% (1027/1105), mutation 45% (287/639), test strength 49% (287/583), 56 no-coverage mutations.
      - Projection-builder-heavy follow-up added forum/author/affiliation/publication SQL-row assertions plus full-replacement and batch-refresh write-path coverage. `ScholardexProjectionBuilderService` alone improved to line 97% (474/489), mutation 50% (144/289), test strength 51% (144/283), 6 no-coverage mutations.
      - Updated two-class PIT result after the projection-builder pass: line 95% (1055/1105), mutation 53% (340/639), test strength 55% (340/614), 25 no-coverage mutations. This is a meaningful move, but `H49.5d` remains open because the survivor set is still broad in both helpers and control-flow utilities.
      - Second projection-builder helper pass tightened remaining survivors around fully populated publication rows, WoS-only forum merge fields, and batch refresh delete/reinsert helpers. `ScholardexProjectionBuilderService` alone improved again to line 97% (474/489), mutation 59% (170/289), test strength 60% (170/283), with 6 no-coverage mutations unchanged.
      - Updated two-class PIT result after the second projection-builder pass: line 95% (1055/1105), mutation 57% (366/639), test strength 60% (366/614), 25 no-coverage mutations. The hotspot is substantially healthier now, but `H49.5d` is still open because the remaining survivors are concentrated in helper/control-flow logic rather than the main write pipeline.
      - Third projection-builder surgical pass added coverage for sorted/derived citation maps, WoS merge ordering/skip behavior, and empty-collection author/affiliation view projection. `ScholardexProjectionBuilderService` alone improved to line 97% (475/489), mutation 65% (188/289), test strength 66% (188/283), with 6 no-coverage mutations still concentrated in lower-value helpers.
      - Updated two-class PIT result after the third projection-builder pass: line 96% (1056/1105), mutation 60% (384/639), test strength 63% (384/614), 25 no-coverage mutations. This narrowed hotspot now clears the H49 threshold, though the full `H49.5` package is still open pending broader class coverage.
      - Fact-builder follow-up added stronger persisted-state assertions for publication/forum/funding/citation/author/affiliation materialization plus targeted helper coverage around `boolValue`, `intValue`, `hashKey`, and funding-key normalization. `ScopusFactBuilderService` alone improved to line 95% (583/616), mutation 65% (229/350), test strength 69% (229/333), with 17 no-coverage mutations.
      - Updated two-class PIT result after broadening the fact-builder sweep: line 96% (1058/1105), mutation 65% (417/639), test strength 68% (417/616), 23 no-coverage mutations. Remaining survivors are now concentrated more heavily in control-flow/chunking math and lower-value helper paths than in saved-state assertions.
      - Next fact-builder ROI pass added publication field assertions for DOI/access/funding/article metadata and explicit two-chunk publication/citation logging coverage at 1001 events. `ScopusFactBuilderService` alone improved to line 95% (583/616), mutation 71% (248/350), test strength 74% (248/333), with 17 no-coverage mutations unchanged.
      - Updated two-class PIT result after the chunk-boundary pass: line 96% (1058/1105), mutation 68% (436/639), test strength 71% (436/616), 23 no-coverage mutations. Remaining survivors are increasingly concentrated in replay/skip branches, helper equivalence, and lower-value timing/math paths rather than core write behavior.
      - Replay/unchanged-branch follow-up tightened `buildFactsFromImportEvents` replay assertions for publication/forum/funding/citation paths, including normalized forum hashes, unchanged business fields, lineage refresh, and preserved materialization timestamps. `ScopusFactBuilderService` alone improved to line 96% (589/616), mutation 74% (259/350), test strength 77% (259/336), with no-coverage down to 14.
      - Updated two-class PIT result after the replay-branch pass: line 96% (1064/1105), mutation 70% (447/639), test strength 72% (447/619), 20 no-coverage mutations. Remaining survivors are now mostly helper-equivalence and lower-signal control-flow/timing paths, with less remaining pressure in the main replay/write branches.
      - Helper/skip-path cleanup pass added direct assertions for unsupported entity skip handling, missing publication/citation edge skips, `text`, `arrayValue`, `distinctNonBlank`, `normalizeForNameMerge`, `hashKey`, `sample`, and `nanosToMillis`. `ScopusFactBuilderService` alone improved to line 98% (603/616), mutation 77% (271/350), test strength 79% (271/344), with no-coverage down to 6.
      - Updated two-class PIT result after the helper/skip cleanup: line 98% (1078/1105), mutation 72% (459/639), test strength 73% (459/627), 12 no-coverage mutations. Remaining survivors are now concentrated in a smaller set of low-signal conditionals and math/timing helpers rather than untested business branches.
      - Final surgical helper cleanup added direct coverage for missing-field boolean handling, blank `splitDash`, `mapByKey` duplicate/blank-key filtering, case-insensitive `isUserDefinedSource`, and stricter `hashKey` shape assertions. `ScopusFactBuilderService` alone improved again to line 98% (603/616), mutation 78% (274/350), test strength 80% (274/344), with no-coverage unchanged at 6.
      - Updated two-class PIT result after the final surgical pass: line 98% (1078/1105), mutation 72% (462/639), test strength 74% (462/627), 12 no-coverage mutations. What remains is mostly stubborn low-value timing/control-flow/helper-equivalence fallout rather than meaningful importer behavior gaps.
      - High-ROI projection-builder follow-up added direct reflection-based assertions for `toForumView`, `toAuthorView`, `toPublicationView`, explicit `buildCitingMapForPublications` expectations, and stronger batch-refresh orchestration verification across the reporting write paths. `ScholardexProjectionBuilderService` alone improved to line 97% (476/489), mutation 73% (210/289), test strength 74% (210/283), with 6 no-coverage mutations unchanged.
      - Updated two-class PIT result after the projection-builder ROI pass: line 98% (1079/1105), mutation 76% (484/639), test strength 77% (484/627), 12 no-coverage mutations. Remaining projection survivors are now concentrated mostly in timing math, sort/order control-flow, and lower-value helper/lambda behavior rather than missed field mapping or write-path assertions.
      - Re-ran the broader narrowed 10-class `H49.5` slice after the hotspot remediation passes. Current narrowed result moved from line 80% / mutation 34% / strength 43% to line 82% (2895/3547), mutation 45% (867/1914), test strength 55% (867/1583), with no-coverage down to 331. PIT reported two timed-out mutants during this broader run, so this is useful breadth evidence but not yet a clean final closeout number.

    - [x] `H49.5e` **Canonical breadth refinement.** *(completed 2026-04-29)*
      Scope: finish the remaining non-hotspot survivor cleanup in the canonicalization classes after the projection/fact hotspots.
      Target classes:
      - `ScholardexAffiliationCanonicalizationService`
      - `ScholardexAuthorCanonicalizationService`
      - `ScholardexCitationCanonicalizationService`
      - `ScholardexPublicationCanonicalizationService`
      Focus:
      - remaining duplicate-recovery and replay/idempotence assertions
      - source-link / lineage / normalized-field invariants
      - surviving branch coverage that is currently masked by broad happy-path tests
      Progress:
      - Added direct metadata-contract coverage for all four canonical services, asserting pipeline key, entity type label, entity type, default chunk size, default source version, and heartbeat wiring.
      - Added targeted helper coverage in `ScholardexAffiliationCanonicalizationServiceTest` for normalized alias composition and deterministic fallback canonical-id generation.
      - Added targeted helper coverage in `ScholardexPublicationCanonicalizationServiceTest` for bridge-fact field copying plus deterministic fallback author/authorship helper behavior.
      - Targeted verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Scoped canonical PIT completed with timeout caveat:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Initial four-class canonical slice result after the helper/contract pass: line 77% (1275/1659), mutation 38% (314/835), test strength 47% (314/662), with 173 no-coverage mutations and 2 timed-out mutants.
      - Follow-up replay/wrapper pass added update-path coverage for affiliation replay, source-link rewrite edge handling, publication `applyCanonicalPublicationFields(...)` idempotence assertions, and default-wrapper / ordered-processing coverage on the canonical rebuild entry points.
      - Updated four-class canonical slice result: line 79% (1312/1659), mutation 41% (341/835), test strength 50% (341/684), with no-coverage down to 151 and the same 2 timed-out mutants. This is a meaningful breadth move, though the remaining gap is still broader pipeline/control-flow behavior rather than cheap helper surface.
      - Next ROI pass deepened the direct publication field-application assertions and added reachable affiliation skip/wrapper coverage, including null-corresponding-author handling, distinct affiliation resolution, and fuller replay field-state checks.
      - Updated four-class canonical slice result again: line 79% (1318/1659), mutation 44% (371/835), test strength 54% (371/687), with no-coverage down to 148 and the same 2 timed-out mutants. This confirms the remaining publication-field survivor cluster was real and largely test-killable without production changes.
      - Combined author/publication ROI pass added direct coverage for publication canonical-id fallback branches, publication author-bridge fallback queuing, author skip/fallback/conflict helper behavior, and stronger author metadata/edge assertions on single-record upsert.
      - Targeted author/publication verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Current two-class author/publication slice result: line 82% (973/1189), mutation 46% (274/594), test strength 54% (274/508), with 86 no-coverage mutations and 1 timed-out mutant. This improved the two dominant canonical classes together, but the remaining gap is still broad enough that one more publication/author pass or a switch to `H49.5f` is a judgment call rather than an obvious next step.
      - Follow-up bridge/flush/recovery pass added direct coverage for author fallback bridge dedupe, author recovery rewrite, publication DOI ambiguity handling, publication source-link caching, and publication edge/conflict queuing.
      - Updated two-class author/publication slice result: line 82% (976/1189), mutation 48% (288/594), test strength 56% (288/510), with no-coverage down to 84 and the same 1 timed-out mutant. This is still moving, but gains are now incremental rather than dramatic.
      - Publication-focused `P3 + P4` pass added direct assertions for deduped publication-author-affiliation edge queuing, conflict metadata materialization, duplicate-key recovery replay into DOI matches, and `flushPublicationFacts(...)` fallback behavior for both insert and update duplicate paths.
      - Targeted publication verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Publication-only PIT improved to: line 92% (651/706), mutation 61% (217/357), test strength 65% (217/336), with 21 no-coverage mutations and 1 timed-out mutant.
      - Updated two-class author/publication slice result after the publication `P3 + P4` pass: line 85% (1010/1189), mutation 51% (304/594), test strength 58% (304/521), with no-coverage down to 73 and the same 1 timed-out mutant. This is a better breadth move than the prior incremental passes and confirms the recovery/flush surface was still materially under-tested.
      - Timeout cleanup pass added a pagination-specific `loadSourceFacts(...)` test with explicit page-by-page stubbing so unexpected extra page fetches fail fast instead of looping indefinitely under mutation.
      - Updated publication-only PIT result after the timeout cleanup: line 92% (653/706), mutation 61% (218/357), test strength 65% (218/337), with no-coverage down to 20 and timed-out mutants reduced to 0.
      - Updated two-class author/publication slice result after the timeout cleanup: line 85% (1012/1189), mutation 51% (305/594), test strength 58% (305/522), with no-coverage down to 72 and timed-out mutants reduced to 0.
      - Author `A2 + A4` pass added direct coverage for synthetic fallback source-link caching, mixed linked/unmatched affiliation bridge behavior, queued author-affiliation edge state/reason shaping, and `flushPendingWrites(...)` recovery/counter behavior across linked/unmatched/conflict/skipped source-link results plus edge-conflict accumulation.
      - Targeted author verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest' -q`
      - Updated two-class author/publication slice result after `A2 + A4`: line 88% (1047/1189), mutation 56% (334/594), test strength 62% (334/538), with no-coverage down to 56 and timed-out mutants remaining at 0. This is the strongest `H49.5e` breadth move so far and confirms the author bridge/flush surface was still materially under-tested.
      - Author `A3` pass strengthened direct conflict/recovery assertions for `saveConflict(...)`, `upsertConflictInContext(...)`, and `recoverAuthorWrite(...)`, including full conflict metadata, existing-conflict reuse, detected-at preservation, candidate list handling, and recovered-author lineage/state propagation.
      - Updated two-class author/publication slice result after `A3`: line 90% (1070/1189), mutation 60% (357/594), test strength 65% (357/553), with no-coverage down to 41 and timed-out mutants still at 0. This is another substantial breadth move and materially reduces the remaining author canonicalization survivor mass.
      - Final publication `P2` bridge cleanup added direct coverage for null/empty author-bridge input, direct-vs-fallback author source-link resolution, and remembered-miss / blank-key `findSourceLink(...)` behavior.
      - Updated two-class author/publication slice result after `P2`: line 90% (1070/1189), mutation 60% (359/594), test strength 65% (359/553), with no-coverage unchanged at 41 and timed-out mutants still at 0. This is a small but real cleanup step; most of the remaining `H49.5e` survivors are now lower-yield helper/control-flow fallout rather than missing core canonicalization scenarios.
      - Production bugfix pass addressed a real author canonicalization defect: both single-record and batch flows now detect divergent canonical ids between an existing author source link and an existing author fact before choosing a canonical target, instead of silently trusting the source link and making the `SOURCE_ID_COLLISION` path unreachable.
      - Added focused regression tests for both collision paths in `ScholardexAuthorCanonicalizationServiceTest`, including the chunk-context cached-link variant used by batch rebuilds.
      - Re-validated the narrowed author/publication slice after the bugfix:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest' -q`
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Updated two-class author/publication slice result after the collision bugfix: line 91% (1097/1201), mutation 62% (375/602), test strength 66% (375/571), with no-coverage down to 31 and timed-out mutants still at 0. This keeps the narrowed `H49.5e` author/publication slice above the H49 threshold while correcting a real data-integrity risk.
      - Follow-up production bugfix pass addressed two additional correctness risks: ambiguous DOI resolution in publication canonicalization now stops reuse instead of merging into an arbitrary sorted match, and affiliation pending source-link rewrite now scopes by both source and `sourceRecordId` so recovery cannot rewrite commands from another source that happen to share the same record id.
      - Added focused regression coverage in `ScholardexPublicationCanonicalizationServiceTest` and `ScholardexAffiliationCanonicalizationServiceTest` for both behaviors, then revalidated touched canonical tests:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Re-ran the four-class canonical slice after the DOI/source-rewrite fixes:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Updated four-class canonical slice result after the DOI/source-rewrite bugfixes: line 89% (1490/1683), mutation 58% (497/853), test strength 64% (497/781), with 72 no-coverage mutations and 1 timed-out mutant remaining. The requested bugfixes are now protected by tests; the remaining timeout was concentrated in `ScholardexCitationCanonicalizationService.loadSourceFacts(...)`.
      - Citation pagination cleanup added a dedicated `loadSourceFacts(...)` full-rescan test with explicit page-0/page-1 stubbing and a hard failure on any unexpected page-2 fetch. This eliminates the last timed-out canonical mutant by turning the pagination contract into an asserted behavior instead of relying on permissive one-page mocks.
      - Revalidated citation tests and reran the four-class canonical slice:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest' -q`
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationServiceBatchScopeTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationServiceTest' -q`
      - Updated four-class canonical slice result after the citation timeout cleanup: line 89% (1492/1683), mutation 58% (498/853), test strength 64% (498/782), with no-coverage down to 71 and timed-out mutants reduced to 0. The slice is now clean from a PIT stability perspective, even though it still sits just below the H49 threshold on rounded mutation/strength numbers.
      - Final citation breadth pass added direct preload/process helper coverage for fallback source-record ids, cached source-link replay, unresolved citing-publication conflicts, source-record collisions, already-known-edge reuse, and `lastRecordKey(...)` fallback behavior.
      - Final four-class canonical slice result: line 92% (1559/1689), mutation 66% (568/857), test strength 70%, with no-coverage down to 41 and timed-out mutants at 0. `H49.5e` now clears the H49 bar on the narrowed canonical slice.
      - Production fixes carried by this hub:
        - author canonicalization now detects source-link / canonical-author divergence in both single-record and batch flows instead of silently trusting the source link
        - publication canonicalization no longer reuses an arbitrary canonical publication when DOI resolution is ambiguous
        - affiliation recovery rewrites pending source-link commands by both `source` and `sourceRecordId`, preventing cross-source contamination

    - [x] `H49.5f` **Materialization and ingestion breadth refinement.** *(completed 2026-04-29)*
      Scope: finish the remaining non-hotspot cleanup in the batch-scoped orchestration classes that feed or invoke the canonical/projection pipeline.
      Target classes:
      - `ScopusCanonicalMaterializationService`
      - `ScopusImportEventIngestionService`
      Focus:
      - batch-scoped orchestration assertions
      - replay / duplicate / skip / error-path behavior
      - downstream invocation contracts and batch-boundary invariants
      Handover:
      - Expanded `ScopusImportEventIngestionServiceTest` to cover `ingestBatch(...)` end to end for null/empty inputs, non-Mongo fallback mode, Mongo prepared-document writes, serialization-error-only batches, and Mongo bulk-write partial-failure handling.
      - Added deterministic helper assertions for payload normalization, SHA-256 hashing, and nanos-to-millis conversion.
      - Expanded `ScopusCanonicalMaterializationServiceTest` with direct orchestration-helper coverage for incremental-option derivation, incremental detection, batch-scoped edge reconciliation, null-options full-maintenance behavior, and failure-outcome observability metrics emission.
      - Targeted verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionServiceTest' -q`
      - Narrowed two-class PIT passed:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService,ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionServiceTest' -q`
      - Final `H49.5f` slice result: line 99% (215/217), mutation 75% (63/84), test strength 75%, no-coverage 0. Remaining survivors are concentrated in low-signal arithmetic and conditional math around aggregate outcome/timing calculations.

    - [x] `H49.5g` **User-defined and package closeout refinement.** *(completed 2026-04-29)*
      Scope: finish the remaining non-hotspot cleanup in the user-defined branch, then re-measure the narrowed slice and decide whether `H49.5` is ready for full-package PIT.
      Target classes:
      - `UserDefinedCanonicalizationService`
      - `UserDefinedFactBuilderService`
      Focus:
      - assertion strengthening around USER_DEFINED lineage, normalized keys, and replay behavior
      - close the residual breadth gap outside the Scopus hotspot pair
      - rerun narrowed-slice PIT after `H49.5e`/`H49.5f`/`H49.5g` progress before attempting full-package PIT
      - `H49.5g` user-defined slice final result: line 97% (548/566), mutation 82% (291/356), test strength 84%, no-coverage 10.

    - [x] `H49.5h` **Core utility closeout (`ScholardexCanonicalBuildCheckpointService` + `CanonicalizationSupport` + `AbstractCanonicalizationService` + `CanonicalBuildOptions`).** *(completed 2026-04-29)*
      Scope: close the lowest-support classes and utility/base abstractions with direct unit coverage and scoped PIT.
      Handover:
      - Added direct tests for checkpoint lifecycle, canonical build option defaults, canonicalization helper utilities, and abstract rebuild/conflict orchestration harness behavior.
      - Targeted verification passed:
        `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.scopus.AbstractCanonicalizationServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupportTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointServiceTest' --tests 'ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptionsTest' -q`
      - Scoped PIT passed:
        `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.AbstractCanonicalizationService,ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointService,ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions' -PpitTargetTests='ro.uvt.pokedex.core.service.importing.scopus.AbstractCanonicalizationServiceTest,ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupportTest,ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointServiceTest,ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptionsTest' -q`
      - Result: line 94% (175/187), mutation 76% (93/123), test strength 79%, no-coverage 5. Remaining survivors are concentrated in lower-signal heartbeat/logging/timing math and branch-shape mutations in the abstract base.

    - Final full-package closeout (all 15 classes):
      - `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.importing.scopus.*' -q`
      - Result: line 94% (3686/3902), mutation 71% (1546/2171), test strength 74% (1546/2081).
      - `H49.5` exit threshold is met at package level; remaining survivors are documented as lower-yield utility/control-flow fallout.

  - [x] `H49.6` **`ro.uvt.pokedex.core.service.importing.wos`** — same shape as scopus importing (10 classes). *(completed 2026-04-30)*
    Baseline: line 81% (2129/2623), mutation 39% (587/1517), test strength 48% (587/1227).
    Scope hint: same playbook as `H49.5` — high line coverage, assertion-strengthening work. Slightly better starting point.
    Handover:
    - Removed `fileResult` dual-tracking from `WosImportEventIngestionService`: eliminated the per-file `ImportProcessingResult` parameter from `processEventFast`, `markIdentitySkipped`, and `shouldSkipGovEvent`; multi-file ingestion methods (`ingestGovernmentAisRisExcel`, `ingestOfficialWosJson`) now capture pre-file snapshots and compute deltas for heartbeat logging; single-file methods use `total` directly.
    - Added targeted parser tests in `GovAisRisImportEventParserTest`: unsupported payload format skip, all-blank identity skip, RIS 2024 metric fallback from c2, RIS 2020 metric fallback from c2, AIS 2021 edition-preservation from parsed category field, AIS 2020 null-quarter handling.
    - Added targeted parser tests in `OfficialWosJsonImportEventParserTest`: unsupported payload format skip, `abbrJournal` fallback when title blank, `journalImpactFactor` key present with null value.
    - Added `rebuildWosProjectionsForJournals` tests in `WosProjectionBuilderServiceTest`: inserts rows and reports no errors (with `atLeast(4)` batch-update verify to kill VoidMethodCallMutator survivors at L386-389), null/empty journal-id early-return paths.
    - Added targeted `WosIdentityResolutionServiceTest` tests: `maybeUpdateAliasesUpdatesUpdatedAtWhenTitleChanges` (kills L251), `multiCandidateBestMatchCallsMaybeUpdateAliasesOnWinner` (kills L165), plus earlier tests for null-sourceContext, non-blank journalId, setIdentityKey on create/single/multi-candidate paths, null title, unchanged-match updatedAt, primaryIssn/eIssn/aliasIssn propagation, blank-title handling.
    - Added 8 targeted `WosCanonicalContractSupportTest` tests: `normalizeTitleFingerprint` null and all-special-char inputs, `normalizeMetricValue` null input, `buildIdentityKey` null token set, `isSourceAllowedForMetric` with null metric/source type, `requiresSplitByEdition` false for single edition, `selectCanonicalOperationalSource` with null left and null metric type.
    - Focused test verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.importing.wos.*' -q`.
    - Final scoped PIT passed: 847/1416 mutations killed (60%), test strength 68%. Both H49 thresholds met.
    - Remaining survivors are classified as low-value or equivalent: logging/heartbeat arithmetic mutations in `WosImportEventIngestionService`; hash-material string-constant and join-delimiter mutations in `WosCanonicalContractSupport`; no-coverage mutations concentrated in lower-value helper paths and the unreachable `sha256Hex` catch block; `setAliasIssns`/`setAlternativeNames` VoidMethodCallMutator mutations in `createIdentity` that are shielded by the test's `persistIdentity` mock always initializing null collections.

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
    Subtasks:
    - [x] `H49.8a` Reporting facade core (highest ROI). *(completed 2026-04-30)*
      Scope: `UserReportFacade`, `GroupReportFacade`, `UserIndicatorResultService`, `ReportScopedIndicatorScoringSupport`, `ReportingComputationSupport`.
      Goal: kill high-volume business-path survivors in report computation/routing and improve confidence on user/group scoring outputs.
      Closeout note: all targeted hotspots improved and high-risk survivors addressed; residual survivors are low-signal conditional/equivalence.
    - [ ] `H49.8b` Scholardex edge + source-link core (highest ROI).
      Scope: `ScholardexSourceLinkService`, `ScholardexEdgeWriterService`, `ScholardexEdgeReconciliationService`, `PublicationEnrichmentLinkerService`.
      Goal: raise mutation kill across canonical linking/edge orchestration where branch errors can introduce identity/link regressions.
    - [ ] `H49.8c` Projection read/lookup services (high ROI, coverage lift).
      Scope: `ScholardexProjectionReadService`, `PostgresReportingLookupFacade`, `ScholardexForumMvcService`, `ScholardexPublicationMvcService`.
      Goal: close low-coverage read/lookup branches and assert stable projection-backed query behavior.
    - [ ] `H49.8d` WoS/Scopus reconciliation + onboarding flows (high ROI).
      Scope: `WosParityReconciliationService`, `WosScholardexOnboardingService`, `ScopusBigBangMigrationService`.
      Goal: strengthen orchestration-path assertions on large reconciliation/onboarding services with high survivor density.
    - [ ] `H49.8e` Admin/group/user operational facades (medium ROI).
      Scope: `AdminCatalogFacade`, `ConflictOperationsFacade`, `PublicationWizardFacade`, `SuspiciousAuthorshipTriageService`, `UserPublicationFacade`, `UserScopusTaskFacade`.
      Goal: improve branch and contract coverage on service-layer facades frequently exercised by admin/user workflows.
    - [ ] `H49.8f` PostgreSQL projection/refresh pipeline (medium ROI).
      Scope: `JdbcPostgresReportingProjectionService`, `JdbcPostgresMaterializedViewRefreshService`, `IndexMaintenanceSupport`, `GeneralInitializationService`.
      Goal: increase kill rate in projection refresh/rebuild paths and operational guard branches.
    - [ ] `H49.8g` Read-port no-coverage cluster (closure-focused).
      Scope: `PostgresScholardexAdminReadPort`, `PostgresScholardexProjectionReadPort`, `PostgresWosRankingDetailsReadPort`, `PostgresWosCategoryReadPort`, `PostgresReadCutoverGuard`, `RequestYearRangeSupport`, `ResearcherAuthorLookupService`.
      Goal: remove 0%-low coverage outliers via targeted contract/mapper tests and focused PIT slices.
    - [ ] `H49.8h` Residual sweep + survivor triage.
      Scope: package-level survivors remaining after `H49.8a-g`.
      Goal: classify residual mutants into bug-risk vs low-signal/equivalent buckets, address high-ROI remainder, and close `H49.8`.

  - [x] `H49.9` **`ro.uvt.pokedex.core.service.application.model`** — application model classes (5 classes). *(completed 2026-04-30)*
    Baseline: line 69% (18/26), mutation 26% (8/31), test strength 44% (8/18).
    Handover:
    - Added `ApplicationModelContractTest` with targeted assertions for `AdminDashboardViewModel`, `AdminOperationStatus`, `ScholardexCitationsView`, and `WosEnrichmentRunSummaryDto`.
    - Covered key behavior branches: error-list presence, never-run vs has-run status, citations pagination boundaries (`hasPrevious`/`hasNext`), and `WosEnrichmentRunSummaryDto` derivation rules (`fromStep` null/default path, negative-duration clamp, non-negative preserved count, canonical `notRun` payload).
    - Focused verification passed: `./gradlew test --tests 'ro.uvt.pokedex.core.service.application.model.ApplicationModelContractTest' -q`.
    - Scoped PIT passed: `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.application.model.*' -PpitTargetTests='ro.uvt.pokedex.core.service.application.model.*' -q`.
    - Post-remediation: line 85% (22/26), mutation 90% (28/31), test strength 100% (28/28 covered mutants). Remaining 3 mutants are `NO_COVERAGE` null-return accessor mutants.

  - [x] `H49.10` **`ro.uvt.pokedex.core.service.reporting`** — middle of the pack (21 classes). *(completed 2026-04-30)*
    Baseline: line 57% (1047/1853), mutation 34% (433/1272), test strength 56% (433/767).
    Scope hint: more balanced than the importing packages — both line coverage and test strength have room. Mix of new tests and stronger assertions on existing ones.
    Handover:
    - Package-level PIT reassessment passed with `./gradlew pitest -PpitTargetClasses='ro.uvt.pokedex.core.service.reporting.*' -q`.
    - Post-remediation package metrics: line 93% (1668/1795), mutation 74% (911/1224), test strength 77% (911/1190), no-coverage mutants 34.
    - Final class-level hardening pass on `ComputerScienceConferenceScoringService` reached line 93% (591/637), mutation 72% (353/488), and reduced boundary survivors (ConditionalsBoundaryMutator kills improved from 12/35 to 17/35).
