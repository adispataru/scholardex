# H01-S01 Duplication Inventory (Heuristics First)

Date: 2026-03-03

## Method

- Scope scanned: `src/main/java/**`, `src/main/resources/templates/**`, `frontend/**`, `scripts/**`.
- Out of scope: `src/test/**`, `data/**`, generated build outputs.
- Heuristics used:
  - naming variants: `*2025*`, `*-bak*`, `*copy*`, `*old*`, `*legacy*`
  - family clustering by suffix/prefix (for example `*ScoringService*`)
  - repeated inline template JS patterns (Chart.js setup/formatting)
  - repeated script constants and validation flow

## Cluster Inventory

| Cluster ID | Area | Files | Why flagged | Likely shared intent | Drift risk | Blast radius | Initial action | Notes / evidence |
|---|---|---|---|---|---|---|---|---|
| C01 | backend | `src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService.java`; `src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService2025.java` | Versioned class pair (`2025`) with similar scoring pipeline and diverging field/method usage | Compute CNFIS scoring report from publication/forum/ranking data | high | high | investigate | Subtype API drift (`getSubtype` vs `getScopusSubtype`) at `CNFISScoringService.java:36,94` vs `CNFISScoringService2025.java:36,110,118`; output model drift (`setIsiRosu/Galben/Alb` vs `setIsiQ1..Q4`) at `CNFISScoringService.java:70-76` vs `CNFISScoringService2025.java:80-89` |
| C02 | template | `src/main/resources/templates/admin/researchers.html`; `src/main/resources/templates/admin/researchers-bak.html` (deleted 2026-03-03) | Active + backup template pair (`-bak`) covering same page intent | Render admin researchers listing | high | medium | resolved | Backup file deleted by decision; active template remains source of truth. |
| C03 | template | `src/main/resources/templates/admin/rankings-view.html`; `src/main/resources/templates/admin/rankings-view-bak.html` (deleted 2026-03-03) | Active + backup template pair (`-bak`) with overlapping rankings view content | Render journal ranking details and metrics | high | high | resolved | Backup file deleted by decision; active template remains source of truth. |
| C04 | backend | `src/main/java/ro/uvt/pokedex/core/service/reporting/*ScoringService*.java` (family), with `AbstractForumScoringService.java`, `AbstractWoSForumScoringService.java`, `ScoringFactoryService.java` | Large parallel service family with similar business-rule shape and risk of copy/paste drift | Compute domain-specific scoring from ranking/publication inputs | medium | high | investigate | Reporting package density is highest (`service/reporting` has 22 Java files); scoring service family includes many variants (`AISJournalScoringService.java`, `ComputerScience*ScoringService.java`, `EconomicsJournalScoringService.java`, etc.) |
| C05 | template | `src/main/resources/templates/admin/rankings-view.html`; `src/main/resources/templates/user/wos-rankings-view.html` (deleted 2026-03-03) | Near-duplicate inline Chart.js setup and quartile rendering logic across admin/user variants | Render WoS ranking charts and quartile charts by category | medium | medium | resolved | User duplicate template deleted; user WoS route now renders the kept template (`admin/rankings-view`). |
| C06 | frontend/scripts | `scripts/build-assets.js`; `scripts/verify-assets.js`; `scripts/assets-contract.js` | Shared contract extraction applied for asset expectations; residual logic duplication intentionally differs by command role | Enforce existence of built frontend artifacts | low | low | partially-resolved | `expectedAssets` is now centralized in `assets-contract.js` and consumed by both scripts; fallback-vs-hard-fail behavior remains intentionally distinct. |

## Prioritized Next-Look (Top 5 for H01-S03)

1. C01: `CNFISScoringService` vs `CNFISScoringService2025` (business-rule divergence risk in scoring outputs).
2. C04: reporting scoring-service family (cross-class drift likely; high blast radius).
3. C04 sub-cluster split by domain (reduce investigation size before consolidation strategy).
4. C06 residual script-flow duplication (after shared contract extraction).
5. Cross-role visibility validation for rankings pages after C05 consolidation.

## H01-S04 Priority Table (P0/P1/P2)

| Priority | Cluster | Why now | Execution objective | Exit signal |
|---|---|---|---|---|
| P0 | C01 (CNFIS scoring path) | High drift and high blast radius in production-relevant scoring/export flow. | Freeze expected behavior with focused tests, then normalize CNFIS rule path and remove ambiguity. | CNFIS characterization tests pass and policy contract is explicit in code/docs. |
| P1 | C04 (reporting scoring family) | Broad backend blast radius; multiple risky inconsistencies across abstractions/factory/delegation. | Split C04 into manageable sub-clusters and remove highest-risk inconsistencies first (factory nulls, parsing contract, dispatcher gaps). | No null factory fallback, parsing contract unified/guarded, strategy coverage gaps documented or fixed. |
| P2 | C06 (asset scripts) | Low blast radius and already partially resolved; remaining duplication is operational, not domain-critical. | Keep behavior split but minimize residual duplicated flow and add CI smoke chaining (`build` -> `verify-assets`). | Scripts share contract/helpers and CI reliably catches asset contract regressions. |

Execution order: `C01` -> `C04` -> `C06`.

## Open Questions

- For C01, should 2025 behavior be represented as configuration/rules within one service rather than separate classes?
- For C04, do we prefer consolidation via shared helper/services first, or configuration-table extraction first?

## Policy Notes

- Runtime templates under `src/main/resources/templates/admin` and `src/main/resources/templates/user` must not include `*-bak.html` files.
- Enforcement is automated via `npm run verify-template-assets`.

## H01-S01 Exit Criteria Check

- At least one cluster per layer:
  - Java/backend: C01, C04
  - Templates: none currently open (C02/C03/C05 resolved by deletion/consolidation)
  - Frontend/scripts: C06
- Every cluster includes risk, blast radius, and initial action.
- Top 5 clusters are identified for `H01-S03` drift analysis.
