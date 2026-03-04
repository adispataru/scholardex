# H03-S01 Flow Priority Map

Date: 2026-03-03  
Scope: high-impact runtime flows to baseline for H03 characterization tests.

## 1. Selection Method

Flow ranking uses these signals:

- blast radius (user/admin critical workflows, reporting/export paths);
- mutation risk (cache/ranking recomputation, publication enrichment persistence);
- drift history from H01 (CNFIS/scoring/export families);
- architecture changes from H02 (new facades and boundary refactors needing behavior freeze).

## 2. Prioritized Flow Inventory

| Flow ID | Priority | Risk | Entrypoint(s) | Current Path (Zone Mapping) | Why prioritized | Current baseline coverage |
|---|---|---|---|---|---|---|
| F01 | P0 | high | `GET /user/indicators/export/{id}` | `Z1 UserViewController -> Z2 UserReportFacade -> Z3 ScientificProductionService/ActivityReportingService -> Z4 reporting+scopus repos` | user-facing scoring export; high regression impact from scoring/facade changes | facade-level partial (`UserReportFacadeTest`), no controller characterization |
| F02 | P0 | high | `GET /user/publications/exportCNFIS2025` + `GET /user/export/cnfis` | `Z1 UserViewController -> Z2 UserReportFacade -> Z3 CNFISScoringService2025 + CNFISReportExportService -> Z4 scopus/reporting repos` | CNFIS path had highest drift history in H01; includes WoS enrichment + workbook generation | service tests exist (`CNFISScoringService2025Test`, `UserReportFacadeTest`), no controller characterization |
| F03 | P0 | high | `GET /admin/groups/{id}/publications/exportCNFIS2025`; `GET /admin/groups/{id}/publications/exportAllReports` | `Z1 AdminGroupController -> Z2 GroupCnfisExportFacade -> Z3 CNFISScoringService2025 + CNFISReportExportService -> Z4 scopus/reporting repos` | group-level institutional export + zip fan-out; high blast radius and data mutation in enrichment loop | facade-level tests present (`GroupCnfisExportFacadeTest`), no endpoint characterization |
| F04 | P0 | high | `POST /admin/rankings/wos/computePositionsForKnownQuarters`; `POST /admin/rankings/wos/computeQuartersAndRankingsWhereMissing`; `POST /admin/rankings/wos/mergeDuplicateRankings` | `Z1 AdminViewController -> Z2 RankingMaintenanceFacade -> Z4 RankingRepository + CacheService` | mutates core ranking dataset and cache; can affect all scoring outcomes | unit tests present (`RankingMaintenanceFacadeTest`), no transport-level contract test |
| F05 | P1 | medium-high | `GET /admin/groups/{id}/publications/export` | `Z1 AdminGroupController -> Z2 GroupExportFacade -> Z4 scopus/group repos` | institutional CSV export consumed externally; aggregation and formatting sensitive | facade-level tests present (`GroupExportFacadeTest`), no endpoint characterization |
| F06 | P1 | medium-high | `GET /admin/institutions/{id}/publications`; `GET /admin/institutions/{id}/publications/exportExcel` | `Z1 AdminViewController -> Z2 AdminInstitutionReportFacade -> Z4 institution/scopus/report repos` | heavy report assembly and Excel rows; recently extracted from controller | facade tests present (`AdminInstitutionReportFacadeTest`), no endpoint characterization |
| F07 | P1 | medium | `GET /user/indicators/apply/{id}`; `GET /user/individualReports/view/{id}` | `Z1 UserViewController -> Z2 UserReportFacade -> Z3 ScientificProductionService/ActivityReportingService -> Z4 scopus/report repos` | core user scoring views; selector and aggregation behavior must be stable | partial facade tests (`UserReportFacadeTest`), no controller characterization |
| F08 | P2 | medium | `GET /admin/scopus/publications/search`; `GET /admin/scopus/publications/citations`; `GET /api/export` | `Z1 AdminViewController/ExportController -> Z2 AdminScopusFacade/ForumExportFacade -> Z4 scopus repos` | read-only but still user-visible and report-adjacent data assembly | facade tests present (`AdminScopusFacadeTest`, `ForumExportFacadeTest`) |

## 3. Top 5 for Immediate H03 Contract Work

1. `F01` user indicator workbook export.
2. `F02` user CNFIS exports (2025 + legacy route behavior).
3. `F03` group CNFIS workbook/zip exports.
4. `F04` admin WoS ranking maintenance mutations.
5. `F06` admin institution publications + Excel export.

## 4. Coverage Baseline Snapshot (Current State)

- Strongest existing guardrails: reporting and application-facade unit tests.
- Gap to close in H03: transport-level characterization tests (view names, redirects, status codes, response headers/files, key model attributes).
- No existing WebMvc/controller characterization suite detected in current test tree; H03-S04 should establish this baseline for selected P0/P1 flows.
