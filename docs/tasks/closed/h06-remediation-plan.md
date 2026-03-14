# H06-S06 Remediation Plan

Date: 2026-03-03  
Status: active phased plan for persistence consistency remediation.

## 1. Objective

Sequence persistence cleanup by blast radius and effort, while locking in guardrails that prevent reintroduction of known high-risk drift patterns.

## 2. Risk-to-Slice Mapping

| Slice | Target risks | Scope | Expected impact |
|---|---|---|---|
| `R1` | `Q-H06-02` | Citation uniqueness hardening (DB index + safe migration). | Prevent duplicate citation edges under concurrency. |
| `R2` | `Q-H06-03` (remaining), `C3` | Expand safe year parsing to remaining query/filter/grouping paths. | Eliminate malformed-date crash class in report/search pipelines. |
| `R3` | `Q-H06-04`, `Q-H06-06`, `Q-H06-07` | Canonical identity and deterministic ordering in high-impact list/export flows; dedupe author-aggregated publication queries. | Stabilize result semantics and reduce duplicate/lookup drift. |
| `R4` | `Q-H06-05`, `Q-H06-08`, `Q-H06-09`, `D-H06-03` | Text-search normalization policy rollout, typo’d query API cleanup, forum export dedupe normalization, collection naming migration plan. | Improve consistency/readability and remove low/medium persistence debt. |

## 3. Decision-Complete Slice Details

## R1: Citation Uniqueness Hardening (`P0`)

- Add Mongo compound unique index for citation pair (`citedId`, `citingId`) on `Citation`.
- Pre-migration step:
  - detect duplicates grouped by pair;
  - keep one canonical record per pair;
  - archive/delete extras with migration log.
- Keep application-side guard (`findByCitedIdAndCitingId`) as defense in depth.
- Regression checks:
  - integration test for duplicate-write rejection at DB layer.

Exit criteria:
- duplicate pair inserts are rejected at persistence layer;
- existing flows remain behavior-compatible for non-duplicate inserts.

## R2: Full Year Parsing Contract Rollout (`P1`)

- Expand `PersistenceYearSupport.extractYear(...)` usage to remaining high-impact paths still using raw parsing.
- Priority targets (current evidence):
  - `AbstractForumScoringService`
  - `AbstractWoSForumScoringService`
  - `GroupReportFacade`
  - `AdminInstitutionReportFacade`
  - `ActivityInstance#getYear` follow-up decision (safe parse vs type migration).
- Policy remains: invalid year input => skip record + warn (no crash).

Exit criteria:
- no raw `substring(0,4)` year filtering/grouping in high-impact report/export paths;
- tests characterize skip+warn behavior for malformed dates.

Status update (2026-03-04):
- `R2` implemented via `B04`.
- Safe year extraction was rolled out across high-impact scoring, grouping, and export-rendering paths using `PersistenceYearSupport`.
- `ActivityInstance` now exposes `getYearOptional()` for safe migration while retaining legacy `getYear()` compatibility.
- `verify-h06-persistence` guardrails were expanded to block raw year parsing regressions in remediated hotspots.

## R3: Identity + Ordering + Duplicate Aggregation (`P1`)

- Identity contract rollout:
  - audit and normalize entrypoints using `id` vs `eid` vs `doi` per `C2`.
- Deterministic ordering rollout:
  - apply explicit sorting for user-visible list/export/report outputs where repository ordering is implicit.
- Duplicate aggregation fix:
  - dedupe publication results in author-iterative aggregation paths (notably user publication flow), keyed by publication id.

Exit criteria:
- key user/admin outputs have deterministic order contracts;
- no duplicate amplification in author-based aggregation flows;
- identity lookups use contract-consistent repository methods.

Status update (2026-03-04):
- `R3` implemented via `B05`.
- Identity normalization:
  - user publication edit/save path now uses canonical-id naming (`publicationId`) and `findById` semantics.
- Deterministic ordering rollout completed for high-impact publication/citation outputs in:
  - `UserPublicationFacade`
  - `AdminScopusFacade`
  - `AdminInstitutionReportFacade`
  - `GroupReportFacade`
  - `GroupExportFacade`
- Duplicate amplification remediation completed:
  - user author-iterative publication aggregation now dedupes by publication ID before score/count/map derivations.
- `verify-h06-persistence` guardrails expanded with `R3` checks (dedupe + identity + ordered search path).

## R4: Consistency Cleanup and Namespace Hygiene (`P2`)

- Search normalization:
  - explicitly adopt case-insensitive policy for user-facing publication search unless exact match is required.
- Query API cleanup:
  - retire typo drift (`findAllByeIssn`) through compatibility step then normalized method naming.
- Forum export dedupe normalization:
  - replace sentinel-string checks with null/blank normalization.
- Collection naming migration plan:
  - address `schodardex` typo to `scholardex` with backward-compatible migration strategy.

Exit criteria:
- query API and naming drift items have concrete closure path and implementation evidence.

Status update (2026-03-04):
- `R4` completed via `B10 + B10A`:
  - admin publication title search normalized to case-insensitive contains (`ContainingIgnoreCase`);
  - forum export dedupe normalized (ISSN -> eISSN -> sourceId fallback), sentinel-string reliance removed;
  - task collection namespace cut over from `schodardex.tasks.*` to `scholardex.tasks.*` with startup-gated report/apply migration support;
  - typo’d repository method `findAllByeIssn` retired; canonical query API is `findAllByEIssn` with explicit `@Query` mapping.

## 4. Guardrails (S06)

### 4.1 Automated lightweight verification

- New command: `npm run verify-h06-persistence`
- Enforced checks:
  - CNFIS year-filter paths must keep `PersistenceYearSupport.extractYear(...)`.
  - CNFIS year-filter methods must not regress to `substring(0, 4)` parsing.
  - `CacheService` ISSN cache must not be polluted by ranking id keys.
  - `getCachedRankingsByIssn` must keep dual fallback (`findAllByIssn` + `findAllByEIssn`) with dedupe semantics.
  - `findAllByeIssn` is retired: any usage is blocked.

### 4.2 Review checklist addition

For persistence-touching changes:
1. Run `npm run verify-h06-persistence`.
2. Run targeted persistence tests for touched query/aggregation paths.
3. If changing indexes or collections, include migration/rollback notes.

## 5. Recommended Execution Order

1. `R1` (index + migration safety)  
2. `R2` (remaining date safety)  
3. `R3` (identity/order/dedupe semantics)  
4. `R4` (normalization and cleanup)

## 6. H06-S06 Completion Notes

- Phased remediation slices are now explicit and ordered (`R1..R4`).
- Lightweight persistence regression guard command is in place (`verify-h06-persistence`).
- Remaining H06 closeout work moves to `H06-S07` (adoption notes + archive/status updates).

## 7. H06-S07 Closeout and Handoff

Status date: 2026-03-03

- H06 baseline is complete for planning and execution:
  - architecture/data ownership map: `docs/tasks/closed/h06-persistence-map.md`
  - schema drift inventory: `docs/tasks/closed/h06-schema-drift-inventory.md`
  - query consistency findings: `docs/tasks/closed/h06-query-consistency-findings.md`
  - canonical contracts: `docs/tasks/closed/h06-persistence-contracts.md`
  - phased remediation plan: `docs/tasks/closed/h06-remediation-plan.md`
- Active persistence guardrail command:
  - `npm run verify-h06-persistence`
- Execution handoff to `H07`:
  - treat `C1..C10` persistence contracts as fixed inputs for validation/error/security work,
  - prioritize `R1` when H06 remediation resumes (`Q-H06-02` DB-level citation uniqueness),
  - keep `R2..R4` sequencing unchanged unless a new high-severity incident requires reprioritization.

## 8. B01 Status Update (2026-03-04)

- `R1` implementation delivered with two-phase rollout gate:
  - properties:
    - `h06.citation.uniqueness.mode=off|report|apply`
    - `h06.citation.uniqueness.fail-on-duplicates`
    - `h06.citation.uniqueness.log-sample-size`
  - service: `CitationUniquenessMigrationService` (scan, dedupe keep-lowest-id, ensure unique index, verify).
  - runner: `CitationUniquenessMigrationRunner` (report/apply behavior).
- Persistence hardening:
  - unique index `uniq_cited_citing` on (`citedId`, `citingId`) enforced at runtime.
  - app-level duplicate guard remains in place.
- Test coverage added:
  - `CitationUniquenessMigrationServiceTest`
  - `CitationUniquenessMigrationServiceIntegrationTest`
  - `ScopusCitationRepositoryIntegrationTest` unique-index rejection scenario.
