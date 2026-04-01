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

- [ ] `H30` Shared shell and foundation migration to ScholarDex-owned UI/UX.
  Goal: migrate the shared shell off SB Admin 2 and Bootstrap 4-era shell conventions toward a ScholarDex-owned UI/UX system based on `docs/ux-design-guide.md`, with Bootstrap 5-compatible shared-shell implementation, repo-owned behavior, and support for both light and dark themes.
  Deliverable: shared shell cutover for sidebar/topbar/page-shell foundations, Bootstrap 5-compatible shell baseline, repo-owned visual tokens and theme hooks, and aligned shared fragments/templates/docs that preserve the current frontend asset contract and role-aware composition path.
  Exit criteria: authenticated shell work no longer treats SB Admin 2 or Bootstrap 4 shell patterns as acceptable steady state; touched shell behavior moves away from jQuery-driven Bootstrap 4 conventions; shell changes still route through shared fragments and `/assets/app.css` + `/assets/app.js`; touched docs and verification expectations reflect the new ScholarDex-owned shell baseline with light/dark theme support.
  Dependency: none.
  Subtasks:
  - [ ] `H30.1` Lock the shared shell and visual-foundation contract.
    Deliverable: implementation-ready contract for the shared-shell migration, covering the Bootstrap 5 target, ScholarDex-owned shell/layout primitives, light/dark theme support, required use of shared fragments and `/assets/app.css` + `/assets/app.js`, and the explicit non-goals around route ownership and page-level redesign owned by later tasks.
    Exit criteria: the repo has a decision-locked H30 contract that defines what the shell/foundation migration must accomplish before H31-H34 build on it; the fragment boundaries, asset-pipeline constraints, Bootstrap 5 target, theme support contract, and role-aware composition rules are explicit; and contributors can implement H30 without re-deciding shell scope or migration direction.
  - [ ] `H30.2` Refresh shared sidebar structure and navigation treatment.
    Deliverable: shared sidebar migration to repo-owned markup, behavior, and theme-aware styling within the existing role-aware fragment path, with clearer grouping, active-state treatment, label cleanup, and Bootstrap 5-compatible shell behavior replacing SB Admin 2 sidebar assumptions.
    Exit criteria: the authenticated sidebar is visibly more consistent and readable across admin/user contexts in both light and dark themes; navigation still resolves through the unified sidebar composition path; touched sidebar behavior no longer depends on SB Admin 2 or jQuery-driven Bootstrap 4 patterns; and touched shell views preserve canonical routes and current runtime asset contracts.
  - [ ] `H30.3` Refresh shared topbar and page-header orientation patterns.
    Deliverable: shared topbar/page-header migration to ScholarDex-owned shell patterns through shared fragments, including clearer page-title presentation, improved role/context treatment, room for breadcrumb-style orientation where appropriate, and Bootstrap 5-compatible behavior instead of Bootstrap 4/SB Admin shell conventions.
    Exit criteria: authenticated pages using the shared shell have a clearer topbar hierarchy and orientation model in both light and dark themes; role/context affordances are improved without inventing page-specific shell variants; and touched topbar behavior no longer depends on jQuery-driven Bootstrap 4 patterns.
  - [ ] `H30.4` Establish baseline visual primitives for shared shell surfaces.
    Deliverable: baseline shared-shell foundation primitives applied through the current frontend pipeline for color, surface, spacing, typography, theme tokens, theme state hooks, and bounded content layout, establishing one ScholarDex design language that supports both light and dark modes.
    Exit criteria: authenticated pages touched by H30 share a coherent shell-level baseline for spacing, typography, surfaces, and theme-aware treatment; shell visual improvements flow through existing frontend source and emitted assets rather than ad hoc template overrides; and H31-H33 can build on a stable Bootstrap 5-compatible shell/foundation baseline instead of reintroducing Bootstrap 4 drift.
  - [ ] `H30.5` Align H30 docs, guardrails, and regression expectations.
    Deliverable: updates to active contributor docs and relevant verification expectations so the migrated shared shell becomes the documented steady-state baseline for future UI work, including alignment with the UX guide, frontend conventions, Bootstrap 5 target, and light/dark theme support contract.
    Exit criteria: active docs describe the post-H30 shared-shell baseline without contradicting current repo constraints; any touched verification or guardrail expectations are updated to reflect the new shell/foundation contract; and the repo has a clear contributor-facing handoff for subsequent H31-H34 migration work.

- [ ] `H31` Data-heavy list and table migration to ScholarDex-owned patterns.
  Goal: migrate ScholarDex’s table and list surfaces off Bootstrap 4-era assumptions toward Bootstrap 5-compatible, ScholarDex-owned list/table patterns that remain consistent, legible, and role-appropriate across shared, admin, and user views in both light and dark themes.
  Deliverable: standardized table/list treatment for titles, filtering, pagination, row states, status badges, identifier presentation, and empty-state behavior across the primary data-heavy pages, with touched surfaces converging on ScholarDex-owned patterns and retiring BS4 DataTables dependency where those surfaces are modernized.
  Exit criteria: the main list/table surfaces present consistent filtering, pagination, status, and empty-state behavior in both themes; legacy Bootstrap 4 full-grid/bordered styling is removed from touched views; touched list/table behavior no longer extends BS4 DataTables or Bootstrap 4-only markup assumptions; and touched tests/docs/guardrails reflect the standardized table UX contract.
  Dependency: execute after `H30` so shared shell and baseline visual rules are stable first.

- [ ] `H32` Form and workflow migration to ScholarDex-owned UX.
  Goal: migrate form-heavy and multi-step user/admin workflows off Bootstrap 4-era interaction assumptions toward Bootstrap 5-compatible, ScholarDex-owned form and workflow patterns so inputs, validation, readonly states, and action structure become predictable and easier to use in both light and dark themes.
  Deliverable: updated form conventions across the highest-value create/edit/workflow pages, including label/input/help-text hierarchy, validation/error treatment, readonly presentation, action hierarchy, clearer multi-step workflow orientation, and replacement of touched Bootstrap 4 modal/tooltip/form assumptions with repo-owned or Bootstrap 5-compatible behavior.
  Exit criteria: touched form/workflow pages follow one consistent input and action pattern in both themes; validation and readonly behavior are clearer and more uniform; touched UI behavior no longer extends jQuery-driven Bootstrap 4 workflow conventions; and touched docs/tests/guardrails capture the updated workflow UX expectations.
  Dependency: execute after `H30` so form work builds on the stabilized baseline shell and visual conventions.

- [ ] `H33` Dashboard, summary, and feedback migration to ScholarDex-owned UI/UX.
  Goal: migrate dashboard-like and summary-heavy surfaces to ScholarDex-owned patterns that emphasize meaningful metrics, recent activity, empty states, and explicit user feedback while behaving consistently in both light and dark themes.
  Deliverable: refreshed dashboard/summary surfaces for the main user and admin entry views plus consistent feedback patterns for success/error/status messaging and action outcomes in touched UI flows, aligned with the Bootstrap 5-compatible and repo-owned shell/foundation established by `H30`.
  Exit criteria: primary dashboard or summary entry surfaces no longer read as placeholder shells; key summary cards, recent activity or attention blocks, and empty-state guidance are present where intended in both themes; touched flows provide clearer post-action feedback without introducing one-off notification patterns or extending Bootstrap 4-era behavior; and touched docs/tests/guardrails reflect the refreshed summary/feedback UX contract.
  Dependency: execute after `H30`; may proceed in parallel with `H31` and `H32` once the shell baseline is in place.

- [ ] `H34` Accessibility, responsive behavior, and migration consistency closeout.
  Goal: close the modernization wave by enforcing accessibility, responsive behavior, and cross-surface consistency expectations across the migrated ScholarDex-owned UI/UX system.
  Deliverable: targeted accessibility/responsive fixes, cross-theme consistency cleanup, and removal of stale Bootstrap 4/SB Admin remnants across the surfaces modernized by `H30`-`H33`, with aligned docs and verification expectations for the updated frontend baseline.
  Exit criteria: the modernized shell, table/list, form/workflow, and dashboard surfaces meet the repo’s intended accessibility and responsive expectations in both light and dark themes; keyboard/focus/state/empty-state/responsive regressions are addressed for the touched areas; stale Bootstrap 4/SB Admin remnants are removed from migrated surfaces; and active docs and relevant verification coverage describe the post-migration UX baseline without stale contradictions.
  Dependency: execute after `H31`, `H32`, and `H33`.
