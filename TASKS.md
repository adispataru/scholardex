# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Active

- [ ] `H76` WoS CPCI onboarding — **MVP done + live; only blocked/attributed remainders open.**
  Plan: `docs/tasks/active/h76-wos-cpci-onboarding.md`. Background: `wosForumIds` come only from the WoS **journal**
  MJL/JCR, so WoS-indexed *conferences* were misclassified as non-WoS (1,014 Scopus-only conference forums),
  undercounting the WoS h-index (`H67`) — material for CS.
  **S1+S2 DONE + live (2026-06-25):** DOI→publication→forum (then ISSN/ISBN/title) matcher over UVT's own WoS
  **Records** export; `WosCpciOnboardingService` + `POST /admin/initialization/wos/cpci/{dryRun,apply}`. 1,302/1,984
  UVT proceedings matched → **211 conference forums tagged `wosCpciIndexed=true`** (new boolean, read only by
  `applyCitationSourceSplit`). Projection refresh lifted `wos_citation_count` **+9,909 across +503 pubs**.
  **Remaining (both out of the MVP's hands):**
  - **Physics/FF forum-scoring** — `wosCpciIndexed` feeds ONLY the citation source-split, NOT forum-membership/WoS-forum
    *paper* scoring; wiring the paper-count read to honor the flag (or projecting a CPCI membership row) is **`H65`
    work**.
  - **S3 broad citing coverage — BLOCKED on a WoS API key.** The UVT-scoped roster covers venues UVT publishes in; the
    full WoS citation graph (all citing venues) needs a programmatic Core-Collection pull (UI Records export caps
    ~1,000/file). Revisit when an Expanded/Starter API key is available.

- [ ] `H68` Advanced criteria / threshold extensions (foundational, from the standards assessment).
  Goal: extend the criteria engine for recurring patterns — **post-PhD temporal anchor**, per-indicator/
  per-group **caps (plafoane)**, **best-of single-indicator assignment**, **count + point** mixed criteria,
  **Da/Nu** qualitative gates, cross-criterion compensation. Modest config-level extensions on the existing
  per-position threshold model. Consumers: FSGC, drept, FLIT, FAD, FSP, sport, fizica.
  **SCOPED 2026-06-30** (`docs/tasks/active/h68-criteria-extensions.md`).
  **DONE (2026-06-30, `bdd4379b`):** per-indicator caps (`applyPointsCap`); slice 1 — criterion `mode`
  count-vs-points + unified the two criterion-score paths (fixed the H65 weights bug: was applied at render/export but
  not at compute); slice 2 — criterion-level cap (`maxTotal`). `phdAwardYear` profile field added (`6fe81f97`) as the
  post-PhD anchor's data hook.
  **Remaining — all DEFERRED to first consumer** (ambiguous semantics until a real report needs them): post-PhD
  temporal anchor (field exists, no scoring use yet), Da/Nu qualitative gates, best-of single-indicator assignment,
  cross-criterion compensation. No active consumer — revisit when FSGC/drept/FLIT/FAD/FSP/sport/fizica needs one.

- [ ] `H50` Individual report export / read-only score-verification import.
  **STATUS (2026-06-30): mostly done — H62/H65 overtook most of the "remaining" list. The genuine gap is docx *import*
  verification (H50.6). Entry below refreshed.**
  Goal: enable users to export a `UserIndividualReportRun` to a per-report-type template and to upload a corrected file for a transient, read-only score verification (file scores vs the persisted run; never writes, never auto-creates a run). The original 4-bucket reconcile/commit design was superseded (2026-05-19) and its dead code removed (2026-06-14).
  Done: `ReportInstanceSnapshot` DTO + registry (H50.1); xlsx exporter + template for `informatica-2016` (H50.2); xlsx score-verification import across publications/citations/activities — parse+evaluate, per-item+totals comparison UI, `importEnabled` toggle (H50.3); run-backed export, verify-vs-displayed-run, `ReportExportReadinessValidator`, and typed `ExportFailureReason` mapping.
  Done since (via H62/H65): **docx export (H50.4)** is wired — `ReportExportFacade` renders any format the support declares, with the DOCX content-type + `TemplateDocxRenderer`. **Report-type coverage (H50.5) largely done** — bindings now exist for `informatica-2016`, `matematica-2016`, **`feaa-2024`**, **`fizica-ff`** (4 of the report types), each with a `ReportTypeImportSupport` that renders docx.
  Remaining: **docx *import*/verify (H50.6)** — the docx supports' `parse()` still throw `UnsupportedOperationException` (Fizica/Feaa), so score-verification upload is xlsx-only; implement docx parsing for the docx report types. Plus any report definitions still lacking a binding (the remainder of H50.5).
  Exit criteria: each supported report type round-trips export → edit → upload → read-only verify (file-vs-run per-item + totals, no DB writes); xlsx-formula injection and docx-macro inputs are rejected/sanitized; misconfigured export mappings fail readiness instead of silently dropping rows.
  Dependency: none direct; planning doc at `docs/tasks/active/h50-individual-report-export-import.md`.

- [ ] `H80` H79 production rollout (get the Informatică 2026 report live). **SCOPED 2026-07-04**
  (`docs/tasks/active/h80-h79-production-rollout.md`). H79 code is merged + verified locally; the ingest→project pipeline
  (`PipelineRebuildService`) already folds in DOAJ APC + the OPENALEX membership projection + the project projection —
  the **only code gap** is that the OpenAlex source-APC derivation is a standalone endpoint, not a pipeline step.
  - **Slice A (code) — DONE 2026-07-04:** shared `OpenAlexSourceApcAggregator`; `OpenAlexBulkImportService.importAll`
    folds the per-venue APC derivation into the works/citers stream (mirroring the `referenced` institution-id threading)
    → `openalex.source_facts` produced in-DAG before the stage-4 projection. Standalone service + endpoint kept for
    manual re-runs. `BulkImportResult` gained `apcSources`/`apcFeeJournals` (logged in the rebuild). Unit-tested; full
    suite green. No pipeline-wiring change (the DAG already calls `importAll`).
  - **Slice B (ops) — DONE 2026-07-04:** the local `scholardex` Mongo/PG **is** the prod DB, so the full rebuild
    (`rebuildAllDerived?confirmation=RESET&reingest=true`, caffeinated+daemonized, ~34 min, 0 errors) **was** the rollout.
    Fold produced `source_facts` in-DAG (`apcSources=18575 apcFeeJournals=2140` from cleared-0); projection emitted 2,140
    OPENALEX apc=true rows; MDPI *Electronics* → `isFeeJournal=true`; florin's Electronics zeroed in FV Info 2026 (2016
    unchanged). Deploy to stage/prod is a later **data migration** (gated on Informatică backlog + public-UI polish).
  - **Slice C (remaining Informatică 2026 rules, re-checked vs the standard 2026-07-04):** only **C1 posters/system-demos**
    remains as a real platform feature (`id_parA82` — same machinery as workshops, needs a poster/demo detection signal).
    **C2 per-pub-year APC — DROPPED** (standard keys APC to "momentul depunerii dosarului" = current state). **C3 b↔c 20%
    compensation — NOT a platform feature** (`id_parA118` is discretionary "se pot modifica doar prin transfer" — a
    committee eligibility exception like perspectiva-a ethics; platform already gives accurate b/c totals + DA/NU).
