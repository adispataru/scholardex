# H06-S03 Query Consistency Findings

Date: 2026-03-03  
Scope: repository query semantics, callsite assumptions, and consistency risks in service/view flows.

## 1. Method

- Reviewed repository method contracts under `core/repository/**`.
- Mapped query callsites in `core/service/**`, `core/view/**`, and `core/controller/**`.
- Focused on: ordering assumptions, identity semantics, case sensitivity, uniqueness guarantees, and cache/query alignment.

## 2. Findings (Severity-Ordered)

| ID | Severity | Finding | Evidence | Impact | Initial direction |
|---|---|---|---|---|---|
| `Q-H06-01` | high | **Ranking cache key semantics drift (ISSN cache populated with ranking IDs)** | `CacheService.cacheRankings()` stores `rankingCacheByIssn.put(r.getId(), List.of(r))` while lookup path is `getCachedRankingsByIssn(issn)` -> `findAllByIssn` (`src/main/java/ro/uvt/pokedex/core/service/CacheService.java:120,144-145`). | Cache contents are semantically inconsistent; warm cache may not represent ISSN lookup intent and can mask logic errors/perf issues. | Canonicalize keying to ISSN/EISSN or rename cache to reflect ID semantics and split paths. |
| `Q-H06-02` | high | **No DB-level uniqueness contract for citation pair (`citedId`,`citingId`)** | Duplicate prevention is application-side via `findByCitedIdAndCitingId(...).isEmpty()` before insert in both importer and scheduler (`ScopusCitationRepository`, `ScopusDataService`, `ScopusUpdateScheduler`). No compound unique index on model. | Concurrent writers can still create duplicates; count-based metrics and exports can drift. | Add compound unique index + keep app check as defense in depth. |
| `Q-H06-03` | high | **Unsafe string-date query/filter assumptions** | Multiple flows parse year using `Integer.parseInt(...substring(0,4))` (`UserReportFacade`, `GroupCnfisExportFacade`, `AbstractForumScoringService`, `AbstractWoSForumScoringService`, `ActivityInstance#getYear`). | Malformed/null dates can fail entire report/export query paths; consistency depends on clean source data only. | Introduce normalized year extraction helper and replace ad-hoc parsing in query filters. |
| `Q-H06-04` | medium-high | **Mixed publication identity query semantics (`id` vs `eid` vs `doi`)** | Repository exposes `findByEid`, `findByDoi`, while many flows fetch by Mongo `id`; importer comments show historical ID strategy toggles. | Identity mismatches can create subtle duplicates/lookup misses across import/update/export paths. | Define canonical identity contract and audit all query entrypoints against it. |
| `Q-H06-05` | medium-high | **Case-sensitivity/query-normalization drift in textual search** | `findByTitleContains` (no ignore-case contract) used for admin publication search; other areas use ignore-case variants (`findByNameIgnoreCase`, `findAllByNameIgnoreCase`). | Search behavior differs by entity/path; user expectations and results consistency drift across screens. | Standardize case-insensitive search policy for user-facing text queries. |
| `Q-H06-06` | medium-high | **Result ordering is often implicit/unstable** | High-impact list queries rely on repository default order (`findAllByAuthorsIn`, `findAllByCitedIdIn`, `findByTitleContains`, `findAllByAffiliationsContaining`) with no explicit sort in repository or callsite. | Same query can produce unstable ordering, affecting UI determinism, exports, and regression assertions. | Add explicit sort contracts at repository or service layer for user-visible outputs. |
| `Q-H06-07` | medium | **Potential duplicate amplification in author-based publication aggregation** | User publication flow iterates authors and appends `findAllByAuthorsContaining` results without dedupe (`UserPublicationFacade`). | If multiple matched authors belong to same publication, entries can duplicate in output and derived counts. | Dedupe by publication ID before downstream aggregation. |
| `Q-H06-08` | medium | **Repository method naming drift/typo reduces query API clarity** | `RankingRepository#findAllByeIssn` naming typo; nearby API uses `findAllByIssn`. | Increases maintenance error risk and encourages accidental misuse/duplication of query methods. | Normalize method names and remove/replace ambiguous variants. |
| `Q-H06-09` | medium | **Forum export dedupe relies on ad-hoc sentinel values** | `ForumExportFacade` dedupes by `issn` and special-cases `"null-"` string. | Query result consistency depends on data quirks rather than typed null/empty handling; export rows can drift. | Replace sentinel checks with explicit null/blank normalization policy. |

## 3. Coverage and Gaps

- Current integration coverage under H04 validates selected repository contracts:
  - `ScopusPublicationRepositoryIntegrationTest`
  - `ScopusCitationRepositoryIntegrationTest`
- Remaining high-risk query semantics in this document are mostly unguarded by repository-level integration tests (cache semantics, ordering contracts, search normalization, dedupe rules).

## 4. Priority Next-Look List (for H06-S04/S05)

1. `Q-H06-01` ranking cache/query key alignment contract.
2. `Q-H06-02` citation pair uniqueness contract (index + characterization).
3. `Q-H06-03` shared date/year extraction and validation contract.
4. `Q-H06-04` canonical publication identity query policy.
5. `Q-H06-06` explicit ordering policy for UI/export-facing queries.

## 5. Open Decisions Seeded for H06-S04

- Should ranking lookups be canonicalized to `(issn,eIssn,id)` multi-key contract with strict priority?
- Is Mongo unique index acceptable for existing citation data (requires one-time dedupe/migration)?
- Where should ordering be encoded: repository method signatures vs service-level sorting?
- Should `findByTitleContains` become ignore-case and token-normalized for admin search?

## 6. H06-S05 Status Update

- `Q-H06-01`: addressed in `H06-S05`.
  - `CacheService.cacheRankings()` now keys ISSN cache using normalized `issn`/`eIssn` values instead of ranking IDs.
  - `getCachedRankingsByIssn` now normalizes input and merges repository fallback from both `findAllByIssn` and `findAllByeIssn`, deduped by ranking ID.
- `Q-H06-03`: partially addressed in `H06-S05`.
  - CNFIS year-filter paths now use shared safe parsing (`PersistenceYearSupport`) in:
    - `UserReportFacade#buildUserCnfisWorkbookExport`
    - `GroupCnfisExportFacade#filterPublicationsByYear`
  - Malformed/null years are skipped with warning, avoiding export-path crashes.
- `Q-H06-02`: resolved in `B01` (2026-03-04).
  - Added two-phase rollout migration (`off|report|apply`) with deterministic dedupe (keep lowest `id`) and runtime unique index enforcement for (`citedId`, `citingId`).
  - Added migration unit/integration tests and duplicate-key rejection regression coverage.
- `Q-H06-03`: resolved in `B04` (2026-03-04).
  - Rolled out `PersistenceYearSupport` usage to remaining high-impact scoring, grouping, and export rendering paths:
    - `AbstractForumScoringService`
    - `AbstractWoSForumScoringService`
    - `CNFISScoringService2025`
    - `GroupReportFacade`
    - `AdminInstitutionReportFacade`
    - `UserReportFacade`
    - `CNFISReportExportService`
    - `AdminViewController`
    - `AdminGroupController`
  - Invalid/malformed year values now follow skip+warn or blank-cell export behavior rather than crash-prone parsing.
- `Q-H06-04`, `Q-H06-06`, `Q-H06-07`: resolved in `B05` (2026-03-04).
  - Identity semantics were normalized in user publication edit/save flow to canonical Mongo `id` naming and lookup (`findById`), removing misleading `eid` variable semantics.
  - Deterministic ordering contract (`coverDate desc`, `title asc`, `id asc`) was applied across high-impact publication/citation list paths:
    - `UserPublicationFacade` (publications + citations)
    - `AdminScopusFacade` (search + citations)
    - `AdminInstitutionReportFacade` (publication list + year buckets + citation map rows)
    - `GroupReportFacade` (publication list + year buckets)
    - `GroupExportFacade` (CSV publication rows)
  - Author-based publication aggregation in `UserPublicationFacade` now dedupes by publication ID before h-index/citation/map assembly.
