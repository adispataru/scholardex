# H50 Individual Report Export / Import

**Status:** Planning
**Created:** 2026-05-13

## Purpose

Let a `UserIndividualReportRun` instance be exported to a user-editable template (xlsx and docx), and let a user upload such a file back to drive a **dry-run reconciliation** against the existing database. Import never creates a `UserIndividualReportRun`; it produces a staged `ReportImportSession` the user reviews and commits row-by-row through the existing ingestion facades.

## Scope

- Per-report-instance export (operates on `UserIndividualReportRun`, not on the `IndividualReport` definition).
- Per-report-type reconciliation logic (no monolithic reconciler).
- Two template formats: xlsx and docx. xlsx is primary; docx ships as export-only in the first slice and gains import support once content-control parsing is proven.
- Toggles for enable/disable export and import live in the report definition settings (`IndividualReport` config surface).

Out of scope for this `Hxx`:

- Group reports (`GroupIndividualReportRun`). Same shape may apply later, but not in this slice.
- Auto-resolution of blocked rows. Rows that can't be imported directly are surfaced with a human-readable blocker reason; the user is told to perform the action manually through the existing wizard/admin surface. No deep-linking or `returnTo` plumbing.
- Admin-uploaded custom templates. Templates ship as repo resources; admin upload is deferred.

## Architecture

### Canonical snapshot

A versioned DTO `ReportInstanceSnapshot` is the single source of truth round-tripped between formats:

```
ReportInstanceSnapshot {
  schemaVersion: int,
  reportDefinitionId: String,
  sourceRunId: String?,            // null when constructed from a user-uploaded file
  exportedAt: Instant,
  exportedBy: String,
  items: List<SnapshotItem>        // publications, activities, forum entries, etc.
}
```

`SnapshotItem` is a sealed hierarchy keyed by `entityType`. Each report type declares which entity types its snapshot contains.

### Exporter

Per report type, register a pair of renderers:

- `XlsxReportRenderer` — uses named ranges as stable anchors; chrome cells are write-protected.
- `DocxReportRenderer` — uses content controls (SDT tags) as stable anchors; chrome paragraphs are read-only.

Templates are checked-in resource files under `src/main/resources/report-templates/<reportType>/template.xlsx` and `template.docx`. Both renderers populate from `ReportInstanceSnapshot`.

### Importer (dry-run reconciliation)

Parse a user-uploaded xlsx (and later docx) into a `ReportInstanceSnapshot`, then run per-report-type reconciliation that classifies each `SnapshotItem` into one of four buckets:

| Bucket | Meaning | UI affordance |
|---|---|---|
| `IDENTICAL` | Entity already in DB and all snapshot fields agree. | Skip (informational count only). |
| `DIFFERS` | Entity matched by identity key, one or more fields diverge. | Field-level diff; user picks per row: accept incoming / keep existing. |
| `NEW_AUTO` | No DB match and all referenced entities resolve (forum/authors/etc). | One-click "import selected" via the matching existing facade. |
| `NEW_MANUAL` | No DB match and at least one blocker (unknown forum, ambiguous author, missing DOI, etc). | Show row with `blockerReason`. User is instructed to resolve manually through the existing wizard/admin surface, then re-upload. |

Identity-key strategy (per entity type, documented alongside the reconciler):

- Publication: DOI > normalized title + year.
- Forum: canonical id > normalized name + type.
- Author: scholardex id > normalized name + affiliation.

Commit step fans out to existing ingestion code (`PublicationWizardFacade`, `IncrementalUpdateUploadFacade`, `UserDefinedCanonicalizationService`). H50 does not introduce a new write path.

### `ReportImportSession` storage

Mongo document, consistent with the rest of `model/reporting/*` (which is all `@Document`). Not a Flyway migration.

```
ReportImportSession {
  @Id id: String,
  userEmail: String,
  reportDefinitionId: String,
  sourceRunId: String?,
  uploadedFilename: String,
  format: enum { XLSX, DOCX },
  createdAt: Instant,
  expiresAt: Instant,             // TTL index — auto-cleanup of abandoned sessions
  status: enum { PENDING_REVIEW, PARTIALLY_COMMITTED, COMMITTED, DISCARDED, EXPIRED },
  items: List<ReportImportItem>
}

ReportImportItem {
  itemKey: String,                // stable per-row id for UI selection
  entityType: enum { PUBLICATION, FORUM, ACTIVITY, ... },
  bucket: enum { IDENTICAL, DIFFERS, NEW_AUTO, NEW_MANUAL },
  incomingSnapshot: SnapshotItem,
  matchedEntityId: String?,
  fieldDiffs: List<FieldDiff>?,   // populated when bucket == DIFFERS
  blockerReason: String?,         // populated when bucket == NEW_MANUAL; user-facing copy
  userDecision: enum { PENDING, ACCEPT_INCOMING, KEEP_EXISTING, REJECT },
  committedEntityId: String?,
  commitError: String?
}
```

TTL: `expiresAt` set to `createdAt + 7 days` initially. Mongo TTL index handles cleanup. A user can refresh expiry by reopening the session.

### Reconciler registration

Per report type, implement and register:

```
ReportTypeImportSupport {
  String reportTypeKey();
  List<SnapshotItem> parseXlsx(InputStream);
  List<SnapshotItem> parseDocx(InputStream);                  // optional in v1
  ReportImportItem reconcileItem(SnapshotItem);               // produces bucket + diff
  CommitResult commit(ReportImportItem, UserContext);         // routes to existing facade
}
```

`IndividualReportsManagementFacade` (existing) gains a registry lookup by report definition's type key.

## Vocabulary: relation to existing Wos conflict tables

The repo already has `WosIdentityConflict` and `WosFactConflict` (both Mongo). Naming axis (identity vs fact) is genuinely useful and our `DIFFERS` (fact divergence) vs `NEW_MANUAL` blocked-on-identity (identity ambiguity) bucket split mirrors it. We intentionally do **not** reuse those entities because their semantics are post-commit observability records emitted after automated ingestion picks a winner, while `ReportImportItem` is pre-commit staging awaiting a user decision.

## Report definition settings

Add to `IndividualReport` (Mongo, additive — no migration needed for new fields):

- `exportEnabled: Boolean` (default true)
- `importEnabled: Boolean` (default false — opt-in per report)
- `allowedExportFormats: Set<Format>` (default `{XLSX, DOCX}`)
- `allowedImportFormats: Set<Format>` (default `{XLSX}`)

Surface in the existing admin report-edit screen (`AdminIndividualReportsController` / report edit template).

## UI surfaces

- **Report instance page (user workspace)**: "Export" dropdown listing enabled formats; "Import from file" button when import is enabled.
- **Import review page**: table grouped by bucket with per-row decisions; bucket counts at the top; "Commit selected" button at the bottom; resumable across sessions while the import session is live.
- **Admin report edit**: the four toggle controls above, plus a "Download blank template" link per format for tester convenience.

## Security

- Strip docx macros on parse.
- Reject xlsx cells whose value begins with `=` unless the cell is in a known formula-allowed named range (none in v1; all incoming values are data).
- Enforce upload size cap (suggest 10 MB initial).
- Import permission is independent of export permission; importing is restricted to the run's owner (and admins).

## Open Questions

- Should the user be able to import a file produced by a *different* report definition than the one they're viewing? Default proposal: no — refuse if `reportDefinitionId` in the snapshot doesn't match the current report. Confirm before implementation.
- For `DIFFERS` rows, should there be a "merge" option (accept some fields, keep others) or only "accept incoming / keep existing"? Default proposal: binary in v1, field-level merge deferred.
- Snapshot `schemaVersion` migration policy: linear migrations, or refuse to import older versions? Default proposal: linear migrations, with a hard floor below which import is refused with a clear message.

## Import flow REVISED (2026-05-19)

The original 4-bucket reconciliation + commit-to-facades design is **superseded**. Data onboarding
(new publications/citations) stays with the existing importers (Scopus, user-defined, Publish-or-Perish).
The import flow is now a **read-only score verification**:

1. User downloads the export (pre-filled with platform data), corrects it (categories, author counts,
   add/remove rows), re-uploads.
2. Parse the uploaded xlsx and **evaluate its formulas** (POI `FormulaEvaluator`) so the Punctaj
   columns recompute from the user's entered values.
3. Compare the file's per-item scores against the **latest report run's** platform scores, matched by
   title.
4. Show a comparison: per-item file-score vs platform-score, flag the ones that differ, tally the
   "correct points" (where they agree) and the totals.
5. **No commit, no DB writes.** Transient — nothing persisted (the `ReportImportSession` model from
   H50.1 is unused for now).

Decisions (2026-05-19): per-item + totals granularity; transient (no session persistence); evaluate
formulas for the file score; **publications + citations first, activities later**.

## Follow-up: run-backed export and criteria verification

Roast findings (2026-06-04): the current export/verify flow can drift from what the run page tells the
user. Export has no explicit run id, and snapshot/projector paths can recompute from live state or latest
indicator results instead of rendering the persisted `UserIndividualReportRun` that the user is looking at.
Criteria/template mappings can also silently skip indicators when admin config is incomplete, and several
different failure modes collapse into the same not-found response.

### Commit 1 — `fix: export individual reports from the selected run`

Goal: exporting must use the same durable run data the user sees on the report page. Live recomputation is
allowed only when creating a new run, not while exporting an existing one.

Tasks:

- Add a `run` request parameter to `/user/evaluation/export`; the report page export link must include the
  displayed `UserIndividualReportRun.id`. A latest-run fallback is acceptable only for old links and must be
  explicit in tests.
- Change `ReportExportFacade` to resolve and authorize the requested run for the current user/report before
  rendering. Reject missing, wrong-user, wrong-report, and non-exportable run states as distinct outcomes.
- Refactor snapshot creation so export is built from `UserIndividualReportRun.indicatorResultIds`,
  `indicatorScoresByIndicatorId`, and `criteriaScores`, plus the persisted `UserIndicatorResult` snapshots.
  The export path must not call live scoring/projector sources such as latest indicator result lookup or
  scientific-production reads to decide which rows belong in the workbook.
- Split "read/compute current data" from "shape rows for a template". Existing publication, citation, and
  activity projectors should accept run-backed indicator snapshots/raw graphs and only shape rows/cells.
- Preserve the template formula behavior: rows and run-computed scores are placed from the selected run, and
  Excel criteria formulas may still compute final criteria cells after the workbook is opened/evaluated.

Tests:

- `ReportExportFacade` proves the requested run id is used, wrong-user/wrong-report runs are rejected, and
  live latest-result lookup is not used by the export path.
- Snapshot/projector tests prove persisted publication, citation, and activity indicator results populate the
  expected workbook rows and scores.
- Workspace controller contract test proves the export link carries `run={runId}` and the controller passes it
  through.

Verification command target: focused facade/controller/snapshot tests first, then `./gradlew test`.

### Commit 2 — `fix: verify uploaded reports against the displayed run`

Goal: "Verify from file" must compare the uploaded workbook against the run the user is looking at, and must
also show whether the file diverges from the latest/current run when that is different. A configured report
must either verify deterministically or tell the admin/user exactly why it cannot.

Tasks:

- Add a `run` request parameter to the verify/upload endpoint; the report page "Verify from file" form must
  submit the displayed `UserIndividualReportRun.id`.
- Load the displayed run as the primary platform comparison. Parse and evaluate workbook formulas, then
  compare file scores to that run's persisted item scores and totals.
- Add an optional "current run" comparison when the latest run for the same report/user differs from the
  displayed run. The UI should make the two comparisons explicit: file vs displayed run, and displayed run vs
  current run.
- Keep criteria evaluation split correctly: the workbook may apply template/precomputed formulas after rows
  and scores are placed, but platform-side criteria comparison comes from the persisted run criteria scores.
- Add an export-readiness validator for `IndividualReport` + registered report type binding:
  `reportTypeKey` exists, requested format is supported, every criterion indicator reference resolves to a
  report indicator, and every exportable criterion indicator has a valid role/block mapping or an explicit
  "not exported by template" marker.
- Surface readiness warnings/errors in the admin report edit form near indicator role/block configuration.
  Saving a report with broken export config should either fail validation or clearly mark export unavailable.
- Disable or annotate the user export action when the selected report/run is not export-ready.
- Replace `Optional.empty`/generic not-found responses in export with a typed result or error enum, then map
  controller responses distinctly for export and verify: missing report/run, unsupported report type/format,
  forbidden run, stale/non-ready run, invalid export configuration, invalid workbook, and formula-evaluation
  failure.
- Add a guard test that missing role/block mapping for a criteria indicator fails readiness instead of
  producing a workbook with silently missing data.

Tests:

- Import verification tests prove uploaded file scores are compared to the displayed run, not an implicit
  latest/live run.
- Comparison-service tests cover the three-way state: uploaded file, displayed run, and newer current run.
- Readiness validator tests cover unregistered type, unsupported format, unresolved criterion indicator,
  missing role/block mapping, and explicit template exclusion.
- Admin controller/model tests show readiness feedback is available to the edit template.
- User controller tests cover distinct response statuses/messages for each export and verify failure reason.
- Renderer/facade regression test proves a misconfigured criteria indicator cannot be skipped silently.

Verification command target: focused import-verification/readiness/facade/controller tests first, then
`./gradlew test`.

## Proposed Slicing

- **H50.1** — `ReportInstanceSnapshot` DTO, `ReportImportSession` Mongo model, registry abstraction (`ReportTypeImportSupport`), wiring stubs. (done)
- **H50.2** — xlsx exporter + template for `informatica-2016`. End-to-end download. (done)
- **H50.3** — xlsx **score-verification import** (revised): parse + evaluate, compare file vs latest run, comparison UI. Publications + citations first.
  - **H50.3.a** — parse uploaded xlsx → per-item scores (publications), evaluate formulas; add `score` to platform snapshot items.
  - **H50.3.b** — comparison service (per-item match by title + totals + correct-points tally).
  - **H50.3.c** — upload endpoint, comparison page, "Verify from file" button, `importEnabled` setting.
  - **H50.3.d** — citations comparison.
  - **H50.3.e** — activities comparison.
- **H50.4** — docx exporter (export-only).
- **H50.5** — extend to remaining individual report types.
- **H50.6** — docx import.

## Verification Plan (per slice)

- Unit: snapshot round-trip (snapshot → xlsx → snapshot equality), reconciler bucket classification with fixture DB states for each bucket, identity-key normalization.
- Integration: full export/import cycle against a seeded DB hitting each of the four buckets at least once.
- UI smoke: bucket counts render, per-row decision persists, commit routes to the correct facade, expired session shows a clear re-upload prompt.
- Security: upload of a docx with a macro is parsed clean; upload of an xlsx with a leading-`=` cell is rejected with a user-facing message; oversized upload rejected.
