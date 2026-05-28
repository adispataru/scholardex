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

- [ ] `H51` Mongo unique-index integrity sweep and project-wide auto-index-creation enablement.
  Goal: enable `spring.data.mongodb.auto-index-creation=true` project-wide without crashing startup on existing duplicate data.
  Deliverable: inventory of every `unique = true` Mongo index declared in the codebase (~15 collections including `userIndicatorResults`, `WosCategoryFact`, `WosMetricFact`, `WosImportEvent`, `WosJournalIdentity`, `ScopusPublicationFact`, `ScopusForumFact`, `ScopusAuthorFact`, `ScopusAffiliationFact`, `UserDefinedForumFact`, `UserDefinedPublicationFact`, `ScholardexSourceLink`, `ScholardexAuthorshipFact`, `ScholardexCitationFact`, `ScholardexIdentityConflict`, `ScholardexPublicationFact` 4x, `PublicationAuthorshipDecision`); per-collection duplicate audit and dedup policy; cleanup migration(s); the property flip; removal of the `ReportImportSessionIndexInitializer` shim once global creation is on.
  Exit criteria: startup creates every declared index without `DuplicateKeyException`; documented dedup policy per collection; no per-collection index shims remain.
  Dependency: surfaced while wiring `H50.1`; the `userIndicatorResults` failure on `uniq_user_indicator_mode` is the first known violation but others may exist.

- [ ] `H50` Individual report export / import with dry-run reconciliation.
  Goal: enable users to export a `UserIndividualReportRun` to xlsx/docx templates and to upload such files for a staged, per-report-type reconciliation against the existing DB (never auto-creates a run).
  Deliverable: canonical `ReportInstanceSnapshot` DTO, per-report-type xlsx/docx renderers and parsers, `ReportImportSession` Mongo model with four-bucket classification (identical / differs / new-auto / new-manual), import review UI committing through existing ingestion facades, and admin toggles in report definition settings.
  Exit criteria: at least one individual report type round-trips end-to-end (export → edit → import → reconcile → commit) with all four buckets exercised; commits route through existing facades with no new write path; blocked rows surface human-readable reasons; xlsx-formula and docx-macro inputs are rejected/sanitized.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

