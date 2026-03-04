# H02-S04 Boundary Violations Inventory

Date: 2026-03-03  
Baseline rules: `docs/h02-dependency-rules.md`

## 1. Scan Method

Heuristic/static pass over Java and templates:

- `rg` for controller/view imports of `core.repository` (R1 / `Z1 -> Z4`).
- `rg` for controller/view imports/injections of reporting services (R1 + matrix `Z1 -> Z3`).
- `rg` for reporting imports of `core.service.CacheService` (R7 back-edge `Z3 -> Z2`).
- `rg` for template forbidden patterns (`*-bak.html`, `/vendor/`) and asset contract drift.

## 2. Findings Summary

| ID | Rule | Severity | Scope |
|---|---|---|---|
| V01 | R1 (`Z1 -> Z4` denied for new code; existing debt to track) | high (partially remediated) | controllers/views directly depending on repositories |
| V02 | Matrix (`Z1 -> Z3` denied) + R1 layering intent | high | controllers/views directly depending on reporting rule services |
| V03 | R1/R2 responsibility split | medium | scoring/report-assembly business logic inside controllers |
| V04 | R7 (`Z3 -> Z2` back-edge denied) | medium (resolved) | reporting services importing `CacheService` from `core/service` |

## 3. Detailed Violations

## V01 - Controllers/Views directly depend on repositories (`Z1 -> Z4`)

Severity: `high`  
Why this violates rules: `R1` and the matrix require entry layer to delegate through orchestration services; repository coupling is explicitly debt-only.

Evidence:
- High-coupling controllers:
  - `AdminViewController`: 14 repository fields (e.g. [AdminViewController.java](/Users/adispataru/Documents/programming/demo-exam/core/src/main/java/ro/uvt/pokedex/core/view/AdminViewController.java:45))
  - `UserViewController`: 13 repository fields (e.g. [UserViewController.java](/Users/adispataru/Documents/programming/demo-exam/core/src/main/java/ro/uvt/pokedex/core/view/UserViewController.java:54))
  - `AdminGroupController`: 10 repository fields (e.g. [AdminGroupController.java](/Users/adispataru/Documents/programming/demo-exam/core/src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java:43))
- Additional direct repository usage in transport layer:
  - [PublicationWizardController.java](/Users/adispataru/Documents/programming/demo-exam/core/src/main/java/ro/uvt/pokedex/core/view/user/PublicationWizardController.java:23)
  - [ActivityInstanceController.java](/Users/adispataru/Documents/programming/demo-exam/core/src/main/java/ro/uvt/pokedex/core/view/user/ActivityInstanceController.java:25)
  - [ExportController.java](/Users/adispataru/Documents/programming/demo-exam/core/src/main/java/ro/uvt/pokedex/core/controller/ExportController.java:26)
- Total controller/view repository imports found: 42.

Remediation direction:
- Introduce/expand Z2 façade services for admin/user/group/reporting read/write flows.
- Freeze new controller repository injections immediately.

V01 implementation status:
- New application facades added:
  - `UserPublicationFacade`
  - `UserScopusTaskFacade`
  - `UserReportFacade`
  - `GroupReportFacade`
  - `GroupManagementFacade`
- Migrated endpoint delegation:
  - `UserViewController`: `/publications`, `/publications/citations`, `/publications/edit/{eid}`, `/publications/save/{eid}`, `/publications/scopus_tasks`, `/tasks/scopus/update`, `/tasks/scopus/updateCitations`, `/indicators/apply/{id}`, `/individualReports/view/{id}`
  - `AdminGroupController`: `/{id}/publications`, `/{gid}/reports/view/{id}`
- Slice 2 (user-closure) migrated endpoint delegation:
  - `UserViewController`: `/indicators`, `/individualReports`
- Slice 3 (admin-group CRUD/forms) migrated endpoint delegation:
  - `AdminGroupController`: `/admin/groups`, `/create`, `/edit/{id}`, `/update`, `/delete/{id}`
- Field-count snapshot for migrated controllers:
  - Before: `UserViewController=13`, `AdminGroupController=10`, combined=`23`
  - After slice 1: `UserViewController=9`, `AdminGroupController=7`, combined=`16`
  - After slice 2: `UserViewController=6`, `AdminGroupController=7`, combined=`13`
  - After slice 3: `UserViewController=6`, `AdminGroupController=3`, combined=`9`
  - After slice 4: `UserViewController=6`, `AdminGroupController=0`, combined=`6`
  - Total reduction: `17/23` (`73.9%`)
- Controller/view repository import count:
  - Before baseline slice: `42`
  - After slice 1: `36`
  - After slice 2: `32`
  - After slice 3: `28`
  - After slice 4: `25`
- Policy enforcement in code comments: remaining controller repository injections are marked as H02 V01 deferred debt.
- V01 acceptance target for the tracked baseline pair remains achieved and improved (`<=13` combined fields, now `73.9%` reduction). `AdminGroupController` repository debt is closed; residual V01 debt remains in other deferred controllers.

## V02 - Controllers/Views directly depend on reporting rule services (`Z1 -> Z3`)

Severity: `high` (partially remediated)  
Why this violates rules: matrix denies direct `Z1 -> Z3`; rule logic should be accessed through orchestration boundaries.

Status update (2026-03-03, V02 slice for User/AdminGroup pair):
- `UserViewController` reporting-coupled export endpoints are facade-backed (`UserReportFacade`):
  - `/user/indicators/export/{id}`
  - `/user/publications/exportCNFIS2025`
  - `/user/export/cnfis` (legacy route kept, now facade-backed)
- `AdminGroupController` no longer calls reporting export service directly for CNFIS workbook flow:
  - `/admin/groups/{id}/publications/exportCNFIS2025` now delegates fully to `GroupCnfisExportFacade`
- Static scan check:
  - `rg -n "import ro\\.uvt\\.pokedex\\.core\\.service\\.reporting" src/main/java/ro/uvt/pokedex/core/view/UserViewController.java src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java`
  - Result: no matches.

AdminView verification closeout (2026-03-03):
- `AdminViewController` is verified compliant for V02 (no direct `Z1 -> Z3` reporting-service imports/injections).
- Baseline transport-layer scan command:
  - `rg -n "import ro\\.uvt\\.pokedex\\.core\\.service\\.reporting|ScientificProductionService|ActivityReportingService|CNFISReportExportService|.*ScoringService" src/main/java/ro/uvt/pokedex/core/view src/main/java/ro/uvt/pokedex/core/controller`
  - Result: no matches.

Residual V02 scope:
- Re-open only if new controller/view direct reporting imports appear in future scans; current baseline is clean for `UserViewController`, `AdminGroupController`, and `AdminViewController`.

## V03 - Business scoring/report assembly logic inside controllers

Severity: `medium`  
Why this violates rules: transport layer contains report scoring assembly that belongs to Z2/Z3.

Status update (2026-03-03, focused AdminView slices):
- Remediated endpoints in `AdminViewController` are now facade-backed:
  - `/admin/institutions/{id}/publications` via `AdminInstitutionReportFacade`
  - `/admin/institutions/{id}/publications/exportExcel` data assembly via `AdminInstitutionReportFacade`
  - `/admin/rankings/wos/computePositionsForKnownQuarters` via `RankingMaintenanceFacade`
  - `/admin/rankings/wos/computeQuartersAndRankingsWhereMissing` via `RankingMaintenanceFacade`
  - `/admin/rankings/wos/mergeDuplicateRankings` via `RankingMaintenanceFacade`
- Additional remediated transport assembly:
  - `/admin/scopus/publications/search` via `AdminScopusFacade`
  - `/admin/scopus/publications/citations` via `AdminScopusFacade`
  - `/api/export` (`ExportController`) data prep via `ForumExportFacade`
- Transport layer now maps request/model/response for these endpoints, with business/data assembly moved to Z2 facades.

Remediation direction:
- Keep controllers limited to request/model mapping and response rendering.
- Re-open V03 only if new non-trivial data/workbook assembly is added to transport endpoints.

## V04 - Reporting layer imports orchestration-layer `CacheService` (`Z3 -> Z2`)

Severity: `medium` (resolved on 2026-03-03)  
Why this violated rules: `R7` denies back-edges from domain scoring layer to orchestration layer.

Closure status:
- Introduced reporting-owned read port: [ReportingLookupPort.java](/Users/adispataru/Documents/programming/demo-exam/core/src/main/java/ro/uvt/pokedex/core/service/reporting/ReportingLookupPort.java).
- Introduced Z2 adapter implementation: [CacheBackedReportingLookupFacade.java](/Users/adispataru/Documents/programming/demo-exam/core/src/main/java/ro/uvt/pokedex/core/service/application/CacheBackedReportingLookupFacade.java).
- Rewired all reporting scorers/base classes to depend on `ReportingLookupPort` instead of `CacheService`.

Verification evidence:
- `rg -n "import ro\\.uvt\\.pokedex\\.core\\.service\\.CacheService" src/main/java/ro/uvt/pokedex/core/service/reporting`
  - Result: no matches.
- `rg -n "CacheService" src/main/java/ro/uvt/pokedex/core/service/reporting`
  - Result: no matches.

## 4. No-Finding Checks

These checks did not produce violations in this pass:

- Z6 template policy violations (`*-bak.html`, `/vendor/`) in runtime template roots: none found.
- Service/repository imports of controllers/views (`Z2/Z4 -> Z1` back-edge): none found.
- External HTTP client usage in controllers/views: none found.

## 5. Prioritization for H02-S05

Proposed remediation priority order:

1. V01 (high): controller repository dependency reduction baseline.
2. V02 (high): remove `Z1 -> Z3` direct coupling via orchestration façades.
3. V04 (medium): remove reporting back-edge to `CacheService` with a lookup port.
