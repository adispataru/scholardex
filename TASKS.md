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

- [ ] `H54` Ingestion pipeline & record-keeping rebuild.
  Goal: restructure ingestion around one principle — a thin human-authored layer is precious and backed up; everything else is a deterministic, rebuildable function of source files/APIs — with one writer per collection, natural-key upserts, enforced indexes, provenance, and rebuild-in-place of migrations.
  Deliverable: precious-vs-derived inventory and backup policy; `partialFilter` fixes for the four multikey-array unique indexes; canonical key-shape decisions for the two drift cases (`scholardex.citation_facts`, `scholardex.identity_conflicts`); `auto-index-creation=true` with index shims/maintenance services deleted and a boot-index integration test; event-ledger redesign (drop full-payload storage, key on `(source, entityType, sourceRecordId)`, supersede-on-hash-change); single-owner builders per stage with provenance fields; a `PipelineRebuildService`; double-ingest / supersede / rebuild-determinism contract tests; full wipe + reimport verification.
  Exit criteria: every declared unique index is created and matches at startup (subsumes `H51`); each derived collection has exactly one writer and a deterministic `rebuild()`; re-ingesting an unchanged payload is a no-op and a changed payload supersedes; a full wipe + reimport reproduces row counts and sampled scores, and a second rebuild is byte-identical; `scripts/h51-unique-index-duplicate-audit.js` reports zero drift and zero duplicates.
  Dependency: subsumes `H51`. Surfaced by the 2026-06-08 index/duplicate audit. Planning doc at `docs/tasks/active/h54-ingestion-pipeline-rebuild.md`.

- [x] `H51` Mongo unique-index integrity sweep and project-wide auto-index-creation enablement. (Satisfied by `H54.2`, 2026-06-09: `auto-index-creation=true`, all declared indexes build at startup with no `DuplicateKeyException`/conflict, `ReportImportSessionIndexInitializer` shim removed, audit reports 0 drift / 0 duplicates. Move to TASKS-done.md when `H54` is archived.)
  Goal: enable `spring.data.mongodb.auto-index-creation=true` project-wide without crashing startup on existing duplicate data.
  Deliverable: inventory of every `unique = true` Mongo index declared in the codebase (audit found 34 declared unique indexes across ~28 collections — more than the original ~15 estimate); per-collection duplicate audit and dedup policy; cleanup migration(s); the property flip; removal of the `ReportImportSessionIndexInitializer` shim once global creation is on. Tooling delivered: `scripts/h51-unique-index-duplicate-audit.js` (drift + duplicate audit).
  Exit criteria: startup creates every declared index without `DuplicateKeyException`; documented dedup policy per collection; no per-collection index shims remain.
  Dependency: surfaced while wiring `H50.1`; the `userIndicatorResults` failure on `uniq_user_indicator_mode` was the first known violation. 2026-06-08 audit additionally found `scopus.import_events` (~14x dup, no index created), an unbuildable `sparse` multikey index on `scholardex.forum_facts`, and 2 index spec-drift cases.

- [ ] `H50` Individual report export / import with dry-run reconciliation.
  Goal: enable users to export a `UserIndividualReportRun` to xlsx/docx templates and to upload such files for a staged, per-report-type reconciliation against the existing DB (never auto-creates a run).
  Deliverable: canonical `ReportInstanceSnapshot` DTO, per-report-type xlsx/docx renderers and parsers, `ReportImportSession` Mongo model with four-bucket classification (identical / differs / new-auto / new-manual), import review UI committing through existing ingestion facades, and admin toggles in report definition settings.
  Exit criteria: at least one individual report type round-trips end-to-end (export → edit → import → reconcile → commit) with all four buckets exercised; commits route through existing facades with no new write path; blocked rows surface human-readable reasons; xlsx-formula and docx-macro inputs are rejected/sanitized.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.
