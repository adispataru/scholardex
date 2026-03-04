# H04-S03 Risk-Weighted Coverage Gap Matrix

Date: 2026-03-03  
Inputs: `docs/h03-flow-priority-map.md`, `docs/h03-reporting-contracts.md`, `docs/h04-test-inventory.md`, `docs/h04-test-strategy.md`

## 1. Method

- Started from H03 prioritized flows (`F01..F08`) and contract records (`C-F*`).
- Mapped each flow to currently available tests and remaining unprotected behaviors.
- Assigned severity (`high|medium|low`) based on blast radius + drift history + mutation risk.
- Selected target test layer using H04 strategy rules (prefer lowest reliable layer).

## 2. Gap Matrix

| Gap ID | Flow/Contract | Severity | Current signal | Missing behavior guard | Target layer | Proposed action | Planned slice |
|---|---|---|---|---|---|---|---|
| G01 | `F02` / `C-F02-01` | high | `CNFISScoringService2025Test` covers subtype/proceedings/date basics | domain-filter behavior (`ALL` vs specific categories) and null-forum defensive path not explicit | unit | extend `CNFISScoringService2025Test` with domain-filter + null-forum cases | `H04-S04` |
| G02 | `F01` / `C-F01-01` | high | `ComputerScienceScoringServiceTest` covers main dispatch | null activity and unknown forum-type fallback path not explicitly frozen | unit | add explicit unknown/null activity fallback tests in CS scorer tests | `H04-S04` |
| G03 | `F04` / `C-F04-01` | high | `RankingMaintenanceFacadeTest` covers mutation interactions | no-op semantics for empty/no-duplicate ranking datasets only partially characterized | unit | add no-op/empty-dataset assertions (no delete/save where not expected) | `H04-S04` |
| G04 | `F02` / `C-F02-02` | high | controller + facade contract tests exist | workbook payload semantics (sheet/header/data intent) not asserted in facade-level tests | contract | add workbook structure/content contract assertions for user CNFIS exports | `H04-S05` |
| G05 | `F03` / `C-F03-01` | high | group CNFIS facade/controller tests cover status and zip naming | workbook payload semantics and boundary-year assertions are still shallow | contract | add CNFIS workbook + zip payload characterization assertions | `H04-S05` |
| G06 | `F06` | medium | `AdminInstitutionReportFacadeTest` + controller contract test | excel export row/content contract is not explicitly locked | contract | add export workbook content contract test for institution flow | `H04-S05` |
| G07 | `F05` | medium | `GroupExportFacadeTest` + controller contract coverage (CSV response path) | CSV row-level contract (column semantics/order, edge rows) not explicitly frozen | contract | add focused CSV output contract characterization | `H04-S05` |
| G08 | cross-flow data layer | medium | no `@DataJpaTest`/repository integration tests | query semantics and persistence assumptions rely on mocks only | integration | add minimal repository contract tests for highest-risk reporting query paths | `H04-S05` |
| G09 | cross-suite reliability | medium | suite runtime baseline exists (`4s`) | no enforceable runtime/flakiness guardrails yet | slice/process | define targeted command sets + runtime budget checks + flaky triage notes | `H04-S06` |
| G10 | non-H03 transport routes | low | H03 hotspot routes characterized | remaining non-H03 routes may drift without transport contracts | contract | add contracts only when touching those routes (avoid blanket expansion) | deferred (post-H04) |

## 3. Priority Execution Order

1. `G01` CNFIS scorer edge-path unit guards.
2. `G02` CS scorer null/unknown fallback unit guards.
3. `G03` ranking maintenance no-op unit guards.
4. `G04` user CNFIS workbook payload contract guards.
5. `G05` group CNFIS workbook/zip payload contract guards.
6. `G08` minimal repository integration contracts.
7. `G06` + `G07` export payload contracts for institution/group non-CNFIS flows.
8. `G09` runtime/flakiness guardrails.

## 4. Coverage Action Mapping by H04 Subtasks

- `H04-S04` (unit hotspot expansion): `G01`, `G02`, `G03`.
- `H04-S05` (integration/slice seam coverage): `G04`, `G05`, `G06`, `G07`, `G08`.
- `H04-S06` (reliability guardrails): `G09`.
- deferred/conditional: `G10`.

## 5. Acceptance Snapshot for H04-S03

- Every P0 flow (`F01..F04`) now has at least one concrete, prioritized missing-coverage action.
- P1 flows (`F05..F07`) have targeted contract additions defined without broad test-scope expansion.
- Strategy-consistent layer decisions are explicit (unit-first for logic, contract/slice for transport, integration only for seam risk).

## 6. S05 Status Update (2026-03-03)

- Resolved in `H04-S05`:
  - `G04` user workbook payload contract assertions expanded (`UserReportFacadeTest`).
  - `G05` group CNFIS workbook/zip payload assertions expanded (`GroupCnfisExportFacadeTest`).
  - `G06` institution export workbook content contract expanded (`AdminViewControllerContractTest`).
  - `G07` group CSV output contract expanded (`AdminGroupControllerContractTest`).
- Deferred:
  - `G08` repository integration contracts remain pending until dedicated Mongo test infrastructure is introduced (embedded/testcontainer policy to be decided in `H04-S06`).

## 7. S06 Status Update (2026-03-03)

- `G09` resolved:
  - added `verify-h04-baseline` and runtime soft-budget reporting (`scripts/verify-test-runtime.js`).
  - reliability/flaky triage policy formalized in `docs/h04-reliability-guardrails.md`.
- `G08` partially resolved (initial tranche complete):
  - Testcontainers Mongo infrastructure added for repository integration contracts.
  - first repository integration tests added:
    - `ScopusPublicationRepositoryIntegrationTest`
    - `ScopusCitationRepositoryIntegrationTest`
  - dedicated execution command added: `npm run verify-h04-mongo-integration`.
