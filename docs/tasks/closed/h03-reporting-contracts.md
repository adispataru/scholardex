# H03-S03 Reporting and Service Characterization Contracts
Status: active indexed source-of-truth document.

Date: 2026-03-03  
Schema: `docs/tasks/closed/h03-contract-schema.md`  
Scope: highest-risk reporting/service behaviors tied to H03 top flows.

## 1. Contract Set Summary

| Contract ID | Flow ID | Priority | Area | Current status |
|---|---|---|---|---|
| C-F02-01 | F02 | P0 | CNFIS 2025 scoring rule behavior | partial |
| C-F02-02 | F02 | P0 | User CNFIS workbook export orchestration | partial |
| C-F03-01 | F03 | P0 | Group CNFIS workbook/zip orchestration | partial |
| C-F01-01 | F01 | P0 | CS scoring dispatch behavior used by indicator/report flows | partial |
| C-F04-01 | F04 | P0 | Ranking maintenance mutation behavior | partial |

---

### Contract ID: C-F02-01

- Flow ID: `F02`
- Priority: `P0`
- Entrypoint: `CNFISScoringService2025#getReport(Publication, Domain)`
- Auth assumptions: N/A (domain service level, invoked by authenticated export flows).
- Inputs:
  - `Publication` with forum id, subtype/scopusSubtype, coverDate, authors, WoS id.
  - `Domain` (commonly `ALL` for exports).
  - ranking/forum/author lookups via `ReportingLookupPort`.
- Output contract:
  - Returns non-null `CNFISReport2025`.
  - Sets CNFIS flags/counters per canonical rule spec.
- Side effects: none in scorer (read-only lookup behavior).
- Invariants:
  - subtype resolution must prefer `scopusSubtype`, fallback to `subtype`, normalized trim+lowercase.
  - `cp/ch` proceedings flags must follow current IEEE/Lecture Notes + WoS-id rules.
  - malformed category/year inputs must not throw.
  - author counters must be populated from publication authors + university-author lookup set.
- Known edge cases:
  - null/blank categories.
  - category without `-`.
  - malformed coverDate.
  - blank subtype fields.
- Zone path: `Z2 User/Group facade -> Z3 CNFISScoringService2025 -> Z3 ReportingLookupPort (adapter in Z2) -> Z4-backed data`
- Current tests:
  - `CNFISScoringService2025Test` (`covered` for subtype precedence/fallback, proceedings flags, malformed category/date handling).
- Planned tests:
  - add explicit domain filtering characterization (`ALL` vs specific domain category list) (`missing`).
  - add null-forum lookup defensive behavior test (`missing`).
- Owner: Reporting Domain Owner.

### Contract ID: C-F02-02

- Flow ID: `F02`
- Priority: `P0`
- Entrypoint: `UserReportFacade#buildUserCnfisWorkbookExport(String, int, int)` and `#buildLegacyUserCnfisWorkbookExport(String)`
- Auth assumptions:
  - user email must resolve to existing user and linked researcher for success path.
  - missing user/researcher yields non-success status (`unauthorized`/`not_found` semantics via result object).
- Inputs:
  - `userEmail`, `startYear`, `endYear` (2025 export).
  - publication/forum lookup repositories and CNFIS services.
- Output contract:
  - result status and workbook metadata must match current behavior.
  - 2025 export filename/content-type must match current fixed values.
  - legacy export route semantics preserved (distinct template/workbook path).
- Side effects:
  - WoS enrichment + publication save in 2025 path.
- Invariants:
  - year filter must include only publications in requested interval.
  - export workbook generation must use current CNFIS report pipeline.
  - legacy route remains available and facade-backed.
- Known edge cases:
  - missing user/researcher.
  - no authors in legacy path.
  - empty publication/forum maps.
- Zone path: `Z1 UserViewController -> Z2 UserReportFacade -> Z3 CNFIS services/WoS extractor -> Z4 repositories`
- Current tests:
  - `UserReportFacadeTest` (`partial`; status and file-name checks present, workbook content/headers not fully characterized).
- Planned tests:
  - add characterization of year-filter boundary behavior (`missing`).
  - add explicit unauthorized (`user missing`) behavior test (`missing`).
  - add legacy route success-path workbook metadata/content contract test (`missing`).
- Owner: Platform Backend Owner (Z2), with Reporting Domain Owner review.

### Contract ID: C-F03-01

- Flow ID: `F03`
- Priority: `P0`
- Entrypoint: `GroupCnfisExportFacade#buildGroupCnfisWorkbookExport(...)` and `#buildGroupCnfisZipExport(...)`
- Auth assumptions: N/A at facade level; controller endpoint authorization handled in Z1.
- Inputs:
  - `groupId`, `startYear`, `endYear`.
  - group researcher set, publications, forums, CNFIS services, export service.
- Output contract:
  - workbook export returns workbook bytes/content-type/file-name.
  - zip export returns one workbook entry per researcher, with current naming convention.
- Side effects:
  - WoS enrichment + publication save for scored publications.
- Invariants:
  - missing group must return empty optional.
  - `ALL` domain resolution remains current source for CNFIS scoring.
  - zip entry naming must stay `LastName_FirstInitial_AB.xlsx`.
- Known edge cases:
  - group missing.
  - researchers with empty publication sets.
  - publications outside requested year interval.
- Zone path: `Z1 AdminGroupController -> Z2 GroupCnfisExportFacade -> Z3 CNFIS services -> Z4 repositories`
- Current tests:
  - `GroupCnfisExportFacadeTest` (`partial`; good coverage for group missing, year filtering, naming, and metadata; limited assertions on workbook payload semantics).
- Planned tests:
  - add explicit zero-publication researcher case in zip flow (`missing`).
  - add explicit year boundary inclusivity test (`missing`).
- Owner: Platform Backend Owner (Z2), with Reporting Domain Owner review.

### Contract ID: C-F01-01

- Flow ID: `F01`
- Priority: `P0`
- Entrypoint: `ComputerScienceScoringService#getScore(Publication, Indicator)` and `#getScore(ActivityInstance, Indicator)`
- Auth assumptions: N/A (domain service level).
- Inputs:
  - publication/activity with subtype or forum aggregation type.
  - delegated scorer services for journal/conference/book.
- Output contract:
  - returns delegated score for supported subtype/forum type.
  - returns safe empty-score fallback for unsupported subtype/forum type.
- Side effects: none (dispatch/orchestration only).
- Invariants:
  - publication subtype dispatch: `ar/re -> journal`, `cp -> conference`, `bk/ch -> book`.
  - activity dispatch: `Journal`, `Conference Proceeding`, `Book`, `Book Series` map to correct scorer.
  - unknown subtype/forum type must not throw and must return empty score.
- Known edge cases:
  - null publication/activity.
  - blank subtype.
  - scopusSubtype present while subtype null.
- Zone path: `Z2 reporting consumers -> Z3 ComputerScienceScoringService -> Z3 delegated scorers`
- Current tests:
  - `ComputerScienceScoringServiceTest` (`covered` for core dispatch and fallback behavior).
  - `ComputerScienceConferenceScoringServiceSubtypeTest` (`covered` for scopusSubtype fallback in conference flow).
- Planned tests:
  - add explicit null-activity handling assertion in combined scorer (`missing`).
  - add contract test for activity unknown forum type fallback (`partial`).
- Owner: Reporting Domain Owner.

### Contract ID: C-F04-01

- Flow ID: `F04`
- Priority: `P0`
- Entrypoint:
  - `RankingMaintenanceFacade#computePositionsForKnownQuarters()`
  - `RankingMaintenanceFacade#computeQuartersAndRankingsWhereMissing()`
  - `RankingMaintenanceFacade#mergeDuplicateRankings()`
- Auth assumptions: N/A at facade level; admin access enforced in controller layer.
- Inputs:
  - cached ranking set from `CacheService`.
  - repository persistence operations.
- Output contract:
  - methods complete without return value; mutation and cache refresh semantics define behavior.
- Side effects:
  - persists ranking updates/deletes.
  - refreshes ranking cache after each mutation path.
- Invariants:
  - compute methods must call persistence and then cache refresh.
  - merge path must save merged ranking and delete duplicates for same-name groups.
  - all three paths must refresh cache state after mutation.
- Known edge cases:
  - empty ranking list.
  - no duplicate groups.
  - duplicate groups larger than size 2.
- Zone path: `Z1 AdminViewController -> Z2 RankingMaintenanceFacade -> Z4 RankingRepository + cache adapter`
- Current tests:
  - `RankingMaintenanceFacadeTest` (`partial`; verifies save/delete/refresh interactions but not empty-list/no-duplicate edge behavior).
- Planned tests:
  - add empty-rankings interaction contract (`missing`).
  - add no-duplicate merge no-delete behavior test (`missing`).
- Owner: Platform Backend Owner (Z2), with Data Owner review.

## 2. Cross-Contract Test Plan for H03-S03

Priority execution order for follow-up test additions:

1. `C-F02-02`: user CNFIS export orchestration edge-path assertions.
2. `C-F03-01`: group CNFIS edge-path assertions (year boundary + empty researcher dataset).
3. `C-F04-01`: ranking maintenance no-op edge paths.
4. `C-F02-01`: domain-filter and null-forum scorer characterization.
5. `C-F01-01`: null/unknown activity fallback characterization.

## 3. Acceptance Snapshot (S03)

- Contract records are now explicit for all required high-risk reporting/service paths.
- Each contract includes covered/partial/missing baseline and mapped test follow-ups.
- These contracts are the source input for:
  - H03-S04 controller-level characterization tests.
  - H03-S05 facade-level coverage expansion.

## 4. S05 Status Update (2026-03-03)

- Facade contract coverage was expanded in:
  - `UserReportFacadeTest`
  - `GroupCnfisExportFacadeTest`
  - `RankingMaintenanceFacadeTest`
- Newly characterized edges include:
  - unauthorized user paths in CNFIS exports,
  - inclusive year-boundary filtering behavior,
  - researcher-without-publications zip export behavior,
  - ranking maintenance behavior for empty/no-duplicate datasets.

## 5. S06 Baseline Gate (2026-03-03)

- Added a single repeatable H03 baseline command:
  - `npm run verify-h03-baseline`
- Gate coverage:
  - architecture boundary checks,
  - frontend/template asset checks,
  - H03 controller/facade contract tests and `CoreApplicationTests`.
- This command is the default local/CI pre-refactor safety gate for H03 artifacts.

## 6. S07 Closeout and H04 Handoff (2026-03-03)

- H03 is closed: flow priorities, contract schema, characterization tests, and baseline gate are complete and adopted.
- H03 contract baseline source-of-truth:
  - `docs/tasks/closed/h03-flow-priority-map.md`
  - `docs/tasks/closed/h03-contract-schema.md`
  - `docs/tasks/closed/h03-reporting-contracts.md`
  - `npm run verify-h03-baseline`
- Adoption rule for ongoing work:
  - run `npm run verify-h03-baseline` before and after refactors that touch H03 flow areas.
  - treat contract test failures as behavior-change signals that require explicit decision/update.
- Forward link to H04:
  - use H03 contract gaps and fragile-path findings to prioritize H04 test-pyramid rebalancing and targeted integration tests.
