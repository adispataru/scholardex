# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.

- [ ] `H59` Delegated researcher-report viewing for admins and supervisors.
  Goal: let a `PLATFORM_ADMIN` or `SUPERVISOR` open the exact individual evaluation report a specific researcher sees (read-only by default) and trigger an attributed refresh, reusing the already user-parameterized report engine unchanged.
  Deliverable: a `ResearcherAccessService` authorization gate (admin = all; supervisor = researchers reachable from their supervised subtree — by current department affiliation or current group membership — as resolved by `SupervisorWorkspaceService`), a dedicated shared `/reports/researcher/**` controller (picker → list → read-only view → refresh → export), a read-only `findLatestRun` resolver so viewing never mutates, and a `triggeredByEmail` provenance field on `UserIndividualReportRun` for delegated refreshes.
  Exit criteria: admin/in-scope-supervisor can view+refresh with numbers/export matching the researcher's own output; supervisor scope spans department-head, division-head, and group-supervisor relations (group-only supervisors included); out-of-scope access is forbidden and out-of-scope researchers never appear in a supervisor's picker; the view path writes nothing when no run exists; delegated refresh records actor ≠ owner and surfaces it; private named snapshots stay unreachable.
  Dependency: none hard; builds on the supervisor/org-scope model (`SupervisorWorkspaceService`, `GroupAccessService`, `DepartmentAffiliation`). Planning doc at `docs/tasks/active/h59-delegated-researcher-report-viewing.md`.

- [ ] `H50` Individual report export / import with dry-run reconciliation.
  Goal: enable users to export a `UserIndividualReportRun` to xlsx/docx templates and to upload such files for a staged, per-report-type reconciliation against the existing DB (never auto-creates a run).
  Deliverable: canonical `ReportInstanceSnapshot` DTO, per-report-type xlsx/docx renderers and parsers, `ReportImportSession` Mongo model with four-bucket classification (identical / differs / new-auto / new-manual), import review UI committing through existing ingestion facades, and admin toggles in report definition settings.
  Exit criteria: at least one individual report type round-trips end-to-end (export → edit → import → reconcile → commit) with all four buckets exercised; commits route through existing facades with no new write path; blocked rows surface human-readable reasons; xlsx-formula and docx-macro inputs are rejected/sanitized.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.
