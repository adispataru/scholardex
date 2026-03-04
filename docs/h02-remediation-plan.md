# H02 Remediation Plan

Date: 2026-03-03

## R1 - V01 Baseline Slice (Controller Repository Dependency Reduction)

Status: target achieved for baseline pair; residual debt remains.

Delivered in this slice:
- Added Z2 use-case facades under `src/main/java/ro/uvt/pokedex/core/service/application/**`.
- Migrated high-impact User + Group endpoints to facade delegation.
- Removed direct repository orchestration from migrated endpoints.
- Added facade tests:
  - `UserPublicationFacadeTest`
  - `UserScopusTaskFacadeTest`
  - `UserReportFacadeTest`
  - `GroupReportFacadeTest`

Completion criteria carried forward to next V01 slice:
- Reach at least 40% repository-field reduction across target controllers. (Achieved in slice 2)
- Continue removing deferred `Z1 -> Z4` dependencies from remaining endpoints/controllers.
- Preserve route/template/redirect behavior parity.

Slice 2 closeout (2026-03-03):
- User-controller closure endpoints migrated: `/user/indicators`, `/user/individualReports`.
- Baseline pair field counts:
  - Before V01: `23`
  - After slice 1: `16`
  - After slice 2: `13`
  - Net reduction: `43.5%`

Slice 3 closeout (2026-03-03):
- AdminGroup CRUD/forms migrated to `GroupManagementFacade`:
  - `/admin/groups`
  - `/admin/groups/create`
  - `/admin/groups/edit/{id}`
  - `/admin/groups/update`
  - `/admin/groups/delete/{id}`
- Baseline pair field counts:
  - Before V01: `23`
  - After slice 3: `9`
  - Net reduction: `60.9%`
- Residual AdminGroup controller debt is limited to deferred export/CNFIS endpoints.

Slice 4 closeout (2026-03-03):
- AdminGroup deferred export/CNFIS endpoints migrated to dedicated facades:
  - `GroupExportFacade` for `/{id}/publications/export`
  - `GroupCnfisExportFacade` for `/{id}/publications/exportCNFIS2025` and `/{id}/publications/exportAllReports`
- `AdminGroupController` repository fields: `3 -> 0`
- Baseline pair field counts:
  - Before V01: `23`
  - After slice 4: `6`
- Net reduction: `73.9%`
- R1/V01 is closed for the AdminGroup+User baseline pair; remaining V01 cleanup is outside this pair (for example `AdminViewController` and smaller controllers).

## R2 - V02 Baseline Slice (`Z1 -> Z3` Reporting Coupling Reduction)

Status: baseline pair remediated; residual debt remains in non-baseline controllers.

V02 slice closeout (2026-03-03):
- `UserViewController` reporting-coupled exports moved behind `UserReportFacade`:
  - `/user/indicators/export/{id}`
  - `/user/publications/exportCNFIS2025`
  - `/user/export/cnfis` (legacy route retained and facade-backed)
- `AdminGroupController` CNFIS workbook export moved fully behind `GroupCnfisExportFacade`:
  - `/admin/groups/{id}/publications/exportCNFIS2025`
- Controller imports of `core.service.reporting` for the User/AdminGroup pair are now zero.

Residual scope:
- Apply the same V02 coupling reduction only if future transport controllers introduce direct reporting couplings.

AdminView V02 verification slice closeout (2026-03-03):
- `AdminViewController` requires no V02 refactor at this time.
- Verification scan for `core/view` + `core/controller` direct reporting-service couplings returned no matches.
- Next `AdminViewController` architecture work should continue under:
  - V01 residual repository coupling reduction
  - V03 business/report-assembly extraction from transport
  - V04 back-edge cleanup as needed

## R3 - V03 Focused Slice (AdminView Transport-Only Extraction)

Status: completed for currently identified transport-assembly hotspots.

V03 focused slice closeout (2026-03-03):
- Added `AdminInstitutionReportFacade` and moved institution publication/citation data assembly out of transport:
  - `/admin/institutions/{id}/publications`
  - `/admin/institutions/{id}/publications/exportExcel` (data preparation moved; workbook writing remains in controller)
- Added `RankingMaintenanceFacade` and moved ranking maintenance actions out of controller:
  - `/admin/rankings/wos/computePositionsForKnownQuarters`
  - `/admin/rankings/wos/computeQuartersAndRankingsWhereMissing`
  - `/admin/rankings/wos/mergeDuplicateRankings`
- Added facade tests:
  - `AdminInstitutionReportFacadeTest`
  - `RankingMaintenanceFacadeTest`

Residual V03 scope:
- Final closure slice delivered:
  - `AdminScopusFacade` for:
    - `/admin/scopus/publications/search`
    - `/admin/scopus/publications/citations`
  - `ForumExportFacade` for:
    - `/api/export` (controller kept streaming/HTTP wiring, facade owns data prep)
  - Added facade tests:
    - `AdminScopusFacadeTest`
    - `ForumExportFacadeTest`
- No remaining V03 hotspots identified in the current H02 scope; reopen only if new transport-level data/workbook assembly is introduced.

## R4 - V04 Back-Edge Cleanup (`Z3 -> Z2`)

Status: completed for reporting package.

V04 slice closeout (2026-03-03):
- Added `ReportingLookupPort` in `service/reporting` as the reporting-owned lookup contract.
- Added `CacheBackedReportingLookupFacade` in `service/application` as the only adapter delegating to `CacheService`.
- Rewired reporting base classes and concrete scorers (including `CNFISScoringService2025`) to use `ReportingLookupPort`.
- Added adapter delegation tests:
  - `CacheBackedReportingLookupFacadeTest`
- Updated reporting characterization tests to use `ReportingLookupPort` mocks where needed.

Verification:
- Reporting package now has zero `CacheService` references/imports via static scan.

## Enforcement Baseline (H02-S06)

Status: completed on 2026-03-03.

- Added automated boundary check: `npm run verify-architecture-boundaries`.
- Guard coverage:
  - blocks new controller/view `core.repository` imports beyond current debt allowlist;
  - blocks direct transport imports of `core.service.reporting`;
  - blocks `CacheService` references in `service/reporting/**`.
- Contributor workflow updated in `CONTRIBUTING.md` to include this verification gate.
