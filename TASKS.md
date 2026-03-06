# Project Recovery Tasks (High-Level)

Objective: raise runtime functional quality from baseline-safe behavior to production-grade correctness and resilience.

Done history moved to `TASKS-done.md`.

## Backlog

- [x] `H11` Functional contract hardening and null-safety normalization.
  Goal: eliminate ambiguous/null-based success/failure paths in core services/controllers and replace them with deterministic contracts.
  Deliverable: audited and upgraded service/controller contracts (`Optional`/typed results), plus targeted regression tests for conflict/not-found/error semantics.
  Exit criteria: high-impact runtime paths no longer rely on `return null` or silent fallbacks for control flow, and failure semantics are deterministic and test-covered.
  Status: completed on 2026-03-04.
  Note: migrated core nullable contracts in-scope (`UserService#createUser` overloads, `GlobalControllerAdvice#currentUser`, `CacheService#getCachedTopRankings`, `PublicationWizardFacade#resolveForumId`), added API `409` duplicate-user mapping and MVC redirect+flash wizard failure contract, and extended regression/guardrail coverage.

- [x] `H12` External integration and import correctness uplift.
  Goal: harden external data acquisition/import flows so parser, mapping, and partial-failure behavior are explicit and reliable.
  Deliverable: completed/parity-checked Scopus/import processing paths, strict mapping validation, and robust retry/error contracts for scheduler/import jobs.
  Exit criteria: external-data and import workflows have deterministic outcomes (including degraded/failure paths), with integration tests covering critical mapping and error scenarios.
  Status: completed on 2026-03-04.
  Note: added bounded retry/backoff metadata and transitions for Scopus tasks, removed scheduler forced reimport/date hack paths, hardened Scopus JSON mapping with skip+warn partial ingest behavior, added deterministic importer summary accounting (Ranking/CORE/URAP), and introduced `npm run verify-h12-integrations`.

- [ ] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical user/admin business workflows end-to-end under realistic conditions.
  Deliverable: focused high-value functional test suite (multi-step user/admin/report/export flows) with deterministic fixtures and clear pass/fail contracts.
  Exit criteria: top business workflows are validated across success and failure paths, and functional regressions are caught before merge by repeatable automated checks.

- [ ] `H14` WoS Approach 3 implementation (immutable ingestion ledger + rebuildable views).
  Goal: implement official WoS extracted data + AIS/RIS ingestion into replayable immutable events, then serve ranking/report reads from materialized views instead of cache-heavy full scans.
  Deliverable: production-ready import, transform, projection, cutover, and validation pipeline with rollback support.
  Exit criteria: ranking/reporting paths no longer depend on `CacheService` as primary lookup for WoS data, and parity/performance gates pass.
  Subtasks:
  - [x] `H14.1` Canonical data contracts and source policy.
    Deliverable: decision-locked schema/contracts for `wos.import_events`, `wos.journal_identity`, `wos.metric_facts`, `wos.category_facts`, `wos.ranking_view`, `wos.scoring_view`, including first-class edition fields.
    Exit criteria: field-level contract documented in code/docs, including lineage fields and sentinel normalization policy (`-999` -> missing).
    Edition contract: store `editionRaw` + `editionNormalized` (`SCIE | SSCI | AHCI | ESCI | OTHER | UNKNOWN`) and `categoryNameCanonical` separately (no coupled `category - index` key storage).
    Completion gate: H14.2/H14.4/H14.5 implementation cannot start until schema spec + code stubs are merged.
  - [x] `H14.2` Identity resolution and alias rules.
    Deliverable: deterministic ISSN/eISSN/title normalization and merge/split policy with conflict logging.
    Exit criteria: same input produces stable `journalId` mapping across replays; unresolved collisions are recorded deterministically.
  - [x] `H14.3` Immutable ingestion ledger implementation.
    Deliverable: importer that writes source-versioned records to `wos.import_events` for AIS/RIS government files and `data/wos-json-1997-2019`.
    Exit criteria: replayable event records persisted with checksum/source metadata; no direct write to read views from raw import.
  - [x] `H14.4` Parser adapters for all WoS inputs.
    Deliverable: robust adapters for year-variant AIS/RIS Excel formats and official WoS extracted JSON files (SCIE/SSCI).
    Exit criteria: parser test matrix covers representative years and malformed rows; import summary includes processed/imported/skipped/errors with samples.
    Edition normalization rules: `SSCI` token -> `SSCI`; `SCIE` token or `SCIENCE` token -> `SCIE`; unknown/non-target tokens -> `OTHER` or `UNKNOWN`; bundled `SCIE+SSCI` source values emit two normalized facts with shared lineage.
    Quick pass notes from `data/loaded` (must be handled in parser adapters):
    - AIS `2011`: 3 cols (`title`,`issn`,`AIS`), AIS can be blank.
    - AIS `2012-2013`: 5 cols (`title`,`issn`,`AIS`,`domain`,`citation index`), index tokens seen as `SCIENCE/science` (normalize to `SCIE`).
    - AIS `2014-2017`: 4 cols (`nr`,`full title`,`issn`,`AIS`) where AIS is frequently stored as STRING numeric text.
    - AIS `2018-2019`: 6 cols (`title`,`issn`,`AIS`,`index`,`category`,`quartile`) with quartile numeric (1..4).
    - AIS `2020`: 7 cols (`title`,`issn`,`eissn`,`AIS`,`index`,`category`,`quartile`) and title cells can be FORMULA.
    - AIS `2021`: 7 cols; category can be bundled text (`ACOUSTICS - SCIE`), quartile as `Q1..Q4` string.
    - AIS `2022`: 6 cols; AIS may be string-formatted decimals (`\"2.070\"`), category already bundled `category - edition`.
    - AIS `2023`: 6 cols with separate category + edition (`SCIE/SSCI/ESCI`); ISSN may be `N/A`; AIS appears as string-formatted decimal.
    - RIS `2019`: 3 cols (`title`,`issn`,`RIS`), no eISSN.
    - RIS `2020-2023`: 4 cols (`title`,`issn`,`eissn`,`RIS`), RIS numeric; text variations in header diacritics/newlines.
    - General normalization requirements observed in files: blank/`N/A` ISSN or eISSN, mixed numeric-vs-string metric cells, case/format variance in edition/index labels, and newline/diacritic header variants.
  - [x] `H14.5` Canonical fact builders.
    Deliverable: deterministic transformation from `import_events` to `wos.metric_facts` and `wos.category_facts`.
    Exit criteria: transformation is idempotent, replay-safe, and preserves lineage back to source event IDs.
    Fact uniqueness/storage rule: persist one fact per `(journalId, year, categoryNameCanonical, editionNormalized, metricType)` and preserve provenance (`sourceFile`, `sourceRow/item`, `sourceVersion`, `editionRaw`).
  - [x] `H14.6` IF policy enforcement.
    Deliverable: remove operational JIF government ingestion path; keep IF only from official WoS extracted data facts.
    Exit criteria: no active import branch consumes JIF gov files; IF facts present only with explicit official-extract lineage.
    Status: completed on 2026-03-06.
    Note: active import now scans only AIS/RIS gov files, legacy JIF helper path is deprecated/no-op, and fact builder hard-skips IF from non-official sources.
  - [x] `H14.7` Materialized projection builders.
    Deliverable: build jobs for `wos.ranking_view` (UI/search/paging) and `wos.scoring_view` (reporting lookups).
    Exit criteria: projections are rebuildable from facts, versioned, and can be regenerated without manual patching.
    Status: completed on 2026-03-06.
    Note: added manual full-rebuild projection pipeline with shared `buildVersion`/`buildAt` stamping and admin-triggered rebuild action.
  - [x] `H14.8` Mongo indexing and query plan hardening.
    Deliverable: compound indexes for fact/projection access patterns.
    Exit criteria: `/api/rankings/wos` and scoring lookups execute with index-backed query plans under target dataset size.
    Status: completed on 2026-03-06.
    Note: added manual WoS index maintenance action/service with deterministic named-index contract, verification reporting, and ranking-view normalized fields for prefix-search index readiness.
  - [x] `H14.9` API read-path cutover.
    Deliverable: `/api/rankings/wos` and related WoS list/detail reads backed by projections rather than `CacheService#getAllRankings`.
    Exit criteria: API contracts unchanged; response parity and pagination/sort/filter behavior preserved.
    Edition filter rule: operational API reads must filter to `editionNormalized IN (SCIE, SSCI)`; `OTHER/UNKNOWN` retained only for traceability.
  - [x] `H14.10` Reporting lookup cutover.
    Deliverable: reporting lookup port backed by `wos.scoring_view`/facts for AIS/RIS (+ IF fallback policy where still allowed).
    Exit criteria: report computations avoid full WoS cache scans and match baseline score outputs for AIS/RIS.
    Edition filter rule: operational scoring/reporting reads use only `SCIE/SSCI` normalized editions.
    Status: completed on 2026-03-06.
    Note: introduced `@Primary` projection/fact-backed `ReportingLookupPort` for WoS methods (`getRankingsByIssn`, `getTopRankings`) with SCIE/SSCI filtering and no WoS cache fallback, while non-WoS lookup methods remain cache-backed until H14.11.
  - [x] `H14.11` CacheService role minimization.
    Deliverable: remove WoS primary lookup responsibilities from `CacheService`; keep optional short-lived aggregate memoization only.
    Exit criteria: no WoS ranking/report primary read path depends on cache preloading.
    Status: completed on 2026-03-06.
    Note: WoS ranking/top-count cache methods were removed from `CacheService`, legacy WoS maintenance operations were disabled, and operational WoS reporting/category reads now use canonical facts/projections.
  - [x] `H14.12` Big-bang migration tooling (admin-triggered only).
    Deliverable: admin-only full-run migration workflow (staging first) from `/admin/rankings/wos`: ingest events -> build facts -> build projections -> verify.
    Exit criteria: admin-triggered dry-run and full-run outputs include reconciliation metrics, deterministic rerun behavior, and clear operator-visible summaries/errors.
    Notes:
    - Execution path is admin maintenance action only (no separate CLI/public runner contract).
    - Workflow must use existing canonical services and produce deterministic per-step accounting.
    - Security: action is restricted to `PLATFORM_ADMIN`.
  - [x] `H14.13` Parity and reconciliation suite.
    Deliverable: automated checks for counts, sampled journal timelines, category/rank consistency, and score parity.
    Exit criteria: parity gates pass against agreed baseline before production cutover.
    Edition reconciliation checks: counts by normalized edition, bundled-record split counts, and deterministic `SCIENCE -> SCIE` normalization.
    Status: completed on 2026-03-06.
    Note: added baseline-driven reconciliation service with strict mismatch gating, migration verification parity fields, admin summary parity visibility, and targeted parity/migration/controller test coverage.
  - [x] `H14.14` Performance and resilience validation.
    Deliverable: p95 latency benchmarks for `/api/rankings/wos` and report scoring lookups, plus rebuild-time budgets.
    Exit criteria: target SLOs met and documented; failure-mode tests validated (partial import, replay after interruption).
    Status: dropped on 2026-03-06 (out of scope for now).
  - [x] `H14.15` Rollback/cutover playbook.
    Deliverable: operational runbook for cutover, rollback, and replay recovery.
    Exit criteria: staged rehearsal completed with documented timings and recovery steps.
    Status: dropped on 2026-03-06 (out of scope for now).
  - [x] `H14.16` Operational support closure for `Indicator.Strategy.IMPACT_FACTOR`.
    Deliverable: keep `IMPACT_FACTOR` fully supported using only official WoS extracted data lineage (including future key-based imports), with explicit source-policy behavior and telemetry.
    Exit criteria: runtime behavior is deterministic when IF missing, IF ingestion uses official WoS extracted sources only, and usage remains observable.
    Policy: `IMPACT_FACTOR` stays operationally supported and is not deprecated.
    Status: completed on 2026-03-06.
    Note: IF source-policy guard is enforced at fact-build time (`OFFICIAL_WOS_EXTRACT` only), IF scoring keeps deterministic empty-result behavior when missing, and telemetry/logging was added for IF requests/success/missing and policy skips.
  
  Test cases to track in H14:
  - [x] Government row with index `SCIENCE` maps to `editionNormalized=SCIE`.
  - [ ] Government/WoS value containing both `SCIE` and `SSCI` yields two facts.
  - [x] Missing/invalid edition text stored as `UNKNOWN` with raw source preserved.
  - [x] Operational ranking/report queries include only `SCIE/SSCI` and ignore `OTHER/UNKNOWN`.
  - [ ] Re-run of ingestion produces identical edition-normalization outputs.
  - [ ] Existing AIS/RIS/CNFIS scoring outputs remain parity-stable for representative fixtures.
  - [x] Admin-triggered H14.12 run executes full chain (ingest -> facts -> projections -> verify) with deterministic summary and no side effects for unauthorized users.
  - [x] IF from official WoS extracted lineage is accepted; non-official IF is skipped deterministically.
  - [x] `IMPACT_FACTOR` scoring emits deterministic empty result when IF data is missing (no fallback substitution).

  Assumptions/defaults for H14 edition handling:
  - [x] Keep raw edition values persisted for audit; operationally filter to `SCIE/SSCI`.
  - [x] Official WoS extracted JSON (`data/wos-json-1997-2019`) and government AIS/RIS are both authoritative inputs; normalization resolves inconsistencies.
  - [x] Edition is first-class data in Approach 3, not encoded in category strings.
  - [x] Keep external endpoint shapes unchanged for this migration; changes are internal.
  - [x] Combined category-edition keys remain in runtime compatibility paths for now; de-coupling is deferred.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
