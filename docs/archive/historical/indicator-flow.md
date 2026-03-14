# `/user/indicators/apply/{id}`, `/user/individual-reports/view/{id}`, and `/admin/groups/{gId}/reports/view/{repId}`
Status: historical archived workflow note; retained for task traceability only.

## Scope
This document reflects the persisted-score architecture for user indicator evaluation, user report runs, and admin group report runs.

Goals:
- avoid recomputing indicator scores on every page load,
- persist indicator results in DB,
- persist individual report runs with links to immutable indicator-result snapshots,
- persist group report runs so group score tables are not recomputed on each view,
- expose manual refresh controls for both apply and report flows.

## Current Architecture

## 1) Indicator apply flow
Route:
- `GET /user/indicators/apply/{id}`

Behavior:
- `UserIndicatorResultService#getOrCreateLatest(userEmail, indicatorId)` is used.
- The service loads `UserIndicatorResult` with key `(userEmail, indicatorId, mode=LATEST)`.
- If found, payload is deserialized and rendered directly.
- If missing, result is computed once via `UserReportFacade#buildIndicatorApplyView`, persisted as `LATEST`, then rendered.

Manual refresh:
- `POST /user/indicators/apply/{id}/refresh`
- forces recomputation and overwrites `LATEST` payload (`refreshVersion` increments).

## 2) Individual report flow
Route:
- `GET /user/individual-reports/view/{id}`

Behavior:
- `UserIndividualReportRunService#getOrCreateLatestRun(userEmail, reportId)` is used.
- The service loads newest `UserIndividualReportRun` by `(userEmail, reportDefinitionId, createdAt desc)`.
- If missing, it builds a new run:
  - ensures each report indicator has a `LATEST` result,
  - creates immutable `SNAPSHOT` `UserIndicatorResult` records linked to the report,
  - computes criterion scores from linked indicator summaries,
  - persists run as `READY`/`PARTIAL`/`FAILED`.

Manual refresh:
- `POST /user/individual-reports/view/{id}/refresh`
- creates a brand-new immutable run (new snapshot links).

## 3) Group individual report flow
Route:
- `GET /admin/groups/{gId}/reports/view/{repId}`

Behavior:
- `GroupReportFacade#buildGroupIndividualReportView(groupId, reportId)` first loads latest persisted `GroupIndividualReportRun` by `(groupId, reportDefinitionId, createdAt desc)`.
- If found, it reuses persisted `researcherScores` + `criteriaThresholds`.
- If missing, it computes once with existing scoring services, persists a run, then renders.

Manual refresh:
- `POST /admin/groups/{gId}/reports/view/{repId}/refresh`
- forces recomputation and stores a brand-new run snapshot.

## Persisted Data Structures

## 1) `UserIndicatorResult` (`userIndicatorResults`)
Purpose:
- Stores computed indicator payloads.
- Supports mutable latest records and immutable snapshots.

Fields:
- identity/context: `id`, `userEmail`, `researcherId`, `indicatorId`
- lifecycle: `mode` (`LATEST|SNAPSHOT`), `sourceType` (`APPLY_PAGE|REPORT_RUN`), `sourceReportId`
- payload: `viewName`, `rawGraph` (serialized full payload)
- summary: `totalScore`, `totalCount`, `quarterLabels`, `quarterValues`
- metadata: `fingerprint`, `createdAt`, `updatedAt`, `refreshVersion`

Index:
- unique compound index on `(userEmail, indicatorId, mode)`.

## 2) `UserIndividualReportRun` (`userIndividualReportRuns`)
Purpose:
- Immutable user-specific report execution record.
- Links to indicator-result snapshots.

Fields:
- identity/context: `id`, `userEmail`, `researcherId`, `reportDefinitionId`
- links/scores: `indicatorResultIds`, `indicatorScoresByIndicatorId`, `criteriaScores`
- lifecycle: `createdAt`, `status` (`READY|PARTIAL|FAILED`), `buildErrors`

Index:
- `(userEmail, reportDefinitionId, createdAt desc)`.

## 3) `GroupIndividualReportRun` (`groupIndividualReportRuns`)
Purpose:
- Immutable group-report execution record used by `/admin/groups/{gId}/reports/view/{repId}`.

Fields:
- identity/context: `id`, `groupId`, `reportDefinitionId`
- scores: `researcherScores` (`researcherId -> criterionIndex -> score`), `criteriaThresholds` (`criterionIndex -> positionName -> threshold`)
- lifecycle: `createdAt`, `status` (`READY|PARTIAL|FAILED`), `buildErrors`

Index:
- `(groupId, reportDefinitionId, createdAt desc)`.

## DTO / Service Contracts

## 1) `IndicatorApplyResultDto`
- `resultId`, `indicatorId`, `viewName`, `rawGraph`, `summary`, `source`, timestamps, `refreshVersion`.

## 2) `IndividualReportRunDto`
- `runId`, `reportDefinitionId`, resolved `indicatorResults`, `indicatorScoresByIndicatorId`, `criteriaScores`, `createdAt`, `source`.

## 3) Services
- `UserIndicatorResultService`
  - `getOrCreateLatest`
  - `refreshLatest`
  - `createSnapshotFromLatest`
  - `getById`
- `UserIndividualReportRunService`
  - `getOrCreateLatestRun`
  - `refreshRun`
- `GroupReportFacade`
  - `buildGroupIndividualReportView` (get-or-create latest persisted run)
  - `refreshGroupIndividualReportView` (create new run)

## Critical Assessment

## What improved
1. Recompute pressure reduced
- Apply pages no longer require recomputation on each request.

2. Traceability added
- Indicator outputs and report runs now have persistent IDs and timestamps.

3. Report reproducibility improved
- Run records link to immutable indicator snapshots.

4. Operational control
- Manual refresh enables explicit recompute timing.

5. Group-view stability
- Admin group report pages now reuse persisted runs instead of recomputing all researcher scores on every GET.

6. Criterion-driven rendering
- Individual report view no longer exposes an aggregate "total score for all indicators" card.
- Report presentation is criterion-based; if a total is desired it should be modeled as a criterion.

## Remaining risks / tradeoffs
1. Stored payload is serialized raw graph
- `rawGraph` is persisted as serialized polymorphic JSON.
- Flexible, but schema evolution/migration must be handled carefully.

2. Summary-based report scoring
- Run scoring currently uses indicator total summaries linked from snapshots.
- If report-specific filters diverge from apply computation, semantics must be reconciled explicitly.

3. Existing score-key caveats persist
- Title-keyed scoring maps and sentinel `"total"` patterns in legacy compute payload still exist inside stored graph.

4. Manual refresh policy
- Staleness is expected until explicit refresh.
- Fingerprint is stored for future optional auto-invalidation but not active.

5. Group runs currently snapshot score matrices, not per-indicator sub-doc links
- Group flow persists computed matrices directly for responsiveness.
- If per-indicator auditability is required for group runs, a future evolution should add group indicator-result snapshots similar to `UserIndicatorResult`.

## Data Lifecycle

1. Definition layer
- Admin manages `Indicator` and `IndividualReport` templates.

2. Latest indicator result layer
- First apply request materializes `LATEST` record.
- Manual refresh mutates latest result.

3. Report run layer
- First report view materializes a run and snapshot links.
- Manual refresh creates a new immutable run.

4. Group report run layer
- First admin group report view materializes `GroupIndividualReportRun` if missing.
- Next reads reuse latest persisted run.
- Manual refresh creates a new immutable run.

## Next Hardening Steps
1. Replace title-keyed score maps with stable IDs in compute payload.
2. Move from polymorphic raw payload toward explicit per-branch persisted DTO schema.
3. Introduce optional fingerprint-based auto-recompute mode (feature-flagged).
4. Add retention policy for old runs/snapshots.
5. Add stronger validation around indicator formulas and year-range expressions.
