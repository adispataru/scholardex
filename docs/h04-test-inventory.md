# H04-S01 Test Inventory and Taxonomy Map

Date: 2026-03-03  
Scope: `src/test/java/**` current baseline

## 1. Method and Inputs

- Enumerated all test classes in `src/test/java`.
- Classified each test to one primary layer: `unit`, `slice`, `integration`, `contract`, `e2e`.
- Captured execution baseline from latest `./gradlew test` run.
- Captured available Gradle verification/test tasks from `./gradlew tasks --all`.

## 2. Baseline Snapshot

- Total test classes: `24`
- Total executed test cases: `91`
- Full suite runtime (`./gradlew test`): `BUILD SUCCESSFUL in 4s`
- Summed per-suite reported time (JUnit XML): `1.945s`
- Heaviest suites in current run:
  - `ExportControllerContractTest` (`0.24s`)
  - `AdminViewControllerContractTest` (`0.163s`)
  - `AdminInstitutionReportFacadeTest` (`0.155s`)

## 3. Taxonomy Summary

| Layer | Classes | Notes |
|---|---:|---|
| `unit` | 14 | Mockito/pure logic tests for Z2/Z3 services and support helpers. |
| `contract` | 5 | Explicit behavior/contract characterization tests (route/output or cross-base contract). |
| `slice` | 4 | `@WebMvcTest` controller slices validating transport contracts and response shape. |
| `integration` | 1 | `@SpringBootTest` context smoke (`CoreApplicationTests`). |
| `e2e` | 0 | No browser/API end-to-end workflow tests present. |

## 4. Detailed Class Inventory

| Test Class | Primary Layer | Zone/Area | Intent |
|---|---|---|---|
| `CoreApplicationTests` | integration | app wiring | Spring context and key bean startup smoke. |
| `ExportControllerContractTest` | contract | Z1 controller | `/api/export` workbook response contract. |
| `AdminViewControllerContractTest` | contract | Z1 view controller | ranking/institution endpoint route+model+header contracts. |
| `AdminGroupControllerContractTest` | contract | Z1 view controller | group CNFIS export route/header contracts. |
| `UserViewControllerContractTest` | contract | Z1 view controller | user export/report route and response contracts. |
| `CategoryDomainContractTest` | contract | Z3 reporting | parity contract for abstract category-domain decisions. |
| `AdminInstitutionReportFacadeTest` | unit | Z2 facade | institution publication/report model assembly. |
| `AdminScopusFacadeTest` | unit | Z2 facade | scopus search/citation model assembly. |
| `CacheBackedReportingLookupFacadeTest` | unit | Z2 adapter | `ReportingLookupPort` adapter delegation to cache service. |
| `ForumExportFacadeTest` | unit | Z2 facade | forum export row assembly and dedupe behavior. |
| `GroupCnfisExportFacadeTest` | unit | Z2 facade | group CNFIS workbook/zip orchestration and edge cases. |
| `GroupExportFacadeTest` | unit | Z2 facade | group CSV export data assembly. |
| `GroupManagementFacadeTest` | unit | Z2 facade | group CRUD/form data assembly orchestration. |
| `GroupReportFacadeTest` | unit | Z2 facade | group reporting model behavior. |
| `RankingMaintenanceFacadeTest` | unit | Z2 facade | ranking compute/merge mutation interactions. |
| `UserPublicationFacadeTest` | unit | Z2 facade | user publication listing/edit data orchestration. |
| `UserReportFacadeTest` | unit | Z2 facade | user report/CNFIS export orchestration behavior. |
| `UserScopusTaskFacadeTest` | unit | Z2 facade | task creation/list orchestration behavior. |
| `CNFISScoringService2025Test` | unit | Z3 reporting | CNFIS 2025 scoring rules and resilience cases. |
| `ComputerScienceConferenceScoringServiceSubtypeTest` | unit | Z3 reporting | subtype-source conference dispatch behavior. |
| `ComputerScienceScoringServiceTest` | unit | Z3 reporting | combined CS subtype/forum dispatch behavior. |
| `PublicationSubtypeSupportTest` | unit | Z3 reporting support | subtype normalization/precedence helper behavior. |
| `ScoringCategorySupportTest` | unit | Z3 reporting support | safe category parsing and domain eligibility helper behavior. |
| `ScoringFactoryServiceTest` | unit | Z3 reporting | strategy-to-service resolution behavior. |

## 5. Current Task/Workflow Surface

Detected Gradle tasks directly relevant to testing/verification:

- `test`
- `check`
- `verifyDuplicationGuardrails`
- `verifyFrontendAssets`
- `verifyTemplateAssets`

## 6. Initial Blind Spots (H04-S01 Findings)

1. No `e2e` coverage for user/admin critical workflows (export and ranking maintenance are not browser/API chain tested end-to-end).
2. Repository/data-layer integration coverage is minimal (no `@DataJpaTest`/database contract tests).
3. Slice/contract coverage currently concentrates on H03 hotspots; non-H03 routes have limited transport characterization.
4. Runtime baseline exists at suite level, but there is no enforced per-layer runtime budget yet.
5. Flakiness policy and deterministic test execution guidance are not formalized in H04 docs yet.

## 7. H04-S02 Input Handoff

This inventory is the source baseline for:

- target pyramid ratio decisions (`H04-S02`),
- risk-weighted gap matrix creation (`H04-S03`),
- selecting unit-first vs slice/integration additions for the first rebalance slices.
