# Project Tasks (High-Level)

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.
Done history moved to `TASKS-done.md`.

## Backlog

- [ ] `H20` Google Scholar (PoP) user-onboarding into Scholardex.
  Goal: support user-triggered Google Scholar imports from Publish-or-Perish exports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: user-operation onboarding flow for PoP exports (upload/import from user surface) with parser + ingest adapter into Scholar-source events/facts and linker integration with Scholardex entities.
  Exit criteria: Scholar imported records from user operations link deterministically and preserve source lineage without mutating non-owned fields; no separate non-user onboarding path is required in this slice.
  Dependency: execute after `H19.9` citation canonicalization so imported Scholar citation edges are canonical-ID compatible at ingest time.

- [ ] `H29` Admin incremental source updates from uploaded WoS and Scopus files.
  Goal: add an operator page parallel to `/admin/initialization` for incremental source updates, starting with WoS and Scopus, where each run is driven by a single uploaded file instead of pre-staged data on disk.
  Deliverable: admin upload/update surface plus source-specific incremental ingest orchestration for WoS and Scopus, accepting one file per operation (`WoS JSON` or government Excel for WoS; `Scopus JSON` for Scopus) and routing the uploaded payload through the existing canonical maintenance pipeline needed for safe incremental updates.
  Exit criteria: operators can trigger incremental WoS and Scopus updates from the browser without relying on filesystem-resident import inputs; file validation and operator feedback are explicit; incremental runs remain replay-safe, scoped to the uploaded payload, and aligned with existing fact/canonical/projection maintenance contracts.
  Subtasks:
  - [x] `H29.1` Lock the admin incremental-update page and upload contract.
    Deliverable: route/UI/handler contract for the new admin page, including supported source types, allowed file formats, one-file-per-run behavior, request/response feedback model, and the intended relationship to `/admin/initialization`.
    Exit criteria: the operator flow and runtime boundaries are decision-locked before implementation starts, including whether WoS government Excel and WoS JSON share the same entry point or separate actions.
    Handover:
    - Implemented as dedicated admin MVC surface under `/admin/incremental-updates`, linked from `/admin/initialization` and the admin operations sidebar.
    - Contract locked with `POST /admin/incremental-updates/wos` and `POST /admin/incremental-updates/scopus`, multipart validation, explicit WoS `sourceType`, optional WoS `sourceVersion`, and admin-only MVC/security coverage.
  - [x] `H29.2` Implement incremental WoS upload orchestration.
    Deliverable: WoS incremental update path that accepts a single uploaded WoS payload, validates the format (`JSON` or government Excel), stages/parses it in-memory or temporary storage, and executes the required downstream maintenance steps without assuming input files exist on disk ahead of time.
    Exit criteria: WoS incremental updates can be initiated entirely from the admin UI with deterministic handling for both supported WoS input variants and no dependency on pre-populated import directories.
    Handover:
    - `/admin/incremental-updates/wos` now runs synchronous uploaded-file WoS ingest plus fact-building, preserving the uploaded filename as `sourceFile` lineage and inferring/overriding `sourceVersion` with the same WoS rules as the existing pipeline.
    - Uploaded WoS runs intentionally skip checkpoint resume, category enrichment, projections, and WoS onboarding in this slice; targeted ingest/service/controller tests cover JSON, government Excel, replay/update semantics, and clear operator errors.
  - [ ] `H29.2a` Add follow-up WoS maintenance buttons on the incremental-updates page.
    Deliverable: explicit operator actions on `/admin/incremental-updates` to run WoS category enrichment and WoS projection rebuild after an upload-driven fact build, without folding those steps back into the H29.2 upload submit action.
    Exit criteria: admins can trigger enrichment and projection rebuild from the incremental-updates surface as separate, discoverable WoS follow-up actions after upload ingestion/fact-building completes.
  - [x] `H29.3` Implement incremental Scopus upload orchestration.
    Deliverable: Scopus incremental update path that accepts a single uploaded Scopus JSON file, validates/parses it, and executes the required downstream incremental maintenance steps without assuming input files exist on disk ahead of time.
    Exit criteria: Scopus incremental updates can be initiated entirely from the admin UI from one uploaded JSON payload and remain aligned with existing canonical ingest/fact/projection expectations.
    Handover:
    - `/admin/incremental-updates/scopus` now runs synchronous Scopus upload ingest plus fact/canonical materialization via a dedicated upload service, using `SCOPUS_JSON_UPLOAD` as stable import-event lineage and stopping before projections, source-link reconciliation, edge reconciliation, and index maintenance.
    - `ScopusDataService` now supports upload-byte ingest for publications and citations, and its indexed-field readers now handle array-backed payload fields correctly so upload-driven parsing matches the existing Scopus JSON shape.
    - Replay hardening now covers deterministic reuse of existing source links, canonical edges, and open conflicts across repeated Scopus upload/scheduler runs, including authorship, publication-author-affiliation, author-affiliation, citation conflicts, and duplicate-key recovery for canonical author/affiliation writes.
    - Batch membership for unchanged Scopus facts is now refreshed to the current `sourceBatchId`, and scheduler-triggered Scopus canonical/projection maintenance now stays batch-scoped instead of falling back to full rescans.
  - [x] `H29.4` Add the shared admin page, operator feedback, and guardrails.
    Deliverable: page implementation similar in role to `/admin/initialization`, with source-specific upload forms, clear status/flash feedback, invalid-file handling, and updated operator documentation.
    Exit criteria: admins have one discoverable page for incremental WoS/Scopus uploads, and invalid file type/empty upload/failure cases fail clearly without ambiguous operator behavior.
    Handover:
    - `/admin/incremental-updates` now distinguishes upload-driven incremental runs from broader initialization maintenance, with explicit source-level scope callouts, downstream-maintenance guidance, and clearer framing around success/error flash summaries.
    - Durable operator docs now include incremental uploads as a first-class admin surface in `docs/operational-playbook.md` and `docs/failure-triage.md`, pointing operators back to `/admin/initialization` for skipped downstream maintenance.
  - [ ] `H29.5` Add targeted regression coverage for upload-driven incremental updates.
    Deliverable: focused tests for controller security, multipart upload handling, source/file validation, and orchestration handoff for WoS and Scopus incremental update actions.
    Exit criteria: automated coverage protects the new admin surface against auth drift, unsupported file acceptance, and accidental regression back to disk-assumed input flows.
  - [x] `H29.5a` Add Scopus post-upload maintenance actions on the incremental-updates page.
    Deliverable: explicit Scopus follow-up actions on `/admin/incremental-updates` for projection rebuild and canonical edge reconcile after an upload-driven Scopus run, with source-link ledger repair exposed only as an advanced maintenance action if it is surfaced at all.
    Exit criteria: admins can continue the normal post-upload Scopus maintenance path from `/admin/incremental-updates` without detouring to the global initialization flow, and the page copy clearly distinguishes routine follow-up actions (projection rebuild, edge reconcile) from exceptional repair actions (source-link reconcile).
  - [ ] `H29.6` Extract non-destructive Scopus incremental maintenance flow from full-rebuild logic.
    Deliverable: dedicated incremental Scopus maintenance path for upload-driven and scheduler-driven batches that preserves replay-safe ingest/canonical behavior while avoiding destructive projection/update semantics that assume full-corpus rebuild completeness.
    Exit criteria: incremental Scopus upload and scheduler updates no longer remove previously visible citations/edges/projection state outside the batch they can fully reconstruct; full initialization/rebuild flows keep their current full-rescan semantics; shared code is extracted only where the contract is truly common.
    Subtasks:
    - [ ] `H29.6a` Lock the incremental-vs-full maintenance contract.
      Deliverable: explicit contract describing which Scopus maintenance steps are safe to share between full rebuilds and incremental batches, and which steps require incremental-specific semantics.
      Exit criteria: the project has a decision-locked distinction between full rebuild behavior and non-destructive incremental behavior for facts, canonicalization, edge maintenance, and projection writes.
      Notes: contract doc lives at `docs/tasks/active/h29.6a-incremental-vs-full-scopus-maintenance-contract.md`.
    - [ ] `H29.6b` Extract batch-scoped canonical/materialization orchestration.
      Deliverable: shared orchestration helpers that support batch-scoped Scopus maintenance without reusing full-rescan assumptions such as global checkpoint resume.
      Exit criteria: scheduler-driven and upload-driven incremental Scopus runs share the same batch-scoped, replay-safe orchestration contract, while full rebuild flows remain unchanged.
    - [ ] `H29.6c` Make batch-scoped Scopus projection maintenance non-destructive.
      Deliverable: batch projection logic that updates only the affected Scopus reporting state it can fully reconstruct from canonical truth, especially for graph-style tables such as citations and derived citation fields.
      Exit criteria: batch projection rebuilds no longer delete citation/edge rows that are not reinserted by the same batch, and previously visible citations do not disappear after incremental upload or scheduler maintenance.
    - [ ] `H29.6d` Separate full-rebuild projection semantics from incremental graph refresh semantics.
      Deliverable: clear internal separation between full projection replacement logic and incremental graph refresh logic for citations/authorship/author-affiliation style data.
      Exit criteria: full rebuilds may continue using replace-style writes, but incremental flows use refresh semantics that preserve cross-batch graph edges and publication citation visibility.
    - [ ] `H29.6e` Add regression coverage for non-destructive incremental Scopus maintenance.
      Deliverable: focused tests for upload-driven and scheduler-driven Scopus batches proving that replayed/incremental runs preserve existing citations and other cross-batch projection rows while still applying new batch changes.
      Exit criteria: automated coverage protects against regression back to destructive batch projection behavior and against checkpoint/full-rescan leakage into incremental flows.
    - [ ] `H29.6f` Document Scopus incremental maintenance invariants for operators and contributors.
      Deliverable: durable docs describing replay safety, batch scope, non-destructive projection expectations, and when operators must still use full initialization/rebuild actions.
      Exit criteria: operator/contributor docs make the incremental-vs-full Scopus maintenance model explicit and actionable.
      Notes: durable Scopus maintenance guidance lives in `docs/operational-playbook.md`, `docs/failure-triage.md`, and `docs/workflows.md`; the active `H29.6a` contract doc remains task history/design input rather than the long-term source of truth.
