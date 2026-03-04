# H01-S03 Drift Findings

Date: 2026-03-03  
Scope in this slice: `C01`, `C03`, `C04`, `C05`, `C06`

## Method

- Compared near-duplicate files line-by-line for behavior-affecting differences.
- Cross-checked model/call-site wiring for backend clusters.
- Compared template inline JS blocks and rendering flow for UI clusters.
- Compared script constants and control flow for tooling clusters.
- Classified each drift as: `harmless`, `intentional`, `risky`, or `unknown`.

## Cluster Status Snapshot (Tracking Closeout)

| Cluster | Current status | Resolved slices | Remaining work only |
|---|---|---|---|
| C01 | resolved | Legacy CNFIS service/model removed; active 2025 path retained; subtype/category policy decisions applied; canonical rule spec added; edge-case characterization tests expanded; dead/noise locals removed. | None in H01-S03 scope. |
| C03 | resolved | Backup template removed (`admin/rankings-view-bak.html`); runtime backup-template guardrail enforced. | None in H01-S03 scope. |
| C04 | resolved | `D05` resolved in H01-S07 (factory null fallback removed); `D04` resolved in C04 Slice 2 (book dispatch aligned + tests); `D01/D02` resolved in Slice 3 (shared category parsing contract); `D03` resolved in Slice 4 (shared subtype-source policy); `D06` resolved in Slice 5 (metadata/logger cleanup). | Residual D07 in scoring path resolved; remaining category split references in CNFIS export are out of C04 scope. |

## Cluster C01 Overview

- Files:
  - `src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService.java`
  - `src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService2025.java`
  - `src/main/java/ro/uvt/pokedex/core/model/reporting/CNFISReport.java`
  - `src/main/java/ro/uvt/pokedex/core/model/reporting/CNFISReport2025.java`
- Current usage status:
  - Active endpoints/export path use the `2025` flow:
    - `src/main/java/ro/uvt/pokedex/core/view/UserViewController.java:1296`
    - `src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java:534`
    - `src/main/java/ro/uvt/pokedex/core/service/reporting/CNFISReportExportService.java:22`
  - Legacy export endpoints are present only as commented blocks:
    - `src/main/java/ro/uvt/pokedex/core/view/UserViewController.java:982`
    - `src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java:406`
- Status update (2026-03-03):
  - Legacy `CNFISScoringService` and `CNFISReport` were removed from the codebase.
  - Commented legacy CNFIS export blocks were removed from `UserViewController` and `AdminGroupController`.
  - Active CNFIS flow remains `CNFISScoringService2025` + `CNFISReportExportService`.
- Status update (2026-03-03, C01 Closure Slice):
  - Canonical CNFIS spec documented in `docs/c01-cnfis-rule-spec.md`.
  - Edge-case characterization tests expanded in `CNFISScoringService2025Test`.
  - Dead/noise locals removed from `CNFISScoringService2025` with no behavior change.

## Drift Matrix (C01)

| Drift ID | Drift description | Classification | Impact | Evidence |
|---|---|---|---|---|
| D01 | Subtype source changed: legacy reads `publication.getSubtype()`, 2025 reads `publication.getScopusSubtype()`. | risky | Different source fields can change classification outcome for the same publication if values diverge or one is null. | `CNFISScoringService.java:36,94` vs `CNFISScoringService2025.java:36,110,118` |
| D02 | Quartile mapping changed from 3 buckets (`isiRosu/isiGalben/isiAlb`) to 4 buckets (`isiQ1..isiQ4`). | intentional | This matches updated 2025 export columns and likely policy change; requires explicit mapping contract docs. | `CNFISReport.java:23-25` vs `CNFISReport2025.java:22-25`; export consumes Q1-Q4 at `CNFISReportExportService.java:93-100,238-245` |
| D03 | Proceedings logic diverged for `cp`: legacy always sets `isiProceedings`; 2025 gates by forum name (`IEEE`) and WOS id presence. | risky | Can flip outcomes materially for conference papers; likely correct for new rules but high regression sensitivity. | `CNFISScoringService.java:94-95` vs `CNFISScoringService2025.java:110-116` |
| D04 | New `ch` subtype branch exists only in 2025 and conditionally sets `isiProceedings` for Lecture Notes + WOS id. | intentional | New behavior extends coverage; absence in legacy confirms rule evolution. | `CNFISScoringService2025.java:118-123` |
| D05 | Year iteration behavior differs: legacy starts from `allowedYears.getLast()` and increments to `LAST_YEAR`; 2025 pops from list (`removeFirst`) and may stop early. | unknown | Could be equivalent today (single year in list), but behavior diverges if multi-year input is introduced. | `CNFISScoringService.java:62-90` vs `CNFISScoringService2025.java:69-107` |
| D06 | Empty `allowedYears` safety differs: legacy may throw if `coverDate` parse fails (calls `getLast()` without guard); 2025 falls back to `LAST_YEAR`. | risky | Legacy path is fragile on malformed dates; 2025 behavior is safer and deterministic. | `CNFISScoringService.java:41-43,62` vs `CNFISScoringService2025.java:41-43,69` |
| D07 | Category parsing changed: 2025 requires `category.split(\"-\")` with at least two parts, otherwise skips category; legacy does not enforce this format. | risky | Potential silent data loss if category naming does not include expected delimiter; can under-score publications. | `CNFISScoringService2025.java:56-61` vs `CNFISScoringService.java:55-58` |
| D08 | Category index handling changed from direct `category.contains(\"CCPI\")` to `catIndex` checks for `SCIE`/`SSCI`/`ESCI`/`AHCI` else `ERIH+`. | unknown | Looks like taxonomy normalization, but can reclassify edge categories unexpectedly. | `CNFISScoringService.java:79-86` vs `CNFISScoringService2025.java:77-99` |
| D09 | University-author count is unimplemented in legacy (`TODO`) but computed in 2025 using cache membership. | intentional | 2025 adds needed metric used by report output and avoids always-zero legacy behavior. | `CNFISScoringService.java:98` vs `CNFISScoringService2025.java:126` |
| D10 | Unused local variables (`bestPoints`, `bestYear`, `bestCategory`, `bestQuarter`) exist in both services. | harmless | Noise/maintenance overhead only; no behavioral impact. | `CNFISScoringService.java:25-28`; `CNFISScoringService2025.java:25-28` |

## Decision for C01

- Overall cluster verdict: `risky` drift (with intentional policy updates mixed in).
- Why:
  - Multiple business-rule divergences affect scoring outcomes (`D01`, `D03`, `D07`).
  - Runtime currently depends on 2025 path only, reducing immediate production exposure of legacy logic.
  - Legacy service/model were removed, but the drift analysis remains relevant as migration evidence and rule history.
- Status update (2026-03-03, H01-S06):
  - Characterization guards added in `src/test/java/ro/uvt/pokedex/core/service/reporting/CNFISScoringService2025Test.java`.
- Status update (2026-03-03, C01 Closure Slice):
  - C01 closure completed: canonical rule spec + edge-case tests + no-behavior cleanup applied.

## Recommended Follow-up (for next subtasks)

1. Completed: canonical CNFIS rule spec documented in `docs/c01-cnfis-rule-spec.md`.
2. Completed: targeted characterization tests added for malformed `coverDate`, `cp/ch` proceedings edges, null/blank categories, and missing subtype data.
3. Completed: legacy `CNFISScoringService` and `CNFISReport` remain removed and guarded by `verify-duplication-guardrails`.
4. Completed: unused local variables removed from `CNFISScoringService2025`.

## Cluster C03 Overview

- Files:
  - `src/main/resources/templates/admin/rankings-view.html`
  - `src/main/resources/templates/admin/rankings-view-bak.html`
- Similarity signal:
  - line-set overlap baseline: `common=67`, `union=270`, `jaccard=24.81%`
- Shared intent:
  - render ranking details for a selected journal in the admin flow.

## Drift Matrix (C03)

| Drift ID | Drift description | Classification | Impact | Evidence |
|---|---|---|---|---|
| C03-D01 | Presentation mode diverged: backup uses static tabular "Detailed Ranks"; active template uses charts and dynamic visual panels. | risky | Different UI mode changes how data is consumed; stale backup can mislead if accidentally restored/edited. | `rankings-view-bak.html:56,66` vs `rankings-view.html:58,60,73` |
| C03-D02 | Year handling differs: backup hardcodes `2015..2022`; active template derives years from model data (`score.ais` min/max). | risky | Backup silently drops years outside fixed range; active template adapts to current dataset. | `rankings-view-bak.html:70,76,82,88,94,100` vs `rankings-view.html:131-143` |
| C03-D03 | Active template adds large inline JS logic (`number_format`, chart config, dataset mapping) absent from backup. | intentional | Functional capability expanded in active file; backup is no longer behaviorally equivalent. | `rankings-view.html:102,145-356` |
| C03-D04 | Title binding diverged: backup static `<title>Rankings</title>` vs active data-bound title (`journal.name`). | harmless | Cosmetic/SEO-level difference; low runtime risk. | `rankings-view-bak.html:5` vs `rankings-view.html:5` |
| C03-D05 | Both files still share the same route/sidebar frame and journal metadata card structure. | harmless | Confirms they originated from same template lineage, but not sufficient for keeping both. | `rankings-view-bak.html:19-50` vs `rankings-view.html:19-50` |

## Decision for C03

- Overall cluster verdict: `risky` drift.
- Why:
  - Backup file is not a safe mirror of active behavior (static ranges and non-chart rendering).
  - Accidental edits to `-bak` can create false confidence during maintenance/review.
  - Keeping both files increases template drift surface without production benefit.
- Status update (2026-03-03):
  - `src/main/resources/templates/admin/rankings-view-bak.html` was deleted.
  - C03 is considered resolved; only `rankings-view.html` remains active.

## Recommended Follow-up (C03)

1. Add a lightweight repository guard to block new `*-bak.html` files under `src/main/resources/templates/**`.
2. Completed: same cleanup decision flow applied to C02 (`researchers-bak.html` deleted on 2026-03-03).

## Cluster C05 Overview

- Files:
  - `src/main/resources/templates/admin/rankings-view.html`
  - `src/main/resources/templates/user/wos-rankings-view.html`
- Similarity signal:
  - line-set overlap baseline: `common=197`, `union=260`, `jaccard=75.77%`
- Shared intent:
  - render WoS ranking charts by category (metrics + quartile views).

## Drift Matrix (C05)

| Drift ID | Drift description | Classification | Impact | Evidence |
|---|---|---|---|---|
| C05-D01 | High duplication of chart utility and rendering logic (`number_format`, chart config callbacks, quartile canvas naming). | risky | Any bugfix/style change requires parallel edits; drift likelihood is high. | `rankings-view.html:102,260,264,355` vs `wos-rankings-view.html:103,136,247,324` |
| C05-D02 | Year axis strategy diverges: admin computes dynamic range from `score.ais`; user template hardcodes `2015..2022`. | risky | Same underlying data can render on different year spans, producing inconsistent analytics UI. | `rankings-view.html:131-143` vs `wos-rankings-view.html:126` |
| C05-D03 | Dataset composition differs in quartile chart: admin includes `Q IF`/`Q IF Position`; user includes only `Q AIS` + `Q Position`. | unknown | May be role-intentional, but currently undocumented and easy to misinterpret as accidental drift. | `rankings-view.html:284,291` vs `wos-rankings-view.html:260` |
| C05-D04 | User view skips `general` category explicitly; admin iterates categories without that guard and uses separate score chart. | intentional | Different page design choices are plausible, but still require explicit contract to avoid regressions. | `wos-rankings-view.html:133-134` vs `rankings-view.html:145,260` |
| C05-D05 | Sidebar role differences (`admin-sidebar` vs `user-sidebar`) and title binding differ. | harmless | Expected role-specific layout variance; not a consolidation blocker for shared JS logic. | `rankings-view.html:19` vs `wos-rankings-view.html:19`; `rankings-view.html:5` vs `wos-rankings-view.html:5` |

## Decision for C05

- Overall cluster verdict: `risky` drift.
- Why:
  - Shared inline chart logic is large and mostly duplicated.
  - At least one behavior divergence (year range) can cause user-visible inconsistency.
  - Good low-friction consolidation candidate via shared JS module.
- Status update (2026-03-03):
  - `src/main/resources/templates/user/wos-rankings-view.html` was deleted.
  - User WoS rankings route now renders the kept template (`admin/rankings-view`).
  - C05 is considered resolved as duplicate removal.

## Recommended Follow-up (C05)

1. Validate cross-role UX acceptance for the shared WoS rankings view.
2. If role-specific UI is needed later, reintroduce variation via conditional sections instead of separate duplicate templates.

## Cluster C06 Overview

- Files:
  - `scripts/build-assets.js`
  - `scripts/verify-assets.js`
- Similarity signal:
  - line-set overlap baseline: `common=7`, `union=50`, `jaccard=14.00%`
- Shared intent:
  - validate presence of built frontend assets (`app.js`, `app.css`).

## Drift Matrix (C06)

| Drift ID | Drift description | Classification | Impact | Evidence |
|---|---|---|---|---|
| C06-D01 | Asset contract constants are duplicated in both scripts (`expected` array). | risky | Asset path changes can silently diverge between build and verify flows. | `build-assets.js:3-5` vs `verify-assets.js:3-5` |
| C06-D02 | Missing-file error semantics diverge (fallback warning path vs hard failure path). | intentional | Different command roles justify different outcomes, but contract should still be centralized. | `build-assets.js:8-16,22-23` vs `verify-assets.js:10-12` |
| C06-D03 | Build script optionally bypasses bundling if esbuild unavailable; verify script always enforces built assets. | intentional | Useful for dev ergonomics, but behavior coupling should be explicit in docs/tests. | `build-assets.js:18-24` vs `verify-assets.js:8-15` |
| C06-D04 | No shared helper means asset list changes require editing multiple files. | risky | Low blast radius but recurring maintenance tax. | `build-assets.js:3-5` and `verify-assets.js:3-5` |

## Decision for C06

- Overall cluster verdict: `medium` drift risk, `low` blast radius.
- Why:
  - Duplication is small but in a critical contract constant.
  - Consolidation is easy and low-risk (shared helper/module constant).
- Status update (2026-03-03):
  - Shared asset contract extracted to `scripts/assets-contract.js`.
  - `build-assets.js` and `verify-assets.js` now consume the same `expectedAssets` source.
- Status update (2026-03-03, H01-S06):
  - Regression guard coverage documented and validated via `npm run verify-assets` and `npm run verify-template-assets`.

## Recommended Follow-up (C06)

1. Completed: shared asset contract list extracted into `scripts/assets-contract.js`.
2. Completed: command behavior differences kept, but both scripts now consume shared constant.
3. Add a tiny smoke check in CI that runs `npm run verify-assets` after `npm run build`.

## Cluster C04 Overview

- Files:
  - `src/main/java/ro/uvt/pokedex/core/service/reporting/*ScoringService*.java` family
  - `src/main/java/ro/uvt/pokedex/core/service/reporting/AbstractForumScoringService.java`
  - `src/main/java/ro/uvt/pokedex/core/service/reporting/AbstractWoSForumScoringService.java`
  - `src/main/java/ro/uvt/pokedex/core/service/reporting/ScoringFactoryService.java`
- Shared intent:
  - compute indicator scores across domains (journal/conference/book/event/university/CNFIS) with reusable scoring primitives.

## Drift Matrix (C04)

| Drift ID | Drift description | Classification | Impact | Evidence |
|---|---|---|---|---|
| C04-D01 | Base-class family drift: two near-duplicate abstract scoring bases diverge in behavior and safety checks. | risky (resolved in Slice 3) | Common logic fixes can land in one base and not the other, creating cross-domain inconsistencies. | `AbstractForumScoringService.java` vs `AbstractWoSForumScoringService.java` (notably `isCategoryInDomain` and score creation paths) |
| C04-D02 | Category parsing safety differs between abstract bases: one uses `category.split(\"-\")[1]` without guard, the other guards invalid format. | risky (resolved in Slice 3) | Malformed category strings can fail/skip differently depending on which scorer path is used. | `AbstractForumScoringService.java:96` vs `AbstractWoSForumScoringService.java:97-100` |
| C04-D03 | Subtype source usage is inconsistent across scoring family: most scorers use `publication.getSubtype()`, CNFIS2025 now uses dual-read (`scopusSubtype` fallback `subtype`). | unknown (resolved in Slice 4) | Domain scorers may classify the same publication differently if ingestion fields diverge in future. | `AbstractForumScoringService.java:230`, `AbstractWoSForumScoringService.java:199` vs `CNFISScoringService2025.java:39,130-135` |
| C04-D04 | Delegation drift in combined CS scorer: `ComputerScienceBookService` is injected but `bk` handling is commented out in dispatcher. | risky (resolved in Slice 2) | Supported strategy can appear available but be unreachable in combined path, causing silent scoring gaps. | `ComputerScienceScoringService.java:25,57-62` |
| C04-D05 | Factory default behavior returns `null` when strategy is unmapped. | risky (resolved in S07) | Missing/invalid strategy can become runtime NPE instead of controlled failure. | `ScoringFactoryService.java:56` |
| C04-D06 | Copy/paste metadata drift in services (logger class targets and descriptions not aligned to class behavior). | harmless (resolved in Slice 5) | Debugging and maintenance clarity degrade; low direct scoring impact. | `CNCSISPublisherListService.java:25`, `ArtEventScoringService.java:25`; generic descriptions in `UniversityRankScoringService.java`, `AISJournalScoringService.java` |
| C04-D07 | Category-splitting logic patterns are inconsistent across concrete scorers (e.g., economics splits ad-hoc, WoS abstractions have separate parsing assumptions). | unknown (resolved for C04 scope) | Edge-category behavior may vary by strategy and be hard to reason about globally. | `EconomicsJournalScoringService.java:133-136` plus abstract parsing differences above |

## Decision for C04

- Overall cluster verdict: `risky` drift.
- Why:
  - Cross-cutting behavior is fragmented across duplicated abstract layers.
  - Failure semantics are not uniform (`null` factory default, guarded vs unguarded parsing).
  - Strategy dispatch and field-source contracts are inconsistent across scorers.
- Status update (2026-03-03, H01-S06):
  - Characterization guards added for current factory/dispatcher behavior:
    - `src/test/java/ro/uvt/pokedex/core/service/reporting/ScoringFactoryServiceTest.java`
    - `src/test/java/ro/uvt/pokedex/core/service/reporting/ComputerScienceScoringServiceTest.java`
- Status update (2026-03-03, H01-S07):
  - `ScoringFactoryService` null fallback removed; method now throws explicit `IllegalArgumentException` for null/unsupported strategies (C04-D05 resolved).
- Status update (2026-03-03, C04 Slice 2):
  - Combined `CS` strategy now routes `bk` and `ch` publication subtypes to `ComputerScienceBookService`.
  - Combined `CS` strategy now routes `Book` and `Book Series` activity forum types to `ComputerScienceBookService`.
  - C04-D04 is resolved by explicit dispatch alignment and regression tests.
- Status update (2026-03-03, C04 Slice 3):
  - Shared category parsing contract extracted (`ScoringCategorySupport`) and reused by both abstract scoring bases.
  - Economics scorer category split now uses shared helper extraction.
  - C04-D01 and C04-D02 resolved; C04-scoped part of D07 resolved.
- Status update (2026-03-03, C04 Slice 4):
  - Shared subtype-source policy extracted (`PublicationSubtypeSupport`) and applied across targeted reporting services.
  - Abstract `isArticleOrReview` checks and direct subtype reads now use dual-read semantics.
  - C04-D03 resolved.
- Status update (2026-03-03, C04 Slice 5):
  - Logger bindings corrected in `CNCSISPublisherListService` and `ArtEventScoringService`.
  - Stale descriptions corrected in `AISJournalScoringService` and `UniversityRankScoringService`.
  - C04-D06 resolved.

## Recommended Follow-up (C04)

1. Completed in C04 Slice 3: consolidated shared parsing/guard logic via `ScoringCategorySupport`.
2. Completed in H01-S07: replaced `ScoringFactoryService` `null` fallback with explicit `IllegalArgumentException`.
3. Completed in C04 Slice 4: aligned subtype policy across scoring family with `PublicationSubtypeSupport`.
4. Completed in C04 Slice 2: enabled explicit book dispatch (`bk/ch`, `Book/Book Series`) in `ComputerScienceScoringService`.
5. Completed in C04 Slice 5: cleaned logger targets/descriptions to remove misleading copy-paste artifacts.

## Resolved Decisions (2026-03-03)

- CNFIS subtype policy: dual-read with migration path.
  - `scopusSubtype` is preferred.
  - Fallback to `subtype` when `scopusSubtype` is blank/missing.
- Category parsing policy: resilient parsing.
  - Non-standard categories are no longer skipped only because delimiter format is missing.
  - CNFIS2025 now logs warnings and continues with safe best-effort parsing.
- Backup template policy: hard-forbidden in runtime template directories.
  - `scripts/verify-template-assets.js` now fails when `*-bak.html` exists under admin/user template roots.

## Remaining Unknowns

- None tracked for this H01 open-questions closure slice.

## H01-S08 Guardrail Status (2026-03-03)

- Added automated guardrail command: `npm run verify-duplication-guardrails`.
- Guardrails now enforce:
  - legacy CNFIS artifacts stay removed,
  - `ScoringFactoryService` has no null fallback,
  - `ComputerScienceScoringService` keeps aligned book dispatch,
  - build/verify scripts keep shared asset contract import.
- Guardrail command is wired into Gradle `check` and documented in `CONTRIBUTING.md`.
