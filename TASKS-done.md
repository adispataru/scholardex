# Done Tasks

Archived completed tasks moved from `TASKS.md` on 2026-03-03.

## H37.10 Legacy template cleanup and verification — DONE 2026-04-18

Completed cleanup of dead routes and templates:
- Deleted orphaned `user/indicators-apply.html` (no controller ever returned it post-H37.1).
- Removed redirect shims from `UserViewController`: `GET /indicators`, `GET /indicators/apply/{id}`, `POST /indicators/apply/{id}/refresh`, `GET /individual-reports`, `GET /individual-reports/view/{id}`, `POST /individual-reports/view/{id}/refresh`, `POST /individual-reports/view/{id}/refresh-all-indicators`. The live Excel export (`GET /indicators/export/{id}`) was retained.
- Removed now-unused `UserIndicatorResultService` and `UserIndividualReportRunService` fields/imports from `UserViewController`.
- Fixed stale notification link in `ResearcherWorkspaceController`: `/user/individual-reports` → `/user/evaluation`.
- Removed `indicators-apply.html` entry from `verify-route-guardrails.js`.
- All verification scripts pass: `compileJava`, `npm run build`, `verify-assets`, `verify-template-assets`, `verify-route-guardrails`, `verify-ui-guardrails`.

## H37.6 What-if analysis — DROPPED 2026-04-18

Removed from backlog. The stub backend endpoints (`POST /what-if`, `GET /breakdown/{indicatorId}`) and associated records (`WhatIfRequest`, `WhatIfItem`, `WhatIfResponse`, `BreakdownItem`, `BreakdownResponse`) and the `computeHypotheticalItemScore` helper have been deleted from `EvaluationWorkspaceController`. No frontend code was written. `extractScoredItems` was kept — it is still used by the indicator detail endpoint.

## H37.7 Per-criterion score breakdown charts — DROPPED 2026-04-18

Removed from backlog together with H37.6. No code was written for this feature.

## H29 Admin Incremental Source Updates From Uploaded WoS And Scopus Files

Archived from `TASKS.md` on 2026-04-02 after backlog bookkeeping confirmed the remaining `H29.6` Scopus maintenance slices had already landed in code, tests, and durable docs.

- [x] `H29` Admin incremental source updates from uploaded WoS and Scopus files.
  Goal: add an operator page parallel to `/admin/initialization` for incremental source updates, starting with WoS and Scopus, where each run is driven by a single uploaded file instead of pre-staged data on disk.
  Deliverable: admin upload/update surface plus source-specific incremental ingest orchestration for WoS and Scopus, accepting one file per operation (`WoS JSON` or government Excel for WoS; `Scopus JSON` for Scopus) and routing the uploaded payload through the existing canonical maintenance pipeline needed for safe incremental updates.
  Exit criteria: operators can trigger incremental WoS and Scopus updates from the browser without relying on filesystem-resident import inputs; file validation and operator feedback are explicit; incremental runs remain replay-safe, scoped to the uploaded payload, and aligned with existing fact/canonical/projection maintenance contracts.
  Status: completed on 2026-04-02.
  Handover:
  - `/admin/incremental-updates` is now the dedicated operator surface for upload-driven WoS and Scopus maintenance, with explicit post-upload follow-up actions and admin-only guardrails.
  - WoS incremental maintenance is now fully lineage-scoped end to end: upload ingest, fact build, category enrichment, and projection refresh all stay tied to the stored `sourceType + sourceFile + sourceVersion` context.
  - Scopus incremental maintenance now follows a batch-scoped contract for upload-driven and scheduler-driven runs, with checkpoint resume disabled for incremental canonicalization and a non-destructive batch projection refresh path distinct from full rebuild semantics.
  - Durable operator/contributor guidance now treats incremental uploads and scheduler maintenance as first-class, replay-safe maintenance modes separate from `/admin/initialization`.
  - Archived contract source of truth: `docs/tasks/closed/h29.6a-incremental-vs-full-scopus-maintenance-contract.md`.
  Subtasks:
  - [x] `H29.1` Lock the admin incremental-update page and upload contract.
    Handover:
    - Implemented as dedicated admin MVC surface under `/admin/incremental-updates`, linked from `/admin/initialization` and the admin operations sidebar.
    - Contract locked with `POST /admin/incremental-updates/wos` and `POST /admin/incremental-updates/scopus`, multipart validation, explicit WoS `sourceType`, optional WoS `sourceVersion`, and admin-only MVC/security coverage.
  - [x] `H29.2` Implement incremental WoS upload orchestration.
    Handover:
    - `/admin/incremental-updates/wos` now runs synchronous uploaded-file WoS ingest plus fact-building, preserving the uploaded filename as `sourceFile` lineage and inferring/overriding `sourceVersion` with the same WoS rules as the existing pipeline.
    - Uploaded WoS runs intentionally skip checkpoint resume, category enrichment, projections, and WoS onboarding in this slice; targeted ingest/service/controller tests cover JSON, government Excel, replay/update semantics, and clear operator errors.
  - [x] `H29.2a` Add upload-scoped WoS post-upload maintenance on the incremental-updates page.
    Handover:
    - Successful WoS uploads now store the last upload lineage in session as `sourceType + sourceFile + sourceVersion`, render that context back on `/admin/incremental-updates`, and enable a dedicated WoS post-upload panel with upload-scoped “Enrich Category Rankings” and “Rebuild Projections” actions.
    - The new WoS follow-up service resolves the exact stored lineage through `WosImportEventRepository`, runs scoped category enrichment only for the uploaded WoS lineage, and rebuilds PostgreSQL WoS reporting rows only for the affected journals instead of truncating and rebuilding the full corpus.
    - Controller/security/service regression coverage now protects missing-session rejection, admin-only access, scoped enrichment updates, and scoped projection rewrites so the incremental page cannot drift back toward the unsafe global Initialization behavior.
  - [x] `H29.2b` Complete upload-scoped WoS fact building for incremental uploads.
    Handover:
    - The WoS incremental upload path now builds facts only for the uploaded lineage by parsing `sourceType + sourceFile + sourceVersion` through `WosImportEventParserOrchestrator.parseSourceLineage(...)` instead of `parseAllEvents()`.
    - `WosFactBuilderService` now exposes a scoped fact-build entrypoint for exact uploaded lineage processing, while the existing full-corpus fact-builder path remains unchanged for `/admin/initialization`.
    - Targeted WoS upload and fact-builder tests now guard the scoped entrypoint, including the invariant that incremental WoS uploads never widen back to full-corpus parser scans.
  - [x] `H29.2c` Tighten WoS incremental projection maintenance from journal scope to slice scope.
    Handover:
    - The WoS incremental projection follow-up now uses mixed scope: `wos_ranking_view` is refreshed per affected journal via upsert, while `wos_metric_fact`, `wos_category_fact`, and `wos_scoring_view` are deleted and reinserted only for slice keys touched by the uploaded lineage.
    - Incremental follow-up scope is now derived directly from lineage-owned Mongo facts for the stored `sourceType + sourceFile + sourceVersion`, rather than widening every reporting table to all rows on affected journals.
    - Targeted projection/follow-up tests now protect the mixed-scope contract, including the invariants that unrelated slice rows on the same journal are preserved, ranking rows are not deleted during partial refreshes, and Initialization keeps the existing full-corpus rebuild path.
  - [x] `H29.3` Implement incremental Scopus upload orchestration.
    Handover:
    - `/admin/incremental-updates/scopus` now runs synchronous Scopus upload ingest plus fact/canonical materialization via a dedicated upload service, using `SCOPUS_JSON_UPLOAD` as stable import-event lineage and stopping before projections, source-link reconciliation, edge reconciliation, and index maintenance.
    - `ScopusDataService` now supports upload-byte ingest for publications and citations, and its indexed-field readers now handle array-backed payload fields correctly so upload-driven parsing matches the existing Scopus JSON shape.
    - Replay hardening now covers deterministic reuse of existing source links, canonical edges, and open conflicts across repeated Scopus upload/scheduler runs, including authorship, publication-author-affiliation, author-affiliation, citation conflicts, and duplicate-key recovery for canonical author/affiliation writes.
    - Batch membership for unchanged Scopus facts is now refreshed to the current `sourceBatchId`, and scheduler-triggered Scopus canonical/projection maintenance now stays batch-scoped instead of falling back to full rescans.
  - [x] `H29.4` Add the shared admin page, operator feedback, and guardrails.
    Handover:
    - `/admin/incremental-updates` now distinguishes upload-driven incremental runs from broader initialization maintenance, with explicit source-level scope callouts, downstream-maintenance guidance, and clearer framing around success/error flash summaries.
    - Durable operator docs now include incremental uploads as a first-class admin surface in `docs/operational-playbook.md` and `docs/failure-triage.md`, pointing operators back to `/admin/initialization` for skipped downstream maintenance.
  - [x] `H29.5` Add targeted regression coverage for upload-driven incremental updates.
    Handover:
    - Focused MVC contract coverage now exists in `AdminIncrementalUpdatesControllerContractTest`, covering page framing, valid WoS/Scopus uploads, empty upload handling, invalid source/file validation, facade validation errors, and the Scopus post-upload follow-up actions.
    - Security coverage now exists in `AdminIncrementalUpdatesSecurityContractTest`, protecting the page, WoS/Scopus upload actions, and Scopus post-upload maintenance actions against non-admin access.
  - [x] `H29.5a` Add Scopus post-upload maintenance actions on the incremental-updates page.
    Handover:
    - `/admin/incremental-updates` now exposes batch-scoped Scopus projection rebuild, edge reconcile, and source-link repair follow-up actions for the stored upload batch instead of sending operators back to full initialization.
    - The Scopus incremental page copy distinguishes routine batch follow-up from broader recovery or full-rebuild work that still belongs on `/admin/initialization`.
  - [x] `H29.6` Extract non-destructive Scopus incremental maintenance flow from full-rebuild logic.
    Handover:
    - Scopus incremental upload and scheduler maintenance now run through shared batch-scoped canonical/materialization orchestration with `sourceBatchIdFilter` support and `useCheckpoint=false` for incremental paths.
    - `ScopusProjectionBuilderService` now separates full replacement rebuilds from batch refresh behavior, using non-destructive batch refresh rules for citations, authorships, and author-affiliation edges instead of leaking `TRUNCATE`-style semantics into incremental flows.
    - Durable docs and regression tests now lock the incremental-vs-full contract for Scopus so upload/scheduler batches preserve replay safety and cross-batch graph visibility while `/admin/initialization` keeps the explicit full-corpus path.
    Subtasks:
    - [x] `H29.6a` Lock the incremental-vs-full maintenance contract.
      Handover:
      - Archived contract lock: `docs/tasks/closed/h29.6a-incremental-vs-full-scopus-maintenance-contract.md`.
      - The repo now has an explicit decision boundary between full-rescan Scopus maintenance and non-destructive batch-scoped incremental maintenance, including the citation-projection rules enforced by later slices.
    - [x] `H29.6b` Extract batch-scoped canonical/materialization orchestration.
      Handover:
      - `ScopusCanonicalMaterializationService` now owns the shared batch-scoped orchestration for upload-driven and scheduler-driven Scopus maintenance, passing `sourceBatchIdFilter` through fact build, canonicalization, edge reconcile, and projection refresh.
      - Incremental Scopus canonicalization now explicitly disables checkpoint resume by forcing `useCheckpoint=false` whenever a batch-scoped run is active.
    - [x] `H29.6c` Make batch-scoped Scopus projection maintenance non-destructive.
      Handover:
      - `ScopusProjectionBuilderService.rebuildViewsForBatch(...)` now refreshes only the reconstructible batch neighborhood instead of truncating and rebuilding the full reporting corpus.
      - Citation refresh now preserves previously visible cross-batch citations when only one endpoint is in the affected batch scope, rather than deleting edges the batch cannot fully reconstruct.
    - [x] `H29.6d` Separate full-rebuild projection semantics from incremental graph refresh semantics.
      Handover:
      - Scopus projection code now has explicit full-rebuild replacement and batch-refresh paths (`executeFullReplacementWrite(...)` versus `executeBatchRefreshWrite(...)`) instead of sharing one destructive implementation.
      - Full rebuilds still use corpus-wide replacement semantics, while incremental maintenance refreshes citations, authorships, and author-affiliation rows only for affected scopes.
    - [x] `H29.6e` Add regression coverage for non-destructive incremental Scopus maintenance.
      Handover:
      - `ScopusCanonicalMaterializationServiceTest`, `ScopusProjectionBuilderServiceTest`, and `ScopusUpdateSchedulerTest` now protect the batch-scoped contract, checkpoint disabling, and non-destructive citation/edge refresh behavior.
      - Incremental Scopus tests now explicitly guard against `TRUNCATE` leakage and against losing valid citations when only one batch endpoint is affected.
    - [x] `H29.6f` Document Scopus incremental maintenance invariants for operators and contributors.
      Handover:
      - Durable Scopus incremental maintenance guidance now lives in `docs/workflows.md`, `docs/operational-playbook.md`, and `docs/failure-triage.md`.
      - Those docs now describe batch scope, replay safety, checkpoint disabling, non-destructive projection behavior, and the rule that `/admin/initialization` remains the explicit full-rebuild path.

## H30 Shared Shell And Foundation Migration To ScholarDex-Owned UI/UX

Archived from `TASKS.md` on 2026-04-02 after backlog bookkeeping aligned the established shell baseline with the completed H31-H35 wave history.

- [x] `H30` Shared shell and foundation migration to ScholarDex-owned UI/UX.
  Goal: migrate the shared shell off SB Admin 2 and Bootstrap 4-era shell conventions toward a ScholarDex-owned UI/UX system based on `docs/ux-design-guide.md`, with Bootstrap 5-compatible shared-shell implementation, repo-owned behavior, and support for both light and dark themes.
  Deliverable: shared shell cutover for sidebar/topbar/page-shell foundations, Bootstrap 5-compatible shell baseline, repo-owned visual tokens and theme hooks, and aligned shared fragments/templates/docs that preserve the current frontend asset contract and role-aware composition path.
  Exit criteria: authenticated shell work no longer treats SB Admin 2 or Bootstrap 4 shell patterns as acceptable steady state; touched shell behavior moves away from jQuery-driven Bootstrap 4 conventions; shell changes still route through shared fragments and `/assets/app.css` + `/assets/app.js`; touched docs and verification expectations reflect the new ScholarDex-owned shell baseline with light/dark theme support.
  Status: completed on 2026-04-02.
  Handover:
  - The authenticated shared shell now resolves through `fragments.html` plus repo-owned frontend assets, with shared sidebar, topbar, page-header, theme toggle, and page-shell layout owned by ScholarDex rather than an admin-template baseline.
  - Shell-level spacing, typography, surface treatment, bounded content layout, and theme-aware behavior are now part of the active authenticated baseline inherited by later frontend work.
  - Root theme state for migrated shell work is expressed through `data-bs-theme`, and later UI waves inherit that shell/theme contract rather than re-deciding it.
  - Durable contributor-facing shell guidance now lives in `docs/frontend-conventions.md`, `docs/ux-design-guide.md`, and `docs/quality-gates.md`.
  - Archived contract source of truth: `docs/tasks/closed/h30.1-shared-shell-visual-foundation-contract.md`.
  Subtasks:
  - [x] `H30.1` Lock the shared shell and visual-foundation contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h30.1-shared-shell-visual-foundation-contract.md`.
  - [x] `H30.2` Refresh shared sidebar structure and navigation treatment.
    Handover:
    - The authenticated sidebar now follows the shared ScholarDex-owned sidebar composition path with clearer grouping, active-state treatment, and repo-owned shell behavior.
  - [x] `H30.3` Refresh shared topbar and page-header orientation patterns.
    Handover:
    - The shared topbar and page-header now provide the active orientation model for authenticated pages, including the unified page title, toolbar rhythm, and workspace/theme controls.
  - [x] `H30.4` Establish baseline visual primitives for shared shell surfaces.
    Handover:
    - Shell-level visual primitives, theme tokens, typography, spacing, and bounded content layout now live in the frontend asset pipeline and act as the inherited shell baseline for later UI work.
  - [x] `H30.5` Align H30 docs, guardrails, and regression expectations.
    Handover:
    - Active frontend docs and targeted verification guidance now treat the shared ScholarDex shell as the baseline for later migrated frontend work.

## H35 Legacy Asset Extraction And Steady-State Shell/Copy Cleanup

Archived from `TASKS.md` on 2026-04-02 after H35.1-H35.6 completion.

- [x] `H35` Legacy asset extraction and steady-state shell/copy cleanup.
  Goal: remove the remaining SB Admin 2 and Bootstrap 4 dependency from the active ScholarDex frontend baseline, finish the shared shell/footer cleanup that still inherits legacy behavior, and purge migration-era implementation language from page-visible copy so the product surface reads as steady state rather than in-transition.
  Deliverable: migrated and explicitly targeted legacy pages no longer rely on SB Admin 2 stylesheet/script includes or Bootstrap 4 runtime imports as part of their steady-state contract; the shared footer and dark-mode shell background are corrected under the repo-owned theme system; and page-visible copy no longer references migration history, old Bootstrap patterns, or internal modernization language.
  Exit criteria: shared fragments and frontend assets no longer load SB Admin 2 or Bootstrap 4 for the modernized baseline; dark-mode page backgrounds and footer styling are governed by ScholarDex-owned theme tokens rather than legacy overrides; visible footer text is correct and encoding-safe; migration-era page copy is removed from touched surfaces; and docs/guardrails describe the post-H35 frontend baseline as the active steady state.
  Status: completed on 2026-04-02.
  Handover:
  - The authenticated ScholarDex baseline now resolves through shared fragments plus `/assets/app.css` and `/assets/app.js` without SB Admin 2 or Bootstrap 4 shell/runtime assets.
  - Authenticated DataTables-backed list surfaces now use the Bootstrap 5 DataTables integration and the shared app-owned initialization contract.
  - The shared footer, dark-shell background treatment, and authenticated shell surfaces now resolve through ScholarDex-owned theme tokens and shared footer markup.
  - Authenticated page-visible copy no longer leaks migration history or internal frontend-contract language.
  - Active frontend docs and authenticated template guardrails now describe and enforce the post-H35 baseline, while remaining Bootstrap-era debt is explicitly bounded to untouched non-authenticated or deferred legacy pages.
  - Archived contract source of truth: `docs/tasks/closed/h35.1-legacy-asset-extraction-contract.md`.
  Subtasks:
  - [x] `H35.1` Lock the legacy asset extraction and steady-state cleanup contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h35.1-legacy-asset-extraction-contract.md`.
  - [x] `H35.2` Extract shared authenticated shell assets off SB Admin 2 and Bootstrap 4.
    Handover:
    - Authenticated templates no longer include `/css/sb-admin-2.min.css`, and shared fragments no longer include `/js/sb-admin-2.min.js`.
    - `frontend/src/app.js` now resolves the authenticated baseline without Bootstrap 4 CSS/JS or `jquery.easing`, while repo-owned shared runtime code handles modal, collapse, tooltip, and scroll-to-top behavior.
    - Shared compatibility styling for authenticated pages now lives in the repo-owned frontend layer rather than SB Admin / Bootstrap 4 shell assets.
  - [x] `H35.3` Replace Bootstrap-4-era DataTables coupling on the active list baseline.
    Handover:
    - The authenticated bundle now uses `datatables.net-bs5` from `frontend/src/app.js`, and shared DataTables initialization lives in `frontend/src/modules/shared/tableEnhancer.js` instead of `/js/demo/datatables-demo.js`.
    - Authenticated `admin`, `user`, and `events` templates no longer include the legacy DataTables demo bootstrap script, and the opt-in guardrail now enforces the shared bundle contract instead of page-local script usage.
    - `admin/group-publications`, `admin/institution-publications`, and `admin/scholardex-citations` now render their DataTables surfaces through the ScholarDex `app-table` framing while preserving existing links and table behavior.
  - [x] `H35.4` Fix shared shell and footer steady-state behavior after asset extraction.
    Handover:
    - Shared fragments now render the footer through ScholarDex-owned `app-shell-footer` markup and no longer include `/css/footer-layout.css` in the authenticated asset contract.
    - Shell/footer layout and visual treatment now live in `frontend/src/styles/foundation.css`, including the content-wrapper flex column, footer surface tokens, and a theme-aware footer that reads coherently in both light and dark modes.
    - `app.footer.message` remains the configurable footer source, and authenticated shell backgrounds now use explicit repo-owned content-surface tokens instead of relying on legacy footer/shell CSS.
  - [x] `H35.5` Purge migration-era implementation copy from page-visible content.
    Handover:
    - Authenticated page intros, helper copy, and empty-state text now describe the page purpose directly instead of referencing migration history, old Bootstrap patterns, or internal frontend-contract language.
    - Admin workflow, dashboard, and operations pages keep their practical guidance while removing phrases such as “shared contract”, “builder contract”, “old Bootstrap 4”, and similar implementation framing.
    - User workspace/apply pages and the touched list pages now use plain task-oriented copy that remains specific to each page without leaking modernization history.
  - [x] `H35.6` Align docs, guardrails, and final frontend baseline expectations.
    Handover:
    - Active contributor docs now describe the post-H35 authenticated frontend baseline instead of the earlier post-H34 transition state.
    - Authenticated template guardrails now fail on reintroduction of `/css/sb-admin-2.min.css`, `/js/sb-admin-2.min.js`, and `/css/footer-layout.css`.
    - `H35` is archived from the active backlog, and its contract doc now lives under `docs/tasks/closed/`.

## H33 Dashboard, Summary, And Feedback Migration To ScholarDex-Owned UI/UX

Archived from `TASKS.md` on 2026-04-02 after final transition closeout.

- [x] `H33` Dashboard, summary, and feedback migration to ScholarDex-owned UI/UX.
  Goal: migrate dashboard-like and summary-heavy surfaces to ScholarDex-owned patterns that emphasize meaningful metrics, recent activity, empty states, and explicit user feedback while behaving consistently in both light and dark themes.
  Deliverable: refreshed dashboard/summary surfaces for the main user and admin entry views plus consistent feedback patterns for success/error/status messaging and action outcomes in touched UI flows, aligned with the Bootstrap 5-compatible and repo-owned shell/foundation established by `H30`.
  Exit criteria: primary dashboard or summary entry surfaces no longer read as placeholder shells; key summary cards, recent activity or attention blocks, and empty-state guidance are present where intended in both themes; touched flows provide clearer post-action feedback without introducing one-off notification patterns or extending Bootstrap 4-era behavior; and touched docs/tests/guardrails reflect the refreshed summary/feedback UX contract.
  Status: completed on 2026-04-02.
  Handover:
  - Migrated shared, admin, and user summary-heavy surfaces now inherit the ScholarDex-owned dashboard, summary, and feedback baseline established by `H33`.
  - Chart framing, summary-card rhythm, action clustering, empty-state treatment, and explicit feedback/status surfaces are part of the steady state for migrated summary/workspace pages.
  - Bootstrap 4 / SB Admin summary-card and alert presentation are no longer acceptable on already-migrated H33 families; remaining debt is bounded to untouched legacy pages.
  - Archived contract source of truth: `docs/tasks/closed/h33.1-shared-dashboard-summary-feedback-migration-contract.md`.
  Subtasks:
  - [x] `H33.1` Lock the shared dashboard/summary/feedback migration contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h33.1-shared-dashboard-summary-feedback-migration-contract.md`.
  - [x] `H33.2` Establish the repo-owned shared dashboard, summary, and feedback foundation.
    Handover:
    - Shared dashboard, summary, and feedback primitives now live under `frontend/src/**` and `/assets/app.css`.
  - [x] `H33.3` Migrate primary shared and admin dashboard/summary surfaces.
    Handover:
    - Primary shared/admin dashboard and summary surfaces now use the shared ScholarDex summary contract instead of SB Admin card stacks and raw alert framing.
  - [x] `H33.4` Migrate primary user dashboard, workspace-summary, and feedback-heavy surfaces.
    Handover:
    - Primary user workspace-summary and feedback-heavy pages now share the same summary and feedback language as the admin dashboard family.
  - [x] `H33.5` Align `H33` docs, guardrails, and regression expectations.
    Handover:
    - Durable frontend docs and quality-gate guidance now treat the H33 summary/feedback baseline as part of the active post-H34 frontend contract.

## H32 Form And Workflow Migration To ScholarDex-Owned UX

Archived from `TASKS.md` on 2026-04-02 after final transition closeout.

- [x] `H32` Form and workflow migration to ScholarDex-owned UX.
  Goal: migrate form-heavy and multi-step user/admin workflows off Bootstrap 4-era interaction assumptions toward Bootstrap 5-compatible, ScholarDex-owned form and workflow patterns so inputs, validation, readonly states, and action structure become predictable and easier to use in both light and dark themes.
  Deliverable: updated form conventions across the highest-value create/edit/workflow pages, including label/input/help-text hierarchy, validation/error treatment, readonly presentation, action hierarchy, clearer multi-step workflow orientation, and replacement of touched Bootstrap 4 modal/tooltip/form assumptions with repo-owned or Bootstrap 5-compatible behavior.
  Exit criteria: touched form/workflow pages follow one consistent input and action pattern in both themes; validation and readonly behavior are clearer and more uniform; touched UI behavior no longer extends jQuery-driven Bootstrap 4 workflow conventions; and touched docs/tests/guardrails capture the updated workflow UX expectations.
  Status: completed on 2026-04-02.
  Handover:
  - Migrated admin and user workflow surfaces now inherit the ScholarDex-owned form, modal, step-flow, and collection-builder baseline established by `H32`.
  - Labels, helper text, readonly treatment, action hierarchy, multi-step framing, and shared modal/workflow surfaces are part of the expected steady state for migrated workflows.
  - Bootstrap 4 modal, tooltip, collapse, and input-group presentation are no longer acceptable on already-migrated H32 families; remaining debt is bounded to untouched legacy pages.
  - Archived contract source of truth: `docs/tasks/closed/h32.1-shared-form-workflow-migration-contract.md`.
  Subtasks:
  - [x] `H32.1` Lock the shared form/workflow migration contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h32.1-shared-form-workflow-migration-contract.md`.
  - [x] `H32.2` Establish the repo-owned shared form and workflow foundation.
    Handover:
    - Shared form and workflow primitives now live under `frontend/src/**` and `/assets/app.css`.
  - [x] `H32.3` Migrate primary admin create/edit and modal-heavy workflows.
    Handover:
    - Primary admin create/edit and modal-driven flows now use the shared ScholarDex workflow contract instead of Bootstrap 4-era modal and input-group presentation.
  - [x] `H32.4` Migrate primary user multi-step and apply-style workflows.
    Handover:
    - Primary user multi-step and apply-style workflows now use the same shared step, help-text, action, and selection contract.
  - [x] `H32.5` Align H32 docs, guardrails, and regression expectations.
    Handover:
    - Durable frontend docs and quality-gate guidance now treat the H32 workflow baseline as part of the active post-H34 frontend contract.

## H31 Data-Heavy List And Table Migration To ScholarDex-Owned Patterns

Archived from `TASKS.md` on 2026-04-02 after final transition closeout.

- [x] `H31` Data-heavy list and table migration to ScholarDex-owned patterns.
  Goal: migrate ScholarDex’s table and list surfaces off Bootstrap 4-era assumptions toward Bootstrap 5-compatible, ScholarDex-owned list/table patterns that remain consistent, legible, and role-appropriate across shared, admin, and user views in both light and dark themes.
  Deliverable: standardized table/list treatment for titles, filtering, pagination, row states, status badges, identifier presentation, and empty-state behavior across the primary data-heavy pages, with touched surfaces converging on ScholarDex-owned patterns and retiring BS4 DataTables dependency where those surfaces are modernized.
  Exit criteria: the main list/table surfaces present consistent filtering, pagination, status, and empty-state behavior in both themes; legacy Bootstrap 4 full-grid/bordered styling is removed from touched views; touched list/table behavior no longer extends BS4 DataTables or Bootstrap 4-only markup assumptions; and touched tests/docs/guardrails reflect the standardized table UX contract.
  Status: completed on 2026-04-02.
  Handover:
  - Migrated shared, admin, and user list/table surfaces now inherit the ScholarDex-owned table/list baseline established by `H31`.
  - Filter panels, toolbar metadata, responsive overflow, empty states, pager treatment, and table semantics are part of the steady state for migrated list/table pages.
  - Bootstrap 4 full-grid table presentation and BS4 DataTables styling are no longer acceptable on already-migrated H31 families; remaining debt is bounded to untouched legacy pages.
  - Archived contract source of truth: `docs/tasks/closed/h31.1-shared-list-table-migration-contract.md`.
  Subtasks:
  - [x] `H31.1` Lock the shared list/table migration contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h31.1-shared-list-table-migration-contract.md`.
  - [x] `H31.2` Establish the repo-owned shared table/list foundation.
    Handover:
    - Shared table and list primitives now live under `frontend/src/**` and `/assets/app.css`.
  - [x] `H31.3` Migrate primary shared and admin list/table surfaces.
    Handover:
    - Primary shared/admin list surfaces now use the shared ScholarDex list-table contract instead of Bootstrap 4-era table presentation.
  - [x] `H31.4` Migrate primary user list/table surfaces.
    Handover:
    - Primary user list surfaces now use the same shared list-table contract with aligned toolbar, state, and overflow behavior.
  - [x] `H31.5` Align H31 docs, guardrails, and regression expectations.
    Handover:
    - Durable frontend docs and quality-gate guidance now treat the H31 list-table baseline as part of the active post-H34 frontend contract.

## H34 Accessibility, Responsive Behavior, And Migration Consistency Closeout

Archived from `TASKS.md` on 2026-04-02 after H34.1-H34.8 completion.

- [x] `H34` Accessibility, responsive behavior, and migration consistency closeout.
  Goal: close the modernization wave by enforcing accessibility, responsive behavior, and cross-surface consistency expectations across the migrated ScholarDex-owned UI/UX system.
  Deliverable: targeted accessibility/responsive fixes, cross-theme consistency cleanup, and removal of stale Bootstrap 4/SB Admin remnants across the surfaces modernized by `H30`-`H33`, with aligned docs and verification expectations for the updated frontend baseline.
  Exit criteria: the modernized shell, table/list, form/workflow, and dashboard surfaces meet the repo’s intended accessibility and responsive expectations in both light and dark themes; keyboard/focus/state/empty-state/responsive regressions are addressed for the touched areas; stale Bootstrap 4/SB Admin remnants are removed from migrated surfaces; and active docs and relevant verification coverage describe the post-migration UX baseline without stale contradictions.
  Status: completed on 2026-04-02.
  Handover:
  - Migrated shell, table/list, workflow, and summary families now inherit one ScholarDex-owned frontend baseline across shared, admin, and user surfaces.
  - Accessibility semantics, visible focus treatment, responsive behavior, and light/dark parity are part of the expected steady state for migrated surfaces rather than deferred polish work.
  - Bootstrap 4 and SB Admin presentation remnants were removed from already-migrated families, while remaining Bootstrap-era debt is intentionally bounded to untouched legacy pages.
  - Durable contributor-facing baseline docs now live in `docs/frontend-conventions.md`, `docs/ux-design-guide.md`, and `docs/quality-gates.md`.
  - Archived contract source of truth: `docs/tasks/closed/h34.1-accessibility-responsive-closeout-contract.md`.
  Subtasks:
  - [x] `H34.1` Lock the accessibility, responsive, and migration-closeout contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h34.1-accessibility-responsive-closeout-contract.md`.
  - [x] `H34.2` Close accessibility and focus/state gaps on migrated user table and report families.
    Handover:
    - Migrated user table and report families now use the shared table accessibility contract with clearer context, captioning, focus treatment, and corrected semantic markup.
  - [x] `H34.3` Close accessibility and focus/state gaps on migrated user workflow and workspace families.
    Handover:
    - Migrated user workflows and mixed workspaces now use clearer step semantics, live-status treatment, disclosure semantics, and workspace accessibility behavior.
  - [x] `H34.4` Close responsive and cross-theme consistency gaps on migrated shared and admin list/table families.
    Handover:
    - Migrated shared/admin list-table pages now share one responsive toolbar, filter, overflow, pager, and theme-consistent list contract.
  - [x] `H34.5` Close responsive and cross-theme consistency gaps on migrated admin workflow families.
    Handover:
    - Migrated admin workflow pages now share one responsive modal, form-grid, collection-row, builder-card, and helper/feedback contract.
  - [x] `H34.6` Close responsive and cross-theme consistency gaps on migrated dashboard, summary, and workspace families.
    Handover:
    - Migrated dashboard, summary, and mixed workspace pages now share one responsive summary-grid, dashboard-form, action-cluster, and report-detail contract.
  - [x] `H34.7` Remove remaining Bootstrap 4 and SB Admin presentation remnants from already-migrated families.
    Handover:
    - Already-migrated families no longer rely on Bootstrap 4 / SB Admin presentation classes as their visible contract, and remaining legacy debt is bounded to untouched pages.
  - [x] `H34.8` Align `H34` docs, guardrails, and final frontend baseline expectations.
    Handover:
    - Top-level frontend docs and quality-gate guidance now reflect the post-H34 steady state and H34 is archived from the active task flow.

## H28 Descriptive Runtime Naming Cleanup For Legacy `Hxx` Identifiers

Archived from `TASKS.md` on 2026-03-31 after H28.1-H28.6 completion.

- [x] `H28` Descriptive runtime naming cleanup for legacy `Hxx` identifiers.
  Goal: remove backlog-task ids from live runtime code and runtime-facing surfaces so classes, interfaces, metrics helpers, tests, logs, and admin UI labels use descriptive domain terminology instead of historical implementation-wave names.
  Deliverable: runtime renaming plan and implementation for the remaining `Hxx`-named live artifacts, centered on operational-status services and canonical observability helpers, with aligned tests, wiring, and visible admin/operator strings.
  Exit criteria: no live runtime class/interface/test/template/property/log label uses `Hxx` naming as its primary identifier where a descriptive domain name is available; runtime behavior and public routes remain unchanged; historical task/docs references remain archival only.
  Status: completed on 2026-03-31.
  Handover:
  - Operational-status runtime types were renamed to `PostgresOperationalStatus*`, with controller wiring and tests aligned to the descriptive names.
  - `H19CanonicalMetrics` was replaced by `CanonicalObservabilityMetrics` while preserving the existing `core.h19.*` meter ids.
  - Remaining runtime-facing H-task labels were removed from admin UI text, runtime logs, active config comments, and contributor-facing workflow/script/command names.
  - New workflow/script names are active under `.github/workflows/quality-gates.yml`, `.github/workflows/security-gates.yml`, and the descriptive `verify-*` command/script family in `package.json` and `scripts/`.
  - Runtime naming regression protection now lives in `scripts/verify-runtime-naming-guardrails.js` and is wired into `verify-quality-gates-baseline`.
  - Archived contract source of truth: `docs/tasks/closed/h28.1-descriptive-runtime-naming-contract.md`.
  - Closeout source of truth: `docs/tasks/closed/h28.6-runtime-naming-cleanup-closeout.md`.
  - Preserved non-goals remain intentionally unchanged: `core.h19.*` meter ids, `core.h22.*` / `H22_*` property-env contracts, HTTP routes, JSON wire contracts, and historical archival docs.
  Subtasks:
  - [x] `H28.1` Lock the descriptive runtime naming contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h28.1-descriptive-runtime-naming-contract.md`.
  - [x] `H28.2` Rename operational-status runtime types from task-coded to domain-coded names.
    Handover:
    - Runtime operational status now resolves through `PostgresOperationalStatusService` and `DefaultPostgresOperationalStatusService`.
  - [x] `H28.3` Rename canonical observability helpers from task-coded to domain-coded names.
    Handover:
    - Runtime canonical metrics helper now resolves through `CanonicalObservabilityMetrics`.
  - [x] `H28.4` Remove residual `Hxx` naming from runtime-facing strings and labels.
    Handover:
    - Active runtime strings/log labels now use descriptive domain wording rather than H-task labels.
  - [x] `H28.5` Remove residual `Hxx` naming from quality-gate workflows and guardrail scripts.
    Handover:
    - Live workflows, script filenames, and contributor-facing verification commands now use descriptive capability-based naming.
  - [x] `H28.6` Refresh verification and closeout documentation for the naming cleanup.
    Handover:
    - Closeout note: `docs/tasks/closed/h28.6-runtime-naming-cleanup-closeout.md`.

## H13 Workflow-Level Functional Confidence Suite

Archived from `TASKS.md` on 2026-03-30 after H13.1-H13.3 completion.

- [x] `H13` Workflow-level functional confidence suite.
  Goal: move beyond slice-level guardrails and prove critical modern admin/user workflows across the current canonical architecture, centered on WoS admin initialization, user reporting/export behavior, and Postgres projection-readiness failure handling.
  Deliverable: focused workflow-level tests for the highest-value operational paths, using deterministic fixtures and asserting state transitions across controller -> orchestration -> persistence/read-model boundaries for both success and degraded scenarios.
  Exit criteria: the selected modern workflows under `/admin/initialization/wos/*`, `/user/individual-reports/view/{id}`, and Postgres projection/readiness handling are validated across success and failure paths, and regressions are caught before merge by repeatable automated checks.
  Status: completed on 2026-03-30.
  Handover:
  - WoS admin initialization happy-path workflow coverage now exists in `WosAdminInitializationWorkflowIntegrationTest`, covering `ingest -> build facts -> enrich category rankings -> rebuild projections` plus Mongo/Postgres read-state verification.
  - The exact WoS admin step routes are protected by admin-only security assertions in `AdminInitializationSecurityContractTest`.
  - User reporting/export happy-path workflow coverage now exists in `UserReportRefreshCnfisWorkflowIntegrationTest`, covering latest-run creation, `/refresh-all-indicators`, persisted snapshot/result updates, and downstream CNFIS workbook export continuity.
  - Projection-failure degraded workflow coverage now exists in `PostgresProjectionFailureOperationalWorkflowTest`, proving failed projection state surfaces as operator-visible `RED` status through the current Postgres operational/admin endpoints.
  - Consolidated failure precedence is locked in `DefaultH22OperationalStatusServiceTest`: projection failure keeps `overallState = RED` even when materialized-view refresh is `SUCCESS`.
  - H13 required no standalone task-doc artifacts; the source of truth is the archived task entry plus the workflow tests above.
  Subtasks:
  - [x] `H13.1` Admin WoS maintenance end-to-end flow.
    Handover:
    - Workflow coverage: `WosAdminInitializationWorkflowIntegrationTest`.
    - Supporting route/auth evidence: `AdminInitializationControllerContractTest`, `AdminInitializationSecurityContractTest`.
  - [x] `H13.2` User indicator refresh/export workflow.
    Handover:
    - Workflow coverage: `UserReportRefreshCnfisWorkflowIntegrationTest`.
    - Supporting route/auth evidence: `UserViewControllerContractTest`, `UserViewSecurityContractTest`.
  - [x] `H13.3` Failure-path workflow gate.
    Handover:
    - Degraded workflow coverage: `PostgresProjectionFailureOperationalWorkflowTest`.
    - Failure-precedence guardrail: `DefaultH22OperationalStatusServiceTest`.

## H21 User-Defined Source Onboarding Into Scholardex

Archived from `TASKS.md` on 2026-03-30 after H21.1-H21.6 closure audit.

- [x] `H21` User-defined source onboarding into Scholardex.
  Goal: support user-triggered non-Scopus/WoS/Scholar publication imports as first-class canonical ingestion into Scholardex identity/link models.
  Deliverable: migrated in-place user publication wizard onboarding flow modeled as `USER_DEFINED` source events/facts with deterministic IDs, explicit review/moderation metadata, and integration with canonical Scholardex identity, source-link, conflict, and projection contracts.
  Exit criteria: the existing `/user/publications/add` wizard submits `USER_DEFINED` publication onboarding through the canonical Scholardex ingestion path; publication/forum/authorship/linked-affiliation lineage is deterministic and replay-safe; review/moderation state is explicit in metadata without requiring a separate admin approval workflow; imported records become visible through canonical Scholardex projections and existing user/admin operability surfaces.
  Status: completed on 2026-03-30.
  Handover:
  - The user publication wizard remains canonical and in-place at `GET /user/publications/add`.
  - Wizard submit now emits canonical `USER_DEFINED` import events with deterministic `USER_DEFINED:FORUM:*`, `USER_DEFINED:PUBLICATION:*`, and `USER_DEFINED:EID:*` identifiers.
  - USER_DEFINED source facts, canonicalization, source-link integration, and projection rebuild flow are implemented without a separate admin approval workflow.
  - Admin diagnostics and maintenance surfaces exist under `/admin/user-defined-triage` and `/admin/initialization/user-defined/*`.
  - Archived contract and closeout docs:
    - `docs/tasks/closed/h21.1-user-defined-wizard-onboarding-contract.md`
    - `docs/tasks/closed/h21.2-user-defined-wizard-submit-migration.md`
    - `docs/tasks/closed/h21.3-user-defined-facts-canonicalization.md`
    - `docs/tasks/closed/h21.4-user-defined-operability-admin-triage.md`
    - `docs/tasks/closed/h21.6-user-defined-onboarding-closeout.md`
  Subtasks:
  - [x] `H21.1` Lock the `USER_DEFINED` wizard-onboarding contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h21.1-user-defined-wizard-onboarding-contract.md`.
  - [x] `H21.2` Migrate wizard submission into first-class `USER_DEFINED` canonical ingest.
    Handover:
    - Runtime wizard submit path now ingests `USER_DEFINED` publication events through the canonical import-event pipeline.
  - [x] `H21.3` Align canonical linking, lineage, and review metadata for wizard-created entities.
    Handover:
    - USER_DEFINED source facts and canonicalization now propagate lineage and review metadata into Scholardex facts/source-links.
  - [x] `H21.4` Integrate operability and admin triage for `USER_DEFINED` onboarding.
    Handover:
    - USER_DEFINED triage and maintenance surfaces are live and source-filtered.
  - [x] `H21.5` Add regression and projection-visibility coverage for migrated wizard onboarding.
    Handover:
    - Regression coverage includes wizard submit, source-link alias normalization, USER_DEFINED fact building/canonicalization, triage, initialization, and operability metrics.
  - [x] `H21.6` Closeout docs and route/task handoff.
    Handover:
    - Closeout source of truth: `docs/tasks/closed/h21.6-user-defined-onboarding-closeout.md`.

## H27 Canonical Entity API Migration From Scopus Compatibility Routes

Archived from `TASKS.md` on 2026-03-30 after H27.1-H27.4 closure.

- [x] `H27` Canonical entity API migration from Scopus compatibility routes.
  Goal: replace the legacy public `/api/scopus/**` contract with a canonical source-agnostic entity API that matches the current Scholardex-backed runtime model.
  Deliverable: breaking public API migration from `/api/scopus/authors`, `/api/scopus/forums`, and `/api/scopus/affiliations` to canonical `/api/entities/authors`, `/api/entities/forums`, and `/api/entities/affiliations`, including aligned DTO naming, controller/service contract updates, and documentation/test refresh.
  Exit criteria: the public entity-read API no longer exposes Scopus-branded routes or `Scopus*` response contract names for Scholardex-backed author/forum/affiliation reads; canonical `/api/entities/**` endpoints are the only supported routes; docs, tests, and guardrails reflect the new contract explicitly.
  Status: completed on 2026-03-30.
  Handover:
  - Canonical public entity-read APIs are `GET /api/entities/authors`, `GET /api/entities/forums`, and `GET /api/entities/affiliations`.
  - Public Java/API response types for these APIs are `Scholardex*` rather than `Scopus*`, while the JSON wire shape remains unchanged.
  - Legacy `/api/scopus/authors|forums|affiliations` routes are removed with no redirect or alias compatibility window.
  - Positive contract/security coverage now targets `/api/entities/**`, and negative coverage locks the removed `/api/scopus/**` routes for both unauthenticated and authenticated access paths.
  - Contract source of truth: `docs/tasks/closed/h27.1-canonical-entity-api-contract.md`.
  - Closeout source of truth: `docs/tasks/closed/h27.3-entity-api-cutover-closeout.md`.
  Subtasks:
  - [x] `H27.1` Lock the canonical entity API contract.
    Handover:
    - Archived contract lock: `docs/tasks/closed/h27.1-canonical-entity-api-contract.md`.
  - [x] `H27.2` Implement canonical entity routes and DTO renames.
    Handover:
    - Runtime entity-read APIs now resolve only through `/api/entities/**`.
    - Public `Scopus*` entity API DTO names were replaced with `Scholardex*`.
  - [x] `H27.3` Remove Scopus API compatibility routes and update public docs.
    Handover:
    - Closeout note: `docs/tasks/closed/h27.3-entity-api-cutover-closeout.md`.
    - Historical H17/H23 notes remain unchanged as historical evidence only.
  - [x] `H27.4` Refresh regression coverage and route guardrails for canonical entity APIs.
    Handover:
    - Removed-route behavior is protected in `ApiSecurityContractTest`.
    - Legacy route-mapping guardrail lives in `EntityApiRouteGuardrailTest`.

## H25 Uniform Entity Routes And Shared Read-View Consolidation

Archived from `TASKS.md` on 2026-03-13 after H25.1-H25.5 closure.

- [x] `H25` Uniform entity routes and shared read-view consolidation.
  Goal: eliminate duplicate MVC pages/routes for shared read surfaces across `/user/*` and `/admin/*`, and align navigation with canonical entity-based routes while keeping admin-only management tools separate.
  Deliverable: canonical authenticated MVC routes for shared entities (`/forums`, `/wos/categories`, `/core/rankings`, `/universities`, `/events`), trimmed `/user/*` routes for user-owned surfaces, removal of duplicate admin read views, and role-driven sidebar selection instead of hardcoded admin/user sidebar fragments per template.
  Exit criteria: shared entity reads resolve through one canonical route family regardless of role; duplicate admin read pages for forums/rankings/universities/events are removed; user-owned surfaces remain under `/user/*`; sidebar/navigation is selected by role at runtime rather than hardcoded per template; legacy duplicate read routes are removed and all callers/tests/docs are aligned to the new route model.
  Status: completed on 2026-03-13.
  Handover:
  - Shared authenticated MVC reads are canonical under `/forums`, `/wos/categories`, `/core/rankings`, `/universities`, and `/events`.
  - User-owned MVC routes are canonical under `/user/*`, with legacy aliases removed rather than redirected.
  - Sidebar selection is centralized through `fragments :: sidebar(activeSection)` with role-aware context instead of template-specific admin/user fragment selection.
  - Route ownership source of truth: `docs/tasks/closed/h25.1-canonical-route-ownership-contract.md`.
  - Steady-state route map: `docs/tasks/closed/h23.5-route-map-and-closeout.md`.
  Subtasks:
  - [x] `H25.1` Lock canonical route and ownership contract.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h25.1-canonical-route-ownership-contract.md`.
  - [x] `H25.2` Consolidate shared entity MVC routes and remove duplicate admin read pages.
    Handover:
    - Shared canonical route families now serve the consolidated read surfaces.
    - Duplicate admin read GET aliases under `/admin/rankings/*` are removed.
  - [x] `H25.3` Normalize remaining user-owned route families.
    Handover:
    - Canonical user routes include `/user/activities*`, `/user/individual-reports*`, `/user/publications/scopus-tasks`, `/user/tasks/scopus/update-publications`, `/user/tasks/scopus/update-citations`, and `/user/exports/cnfis`.
  - [x] `H25.4` Replace hardcoded admin/user sidebar composition with role-based layout selection.
    Handover:
    - Runtime templates use unified sidebar composition with role-aware selection instead of hardcoded `admin-sidebar` and `user-sidebar` bindings.
  - [x] `H25.5` Remove stale route debt and align verification/docs.
    Handover:
    - `/admin/scopus/**` MVC compatibility mappings are removed.
    - Route guardrails and route-map docs were updated to enforce canonical shared/user route families.

## H26 Canonical User Dashboard Route And Post-H25 Naming Cleanup

Archived from `TASKS.md` on 2026-03-13 after H26.1-H26.4 closure.

- [x] `H26` Canonical user dashboard route and post-H25 naming cleanup.
  Goal: finish the post-H25 cleanup by aligning the remaining runtime route contract, live template/view names, and active docs with the canonical MVC route model already adopted in H25.
  Deliverable: canonical `/user/dashboard` route with `/user` retained only as a compatibility redirect, renamed live MVC template/view names that match canonical entities/routes, and active docs/tests/guardrails updated to reflect the steady-state route model without stale pre-H25 naming.
  Exit criteria: `/user/dashboard` is the documented and implemented dashboard route; `/user` no longer serves as the primary route; live runtime template/view names no longer use stale `scholardex` or camelCase report/activity naming where canonical names now exist; active docs/tests/guardrails describe only current route families except where old routes are intentionally referenced as removal assertions.
  Status: completed on 2026-03-13.
  Handover:
  - `GET /user/dashboard` is canonical and `GET /user` is compatibility redirect-only.
  - Live shared/user view names are normalized to entity-aligned template families such as `forums/*`, `wos/*`, `core/*`, `universities/*`, `events/*`, `shared/not-found`, `user/activities*`, and `user/individual-reports*`.
  - Active docs and route guardrails now enforce canonical naming while reserving removed aliases for historical inventories and explicit removal assertions only.
  Subtasks:
  - [x] `H26.1` Canonicalize the dashboard route.
    Handover:
    - Canonical dashboard route is `GET /user/dashboard`; `GET /user` redirects there for compatibility.
  - [x] `H26.2` Rename live runtime views/templates to canonical entity names.
    Handover:
    - Runtime view-name/template families are aligned with canonical entity naming and no longer use stale `scholardex` or camelCase activity/report names.
  - [x] `H26.3` Clean up active route-documentation drift.
    Handover:
    - Active docs now describe canonical H25/H26 route families; legacy aliases remain only in historical inventory/closeout material.
  - [x] `H26.4` Tighten verification around canonical naming and aliases.
    Handover:
    - Route guardrails and MVC/security tests protect canonical `/user/dashboard` behavior, renamed view tokens, and removed-alias invariants.

## H24 PostgreSQL Cutover For `/api/rankings/wos`

Archived from `TASKS.md` on 2026-03-13 after H24.1-H24.5 closure.

- [x] `H24` PostgreSQL cutover for `/api/rankings/wos`.
  Goal: migrate the `/api/rankings/wos` search/paging API from Mongo-backed `WosRankingView` reads to the existing PostgreSQL reporting read model while preserving the public contract and current UI behavior.
  Deliverable: Postgres-backed query implementation for `/api/rankings/wos`, runtime cutover wiring, and targeted parity/regression coverage proving contract-equivalent behavior for paging, sorting, search, validation, and authentication.
  Exit criteria: `/api/rankings/wos` is served from PostgreSQL `reporting_read.wos_ranking_view`; request/response shape, sort semantics, search behavior, and auth contract remain stable; targeted parity/regression tests cover the cutover and protect against reintroduction of Mongo-backed reads for this API.
  Status: completed on 2026-03-13.
  Handover:
  - Public API route intentionally remains `GET /api/rankings/wos`.
  - Runtime query path is now `WosRankingApiController -> WosRankingQueryService -> PostgresWosRankingReadPort`.
  - Runtime storage authority for this API is `reporting_read.wos_ranking_view`; Mongo fallback is intentionally removed.
  - Contract source of truth: `docs/tasks/closed/h24.1-wos-rankings-postgres-query-contract.md`.
  - Closeout source of truth: `docs/tasks/closed/h24.5-wos-rankings-postgres-closeout.md`.
  Subtasks:
  - [x] `H24.1` Lock `/api/rankings/wos` Postgres query contract.
    Deliverable: implementation-ready contract for the SQL-backed `/api/rankings/wos` search path, including allowed sort fields, direction rules, query normalization, prefix-search behavior, paging semantics, and response-shape compatibility.
    Exit criteria: the Postgres implementation target is decision-locked and explicitly matches the current public API contract unless a change is intentionally recorded.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h24.1-wos-rankings-postgres-query-contract.md`.
  - [x] `H24.2` Implement PostgreSQL read port for WoS rankings API.
    Deliverable: dedicated Postgres query component for `/api/rankings/wos` backed by `reporting_read.wos_ranking_view`, returning the existing `WosRankingPageResponse`.
    Exit criteria: the read port supports current paging/sorting/search behavior and reads only from PostgreSQL for this API surface.
    Handover:
    - Runtime SQL adapter: `PostgresWosRankingReadPort`.
    - Existing read model reused from H22 projection state; no new schema migration required for H24.
  - [x] `H24.3` Cut over controller/service wiring for `/api/rankings/wos`.
    Deliverable: runtime wiring that routes `WosRankingApiController` through the new Postgres-backed query path and removes direct Mongo query dependency from the API service.
    Exit criteria: `/api/rankings/wos` no longer depends on `MongoTemplate`/Mongo query code at runtime, while the public route and response contract remain unchanged.
    Handover:
    - `WosRankingQueryService` now requires the Postgres read port and throws if it is unavailable, preventing silent Mongo fallback drift.
  - [x] `H24.4` Add parity and regression coverage for the API cutover.
    Deliverable: focused tests covering request validation, authenticated access, paging, allowed sorts, prefix search semantics, and representative parity between legacy Mongo behavior and the new Postgres path.
    Exit criteria: automated tests fail on contract drift or accidental reintroduction of Mongo-backed `/api/rankings/wos` reads.
    Handover:
    - SQL behavior tests: `PostgresWosRankingReadPortTest`.
    - Runtime cutover tests: `WosRankingQueryServiceTest`.
    - Controller/API contract tests: `WosRankingApiControllerContractTest`.
    - Cross-store parity tests: `WosRankingApiParityIntegrationTest`.
  - [x] `H24.5` Closeout docs and task handoff.
    Deliverable: backlog/docs/task notes updated to record `/api/rankings/wos` as PostgreSQL-backed while retaining the legacy API name intentionally.
    Exit criteria: the steady-state route/storage decision is documented clearly enough that future cleanup does not treat this API as still Mongo-backed.
    Handover:
    - Closeout source of truth: `docs/tasks/closed/h24.5-wos-rankings-postgres-closeout.md`.

## H22 Postgres Reporting Core + Mongo Ingest Baseline Migration

Archived from `TASKS.md` on 2026-03-13 after H22.1-H22.10 closure.

- [x] `H22` Postgres reporting core + Mongo ingest baseline migration.
  Goal: improve WoS scoring/reporting read and compute latency by moving reporting read models to PostgreSQL while keeping MongoDB as the ingestion/event/queue write model.
  Deliverable: architecture contract, SQL read schema, projection/sync pipeline, SQL query cutover for WoS scoring/reporting flows, and operability/rollback guardrails.
  Exit criteria: Mongo remains authoritative for raw import events/queues; WoS/scoring/report read models are served from PostgreSQL; SQL joins/materialized views back WoS scoring and citation-heavy report paths; parity and performance gates pass before full cutover.
  Status: completed on 2026-03-13.
  Subtasks:
  - [x] `H22.1` Architecture contract and bounded-context map.
    Status: completed on 2026-03-11.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h22.1-postgres-reporting-architecture-contract.md`.
    - Companion sequence flows: `docs/tasks/closed/h22.1-postgres-reporting-sequences.md`.
  - [x] `H22.2` PostgreSQL schema for WoS/scoring/reporting read core.
    Status: completed on 2026-03-11.
    Handover:
    - Schema contract: `docs/tasks/closed/h22.2-postgres-reporting-schema-contract.md`.
    - Flyway migrations: `V1__h22_2_create_pg_enums.sql`, `V2__h22_2_create_reporting_core_tables.sql`, `V3__h22_2_create_reporting_core_indexes.sql`.
    - Migration verification test: `PostgresReportingReadSchemaMigrationIntegrationTest`.
  - [x] `H22.3` Projection/sync pipeline from canonical Mongo to PostgreSQL.
    Status: completed on 2026-03-11.
    Handover:
    - Projection contract: `docs/tasks/closed/h22.3-postgres-projection-contract.md`.
    - Projection state migration: `V4__h22_3_projection_state_tables.sql`.
    - Projector service: `JdbcPostgresReportingProjectionService` + `PostgresReportingProjectionService`.
    - Verification tests: `PostgresReportingProjectionServiceIntegrationTest`, `JdbcPostgresReportingProjectionServiceTest`.
  - [x] `H22.4` Query-layer cutover to SQL-backed WoS scoring/report reads.
    Status: completed on 2026-03-11.
    Handover:
    - Cutover contract: `docs/tasks/closed/h22.4-query-layer-cutover-contract.md`.
    - Runtime switch/cutover guards: `ReportingReadStore`, `ReportingReadStoreSelector`, `PostgresReadCutoverGuard`.
    - Verification tests: `ReportingReadStoreRoutingTest`, `PostgresReportingLookupFacadeTest`, `ScholardexCutoverGuardrailTest`.
  - [x] `H22.5` Materialized views and refresh strategy for heavy reads.
    Status: completed on 2026-03-12.
    Handover:
    - Contract: `docs/tasks/closed/h22.5-materialized-views-refresh-contract.md`.
    - Migrations: `V5__h22_5_create_materialized_views.sql`, `V6__h22_5_mv_refresh_state_tables.sql`.
    - Refresh orchestration: `PostgresMaterializedViewRefreshService`, `JdbcPostgresMaterializedViewRefreshService`.
  - [x] `H22.6` Dual-read parity and performance gate.
    Status: completed on 2026-03-12.
    Handover:
    - Contract: `docs/tasks/closed/h22.6-dual-read-parity-performance-gate-contract.md`.
    - Migration/state tables: `V7__h22_6_dual_read_gate_tables.sql`.
    - Runtime gate service: `DualReadGateService`, `JdbcDualReadGateService`.
  - [x] `H22.7` Operationalization, rollback, and rebuild playbook.
    Status: completed on 2026-03-12.
    Handover:
    - Runbook: `docs/tasks/closed/h22.7-operational-rollback-rebuild-playbook.md`.
    - Ops status service: `H22OperationalStatusService`, `DefaultH22OperationalStatusService`.
  - [x] `H22.8` Post-integration layering and naming consistency.
    Status: completed on 2026-03-12.
    Handover:
    - Scholardex naming consistency for admin/read surfaces and associated wiring/tests.
  - [x] `H22.9` Transitional path and config hygiene after Postgres integration.
    Status: completed on 2026-03-13.
    Handover:
    - Runtime routing and config trimmed to Postgres-first operational mode for migrated H22 surfaces.
    - `/admin/initialization` wording/layout cleanup for H22 cards and operational status.
  - [x] `H22.10` H22 test-harness cleanup and deterministic gate baseline refresh.
    Status: completed on 2026-03-13.
    Handover:
    - Deterministic gate seed selection in `JdbcDualReadGateService`.
    - Focused harness baseline command: `./gradlew testH2210Baseline` and `npm run verify-h22-baseline`.

## H23 Scholardex UI Route Consolidation and Steady-State Naming Cleanup

Archived from `TASKS.md` on 2026-03-13 after H23.1-H23.5 closure.

- [x] `H23` Scholardex UI route consolidation and steady-state naming cleanup.
  Goal: reduce maintenance overhead and product-surface drift by consolidating MVC/UI routes around Scholardex-first forum navigation while retiring the split between Scopus forum pages and WoS ranking pages.
  Deliverable: canonical Scholardex forum routes/templates for public and admin UI, WoS-specific category pages, trimmed MVC compatibility redirects/helpers, and updated docs/guardrails that reflect the new steady-state navigation model.
  Exit criteria: covered MVC surfaces use the new canonical route families, legacy MVC paths are either redirected or clearly marked transitional, and tests/guardrails enforce the consolidated UI architecture.
  Status: completed on 2026-03-13.
  Handover:
  - Canonical public MVC routes are `/scholardex/forums`, `/scholardex/forums/{id}`, `/rankings/categories`, and `/rankings/categories/{key}`.
  - Canonical admin MVC routes are under `/admin/scholardex/**`; retained compatibility shims remain under `/admin/scopus/**`, `/admin/scopus/venues*`, `/rankings/wos`, and `/user/rankings/{id}`.
  - Historical note: as of H23 closeout, `/api/scopus/**` and `/api/rankings/wos` were retained as stable API namespaces; H27 later superseded the entity-read `/api/scopus/authors|forums|affiliations` contract with canonical `/api/entities/**`, while `/api/rankings/wos` remained unchanged.
  - New H23 paged category API: `/api/rankings/categories`.
  - Route map and closeout doc: `docs/tasks/closed/h23.5-route-map-and-closeout.md`.
  - H23 verification entrypoint: `npm run verify-h23-ui`.
  Subtasks:
  - [x] `H23.1` Inventory and classify transitional debt.
    Status: completed on 2026-03-13.
    Handover:
    - Debt inventory: `docs/tasks/closed/h23.1-transitional-debt-inventory.md`.
  - [x] `H23.2` Scholardex UI route consolidation.
    Status: completed on 2026-03-13.
    Handover:
    - Canonical forum/publication/affiliation/admin routes moved to Scholardex-first MVC families.
  - [x] `H23.3` Unified forum detail and UI naming normalization.
    Status: completed on 2026-03-13.
    Handover:
    - Canonical forum detail moved to `/scholardex/forums/{id}` with journal/conference/book branching.
  - [x] `H23.4` Route-aware guardrails and deterministic UI verification refresh.
    Status: completed on 2026-03-13.
    Handover:
    - Deterministic route/UI guardrails and paged WoS category coverage now live behind `npm run verify-h23-ui`.
  - [x] `H23.5` Docs, route map, and task closeout.
    Status: completed on 2026-03-13.
    Handover:
    - Route map, verification contract, and task closeout aligned to the shipped H23 route model.

## H11-H14 Recovery Wave

Archived from `TASKS.md` on 2026-03-06 after closure and cleanup.

- [x] `H11` Functional contract hardening and null-safety normalization.
  Status: completed on 2026-03-04.
  Notes: core nullable contracts normalized to deterministic behavior and guarded with regression checks.

- [x] `H12` External integration and import correctness uplift.
  Status: completed on 2026-03-04.
  Notes: importer/scheduler behavior hardened with deterministic error accounting and integration guardrails.

- [x] `H14` WoS Approach 3 implementation (immutable ingestion ledger + rebuildable views).
  Status: completed on 2026-03-06.
  Notes: H14.1-H14.16 resolved; H14.14 and H14.15 were explicitly dropped by decision.
  Highlights:
  - canonical WoS schema + identity + immutable import events + parser adapters + fact builders delivered,
  - IF source-policy enforced (`OFFICIAL_WOS_EXTRACT` only) while `IMPACT_FACTOR` remains operational,
  - projections/indexes/read-path/reporting cutover completed with cache-independent WoS lookup paths,
  - admin-triggered big-bang migration and parity reconciliation gates delivered,
  - residual H14 checks converted to automated tests (bundled SCIE/SSCI split, replay determinism, AIS/RIS/CNFIS parity stability).

## H15 CI Guardrail Realignment and Quality-Gate Restoration

Archived from `TASKS.md` on 2026-03-06 after closure and CI stabilization.

- [x] `H15` CI guardrail realignment and quality-gate restoration.
  Goal: restore trust in CI by aligning guardrail rules with the current post-H14 architecture and enforcing the complete guardrail set in GitHub workflows.
  Deliverable: updated guardrail scripts/workflows and a green full validation baseline (`verify-h09-baseline` + `gradlew check`) on compliant code.
  Exit criteria: CI fails only on real regressions (not stale policy checks), and required guardrails are consistently enforced on PR/push.
  Status: completed on 2026-03-06.
  Note: H15.1-H15.4 completed; guardrail scripts and quality workflows now align with post-H14 behavior and pass on rerun.
  Subtasks:
  - [x] `H15.1` Guardrail policy audit for stale assumptions.
    Deliverable: inventory of guardrails that still encode pre-H14 behavior (WoS cache and old CS dispatch assumptions).
    Exit criteria: each stale check has a documented intended replacement aligned with current architecture.
    Status: completed on 2026-03-06.
    Note: see `docs/tasks/closed/h15-guardrail-policy-audit.md` for stale/valid classification, source-of-truth mappings, and H15.2 decision-locked script updates.
    H15.2 handoff:
    - `verify-h06-persistence`: remove WoS ranking-cache/repository assertions for `CacheService`; keep edit/update canonical `findById` checks while allowing `buildCitationsView` `id/eid` compatibility fallback.
    - `verify-duplication-guardrails`: replace publication `bk/ch` delegation expectation with non-`ar/re/cp` empty-score policy; keep activity `Book/Book Series` delegation requirement unchanged.
  - [x] `H15.2` Guardrail script updates.
    Deliverable: update `verify-h06-persistence` and `verify-duplication-guardrails` to reflect current intended behavior.
    Exit criteria: scripts pass on compliant code and fail on true policy regressions.
    Status: completed on 2026-03-06.
    Note: script-only update completed in line with `docs/tasks/closed/h15-guardrail-policy-audit.md`; no runtime service code changed for this task.
  - [x] `H15.3` GitHub workflow enforcement completion.
    Deliverable: ensure quality workflows execute the full required guardrail set (including WoS parity baseline/integration checks) with failure artifacts.
    Exit criteria: PR/push pipelines consistently run and enforce the updated guardrails.
    Status: completed on 2026-03-06.
    Note: `h09-quality-gates.yml` guardrails job now runs a single explicit guardrail suite (`verify-architecture-boundaries`, `verify-h06-persistence`, `verify-h07-guardrails`, `verify-h08-baseline`, `verify-h12-integrations`, `verify-duplication-guardrails`, `verify-wos-parity-baseline`) with per-check CI logs and failure artifact upload.
  - [x] `H15.4` Full quality-gate recovery.
    Deliverable: restore green status for `npm run verify-h09-baseline` and `./gradlew check`.
    Exit criteria: both gates pass end-to-end and remain stable across reruns.
    Status: completed on 2026-03-06.
    Note: validated with repeated local runs of `npm run verify-h09-baseline` and `./gradlew check`; all checks passed consistently.

## H16 Java and Gradle Modernization Uplift

Archived from `TASKS.md` on 2026-03-06 after H16.1-H16.5 closure.

- [x] `H16` Java and Gradle modernization uplift.
  Goal: upgrade the runtime/build toolchain to newer Java + Gradle versions with deterministic local/CI behavior.
  Deliverable: aligned Java/Gradle versions, dependency/plugin compatibility fixes, and green baseline gates.
  Exit criteria: `java-smoke`, `quality-full`, and local `./gradlew check` pass on the upgraded toolchain without environment-specific hacks.
  Status: completed on 2026-03-06.
  Note: upgrade and validation evidence recorded in `docs/tasks/closed/h16-toolchain-modernization-matrix.md` (including H16.5 closeout evidence).
  Subtasks:
  - [x] `H16.1` Baseline and target matrix.
    Deliverable: documented current Java/Gradle/plugin/dependency versions and an explicit target upgrade matrix with compatibility notes.
    Exit criteria: upgrade scope and order are fixed, with rollback path and known risk hotspots identified.
    Status note (2026-03-06): completed in `docs/tasks/closed/h16-toolchain-modernization-matrix.md` with pinned target direction (Java 25, Gradle 9.1.x+, Spring Boot 4.0.x LTS-target line), compatibility ownership, and rollback guards.
  - [x] `H16.2` Gradle wrapper and build tooling bump.
    Deliverable: upgraded Gradle wrapper and required build script/property updates to match the target Java/toolchain baseline.
    Exit criteria: `./gradlew --version`, configuration phase, and core build lifecycle start cleanly on the new wrapper.
    Status note (2026-03-06): completed with wrapper `9.1.0`, Java toolchain/launchers moved to `25`, macOS wrapper guard updated for JDK 25, and dependency-management plugin bumped to `1.1.7` for Gradle 9 compatibility (`--version`, `help`, `compileJava` all pass).
  - [x] `H16.3` Plugin and dependency compatibility remediation.
    Deliverable: minimal set of plugin/dependency upgrades or config changes required to restore compile/test/check behavior.
    Exit criteria: no deprecated/broken build integrations remain on critical paths (`compileJava`, `test`, `check`).
    Status note (2026-03-06): completed by upgrading Spring Boot to `4.0.2`, adding Boot 4 test-slice modules (`spring-boot-webmvc-test`, `spring-boot-data-mongodb-test`), pinning Testcontainers to `1.19.7`, migrating security/health/error APIs to Boot 4/Security 7 namespaces, and updating affected tests (`@MockBean -> @MockitoBean`, Boot 4 test annotation imports, redirect expectations); `compileJava`, `test`, and `check` pass.
  - [x] `H16.4` CI parity and deterministic execution hardening.
    Deliverable: workflow and environment alignment updates so local and CI use the same Java/Gradle assumptions.
    Exit criteria: `java-smoke` and `quality-full` run with identical toolchain intent across local and CI.
    Status note (2026-03-06): completed by updating quality/security workflows to Temurin Java 25 and standardizing Java-job Gradle invocations to wrapper + `--no-daemon`; docs updated in `docs/tasks/closed/h09-ci-gates.md` and `docs/tasks/closed/h16-toolchain-modernization-matrix.md`.
  - [x] `H16.5` Validation and closeout evidence.
    Deliverable: run log + short closeout note capturing command results, residual risks, and follow-ups.
    Exit criteria: local `./gradlew check` and CI gates (`java-smoke`, `quality-full`) are green on the upgraded stack.
    Status note (2026-03-06): completed with evidence in `docs/tasks/closed/h16-toolchain-modernization-matrix.md` (H16.5 section); local validation set passed (`./gradlew --version`, `compileJava`, `test --tests "*CoreApplicationTests"`, `check`). CI gate confirmation is tracked as follow-up via PR workflow run.

## Vendor Asset Migration Tasks

Tracking migration from `/vendor/*` assets to bundled `/assets/*` assets.

- [x] `T01` Goal: Create task tracker and migration guardrails.
  Files/areas: `/TASKS.md`
  Automated checks: `./gradlew test`
  Done criteria: tracker exists with ordered, test-gated tasks.
  Notes: Completed.

- [x] `T02` Goal: Introduce frontend toolchain (npm + bundler) without switching templates yet.
  Files/areas: `/package.json`, lockfile, bundler config, `frontend/` source dir.
  Automated checks: `npm ci`, `npm run build`, `./gradlew test`
  Done criteria: deterministic assets generated under `src/main/resources/static/assets/`.
  Notes: Completed. `package.json` + lockfile present and install/build checks pass.

- [x] `T03` Goal: Wire baseline vendor equivalents into bundled entrypoints.
  Files/areas: `package.json`, frontend entrypoint files, build scripts.
  Automated checks: `npm run build`, `npm run verify-assets`, `./gradlew test`
  Done criteria: bundle contract includes Bootstrap, jQuery, DataTables, Chart.js, Font Awesome, jquery-easing.
  Notes: Completed with committed `app.css`/`app.js` and npm entrypoint definitions.

- [x] `T04` Goal: Add automated template asset-path validation.
  Files/areas: `scripts/verify-template-assets.js`, npm script wiring.
  Automated checks: `npm run verify-template-assets`, `./gradlew test`
  Done criteria: validator fails on reintroduced `/vendor/` usage.
  Notes: Completed.

- [x] `T05` Goal: Incremental migration batch A (shared pages/fragments).
  Files/areas: shared template patterns used by migrated pages.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: migrated batch has no direct `/vendor/` references.
  Notes: Completed.

- [x] `T06` Goal: Incremental migration batch B (admin pages).
  Files/areas: `src/main/resources/templates/admin/**`
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: admin templates use bundled assets and no `/vendor/...` remains.
  Notes: Completed (excluding `*-bak.html` backups from strict validator).

- [x] `T07` Goal: Incremental migration batch C (user pages).
  Files/areas: `src/main/resources/templates/user/**`
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: user templates no longer depend on `/vendor/...`.
  Notes: Completed.

- [x] `T08` Goal: Remove obsolete vendor tree and machine artifacts.
  Files/areas: `src/main/resources/static/vendor/**`, `.gitignore`.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`, `rg -n '/vendor/' src/main/resources/templates`
  Done criteria: no production template refs to `/vendor/`; `.DS_Store` ignored.
  Notes: Completed.

- [x] `T09` Goal: Documentation and developer workflow finalization.
  Files/areas: `README.md`, `CONTRIBUTING.md`.
  Automated checks: `npm run build`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: docs reflect reproducible frontend + backend verification commands.
  Notes: Completed.

- [x] `T10` Goal: Final regression gate and signoff.
  Files/areas: `TASKS.md` status updates.
  Automated checks: `npm ci`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `./gradlew test`
  Done criteria: all checks green and tasks complete.
  Notes: Completed. Full gate passed: `npm ci`, `npm run build`, `npm run verify-assets`, `npm run verify-template-assets`, `./gradlew check`.

## H01 Duplicate and Drift Audit

Archived from `TASKS.md` on 2026-03-03 after H01 closure.

- [x] `H01` Duplicate code and drift audit.
  Goal: identify copy-paste clusters (backend, frontend, templates, scripts) and detect behavior drift between near-identical implementations.
  Deliverable: duplication inventory with risk ranking and consolidation candidates.
  Exit criteria: top high-risk duplicates have an agreed merge strategy and owners.
  Notes: Completed on 2026-03-03. C01/C03/C04 prioritized slices were executed and stabilized with regression guards and reintroduction checks.

### H01 Subtasks

- [x] `H01-S01` Inventory likely duplicate clusters.
  Deliverable: `docs/tasks/closed/h01-duplication-inventory.md`.
  Notes: Completed.

- [x] `H01-S03` Identify behavioral drift inside top clusters.
  Deliverable: `docs/tasks/closed/h01-drift-findings.md`.
  Notes: Completed on 2026-03-03. `C01`, `C03`, `C04`, `C05`, and `C06` analyzed with decisions/evidence; C04 closure slices completed (`D01/D02/D03/D04/D05/D06/D07` resolved for C04 scope).

- [x] `H01-S04` Prioritize by risk and blast radius.
  Deliverable: priority table in `docs/tasks/closed/h01-duplication-inventory.md`.
  Notes: Completed (`C01 (P0)` -> `C04 (P1)` -> `C06 (P2)`).

- [x] `H01-S05` Define consolidation strategy per priority cluster.
  Deliverable: `docs/tasks/closed/h01-consolidation-strategy.md`.
  Notes: Completed.

- [x] `H01-S06` Create regression guards before refactor.
  Deliverable: focused tests + coverage notes in `docs/tasks/closed/h01-regression-guards.md`.
  Notes: Completed.

- [x] `H01-S07` Execute first consolidation slice (small, high-value).
  Deliverable: C04 sub-cluster B consolidation slices.
  Notes: Completed on 2026-03-03. Factory fail-fast + CS dispatch alignment completed.

- [x] `H01-S08` Prevent reintroduction.
  Deliverable: CI/local duplication check command + contributor note.
  Notes: Completed on 2026-03-03. Added `npm run verify-duplication-guardrails`, wired into `./gradlew check`.

### H01 Cluster Closures

- [x] `C01` `CNFISScoringService` vs `CNFISScoringService2025`.
  Notes: Closed on 2026-03-03. Canonical spec in `docs/c01-cnfis-rule-spec.md`, edge-case tests expanded, no-behavior cleanup applied.

- [x] `C02` Admin template backups (`*-bak.html`) vs active templates.
  Notes: Resolved on 2026-03-03 by deleting `admin/researchers-bak.html`.

- [x] `C03` Admin rankings backup template pair.
  Notes: Resolved on 2026-03-03 by deleting `admin/rankings-view-bak.html`.

- [x] `C04` Reporting/scoring service family.
  Notes: Resolved on 2026-03-03 by slices 2-5 (shared category/subtype contracts, dispatch/factory alignment, metadata/logger cleanup).

## H02 First Subtask List (Planning Mode Seed)

Scope: `H02` Architecture boundaries and ownership.

- [x] `H02-S01` Map current runtime architecture and dependency directions.
  Goal: produce a factual map of layers/modules and how requests/flows travel through them.
  Inputs: package structure, controller/service/repository wiring, frontend template/script entrypoints.
  Deliverable: `docs/tasks/closed/h02-architecture-map.md` (current-state diagram + dependency table).
  Exit criteria: all major runtime paths (web -> service -> data and template/script flow) are represented.
  Status: completed on 2026-03-03.

- [x] `H02-S02` Define target boundaries and ownership zones.
  Goal: define what belongs in each layer/module and who owns cross-cutting areas.
  Inputs: `H02-S01` map + current drift/findings from H01.
  Deliverable: `docs/tasks/closed/h02-boundaries-and-ownership.md` (zones, responsibilities, ownership matrix).
  Exit criteria: each major package/area has a declared owner and allowed responsibilities.
  Status: completed on 2026-03-03.

- [x] `H02-S03` Specify allowed dependency rules.
  Goal: convert boundaries into explicit allow/deny dependency rules.
  Inputs: boundary definitions and known problematic couplings.
  Deliverable: dependency rule set in `docs/tasks/closed/h02-boundaries-and-ownership.md` (or `docs/tasks/closed/h02-dependency-rules.md`).
  Exit criteria: developers can decide placement/dependencies without ambiguity.
  Status: completed on 2026-03-03 (`docs/tasks/closed/h02-dependency-rules.md`).

- [x] `H02-S04` Identify and classify current boundary violations.
  Goal: detect concrete code locations that violate the declared dependency rules.
  Inputs: declared rules + current codebase scan.
  Deliverable: `docs/tasks/closed/h02-violations.md` with severity (`high|medium|low`) and rationale.
  Exit criteria: every violation has a file reference and a proposed remediation direction.
  Status: completed on 2026-03-03 (`docs/tasks/closed/h02-violations.md`).
  Note: V01 follow-up slice 4 completed (`AdminGroupController` export/CNFIS via `GroupExportFacade` and `GroupCnfisExportFacade`); tracked baseline pair is now at 73.9% repository-field reduction (`23 -> 6`), and AdminGroup repository debt is closed.
  Note: V02 baseline slice completed for the same pair (User/AdminGroup): direct controller imports of `core.service.reporting` removed; export/reporting coupling now facade-backed.
  Note: V02 AdminView verification slice completed: no direct `Z1 -> Z3` reporting-service coupling found in `AdminViewController`; transport-layer scan baseline is clean.
  Note: V03 focused AdminView slice delivered: institution publications/export data assembly and ranking compute/merge flows moved behind `AdminInstitutionReportFacade` and `RankingMaintenanceFacade`.
  Note: V03 final closure slice delivered: remaining transport assembly moved behind `AdminScopusFacade` and `ForumExportFacade` (`/admin/scopus/publications/search`, `/admin/scopus/publications/citations`, `/api/export`); V03 marked complete for current H02 scope.
  Note: V04 execution slice completed: reporting back-edge to `CacheService` removed via `ReportingLookupPort` + `CacheBackedReportingLookupFacade`; `service/reporting/**` now has zero `CacheService` references/imports.

- [x] `H02-S05` Define phased remediation plan for violations.
  Goal: prioritize fixes by blast radius and effort without blocking delivery.
  Inputs: violation inventory + ownership matrix.
  Deliverable: `docs/tasks/closed/h02-remediation-plan.md` with phased slices (`R1`, `R2`, ...).
  Exit criteria: top-priority violations have actionable implementation slices and sequencing.
  Status: completed on 2026-03-03 (`docs/tasks/closed/h02-remediation-plan.md` with `R1..R4`).

- [x] `H02-S06` Add lightweight enforcement in workflow.
  Goal: add practical checks/review guardrails so boundaries stay intact.
  Inputs: dependency rules + remediation strategy.
  Deliverable: checks and contributor guidance updates (`CONTRIBUTING.md`, optional scripts/CI rule).
  Exit criteria: at least one automated or checklist-based gate prevents new boundary violations.
  Status: completed on 2026-03-03.
  Note: added `npm run verify-architecture-boundaries` (`scripts/verify-architecture-boundaries.js`) to enforce: no new `Z1 -> Z4` controller repository imports (debt-aware allowlist), no `Z1 -> Z3` reporting imports in transport, and no `CacheService` usage in `service/reporting/**`.

- [x] `H02-S07` Close H02 with adoption notes.
  Goal: finalize architecture baseline and usage guidance for future tasks.
  Inputs: completed H02 artifacts and enforcement setup.
  Deliverable: H02 closeout note in `docs/tasks/closed/h02-boundaries-and-ownership.md` + `TASKS.md` status updates.
  Exit criteria: H02 can be treated as reference baseline for H03+ planning and implementation.
  Status: completed on 2026-03-03.
  Note: H02 is now the active architecture reference baseline; reopen H02 only for boundary-rule changes or newly detected violations.

## H03 Contract and Behavior Baseline

Archived from `TASKS.md` on 2026-03-03 after H03 closure.

- [x] `H03` Contract and behavior baseline.
  Goal: capture current expected behavior for key flows before refactors.
  Deliverable: minimal contract suite (controller/service integration + key UI/API flows).
  Exit criteria: high-impact flows have regression coverage and a known pass/fail baseline.
  Notes: Completed on 2026-03-03. H03 is now the default pre-refactor safety baseline for reporting/export/ranking flows.

### H03 Subtasks

- [x] `H03-S01` Identify and rank critical runtime flows for contract coverage.
  Deliverable: `docs/tasks/closed/h03-flow-priority-map.md`.
  Notes: Completed.

- [x] `H03-S02` Define contract schema for prioritized flows.
  Deliverable: `docs/tasks/closed/h03-contract-schema.md`.
  Notes: Completed.

- [x] `H03-S03` Capture reporting/service characterization contracts.
  Deliverable: `docs/tasks/closed/h03-reporting-contracts.md`.
  Notes: Completed.

- [x] `H03-S04` Add controller-level behavior characterization tests for top flows.
  Deliverable: controller contract tests for User/AdminGroup/AdminView/Export high-priority routes.
  Notes: Completed.

- [x] `H03-S05` Add facade/application contract tests for orchestration outputs.
  Deliverable: expanded characterization tests for `UserReportFacade`, `GroupCnfisExportFacade`, `RankingMaintenanceFacade`.
  Notes: Completed.

- [x] `H03-S06` Assemble and enforce H03 baseline gate.
  Deliverable: `npm run verify-h03-baseline` + `CONTRIBUTING.md` usage guidance.
  Notes: Completed.

- [x] `H03-S07` Close H03 with adoption notes and forward links to H04.
  Deliverable: H03 closeout/adoption note in `docs/tasks/closed/h03-reporting-contracts.md` and task archive updates.
  Notes: Completed on 2026-03-03.

## H04 Test Strategy and Pyramid Rebalance

Archived from `TASKS.md` on 2026-03-03 after H04 closure.

- [x] `H04` Test strategy and pyramid rebalance.
  Goal: reduce fragile end-to-end reliance and improve unit/integration signal quality.
  Deliverable: test taxonomy, gap matrix, and priority test additions.
  Exit criteria: each critical feature has at least one stable automated regression test.
  Notes: Completed on 2026-03-03. H04 is now the active testing playbook baseline for refactor safety.

### H04 Subtasks

- [x] `H04-S01` Build current test inventory and taxonomy map.
  Deliverable: `docs/tasks/closed/h04-test-inventory.md`.
  Notes: Completed.

- [x] `H04-S02` Define target pyramid and quality criteria.
  Deliverable: `docs/tasks/closed/h04-test-strategy.md`.
  Notes: Completed.

- [x] `H04-S03` Create risk-weighted gap matrix for critical flows.
  Deliverable: `docs/tasks/closed/h04-gap-matrix.md`.
  Notes: Completed.

- [x] `H04-S04` Add missing unit tests for scorer/support logic hotspots.
  Deliverable: focused unit coverage additions for `G01-G03`.
  Notes: Completed.

- [x] `H04-S05` Add integration/slice tests for cross-layer seams.
  Deliverable: targeted contract/slice coverage additions for `G04-G07`.
  Notes: Completed; `G08` deferred to S06 infrastructure policy and then partially resolved.

- [x] `H04-S06` Introduce reliability and runtime guardrails for test execution.
  Deliverable: `verify-h04-baseline`, `verify-h04-mongo-integration`, `docs/tasks/closed/h04-reliability-guardrails.md`.
  Notes: Completed; `G09` resolved and `G08` initial Testcontainers tranche implemented.

- [x] `H04-S07` Close H04 with adoption notes and handoff to H05.
  Deliverable: H04 closeout section in `docs/tasks/closed/h04-test-strategy.md` + task archive updates.
  Notes: Completed on 2026-03-03.

## H05 Frontend Structure and Asset Discipline

Archived from `TASKS.md` on 2026-03-03 after H05 closure.

- [x] `H05` Frontend structure and asset discipline.
  Goal: standardize JS/CSS/template patterns to avoid divergent implementations.
  Deliverable: frontend conventions (entrypoints, shared utilities, template composition patterns).
  Exit criteria: duplicated UI logic is centralized and new pages follow the same conventions.
  Notes: Completed on 2026-03-03. H05 baseline is active via `docs/tasks/closed/h05-frontend-map.md`, `docs/tasks/closed/h05-frontend-conventions.md`, shared frontend modules, and template guardrails.

### H05 Subtasks

- [x] `H05-S01` Build frontend structure map and duplication baseline.
  Deliverable: `docs/tasks/closed/h05-frontend-map.md`.
  Notes: Completed.

- [x] `H05-S02` Define frontend conventions and ownership rules.
  Deliverable: `docs/tasks/closed/h05-frontend-conventions.md`.
  Notes: Completed.

- [x] `H05-S03` Extract shared template composition primitives.
  Deliverable: shared core template fragments + migrated includes.
  Notes: Completed (`core-styles`/`core-scripts` fragments and template migrations).

- [x] `H05-S04` Introduce frontend utility modules for repeated JS behavior.
  Deliverable: shared modules under `frontend/src/modules/shared/**`.
  Notes: Completed (`domBehaviors.js`, `publicationSubtypeSync.js`, module-backed template behavior).

- [x] `H05-S05` Add guardrails for template/asset composition drift.
  Deliverable: hardened `scripts/verify-template-assets.js`.
  Notes: Completed (CDN allowlist enforcement, inline-script transitional allowlist, canonical datatables path check).

- [x] `H05-S06` Add focused frontend behavior regression checks.
  Deliverable: expanded frontend-facing controller contract tests.
  Notes: Completed (`UserViewControllerContractTest`, `AdminViewControllerContractTest`).

- [x] `H05-S07` Close H05 with adoption notes and handoff to H06.
  Deliverable: H05 closeout note in H05 docs + task archive updates.
  Notes: Completed on 2026-03-03.

## H06 Data and Persistence Consistency Review

Archived from `TASKS.md` on 2026-03-03 after H06 closure.

- [x] `H06` Data and persistence consistency review.
  Goal: verify entity design, migrations/data files, transaction boundaries, and query patterns for inconsistencies.
  Deliverable: persistence risk report and remediation plan.
  Exit criteria: integrity risks and performance hotspots are tracked with clear fixes.
  Notes: Completed on 2026-03-03. H06 is now the persistence baseline for future remediation and H07 planning.

### H06 Subtasks

- [x] `H06-S01` Build persistence architecture map and entity ownership baseline.
  Deliverable: `docs/tasks/closed/h06-persistence-map.md`.
  Notes: Completed.

- [x] `H06-S02` Inventory schema and data-shape drift risks.
  Deliverable: `docs/tasks/closed/h06-schema-drift-inventory.md`.
  Notes: Completed.

- [x] `H06-S03` Review query patterns and consistency semantics.
  Deliverable: `docs/tasks/closed/h06-query-consistency-findings.md`.
  Notes: Completed.

- [x] `H06-S04` Define canonical persistence contracts.
  Deliverable: `docs/tasks/closed/h06-persistence-contracts.md`.
  Notes: Completed.

- [x] `H06-S05` Add focused persistence regression tests for highest risks.
  Deliverable: targeted repository/service characterization tests + minimal consistency fixes.
  Notes: Completed on 2026-03-03 (`PersistenceYearSupport`, CNFIS year-filter hardening, ranking ISSN cache alignment, guard tests).

- [x] `H06-S06` Define phased remediation plan and guardrails.
  Deliverable: `docs/tasks/closed/h06-remediation-plan.md` + lightweight persistence verification.
  Notes: Completed on 2026-03-03. Added `npm run verify-h06-persistence`.

- [x] `H06-S07` Close H06 with adoption notes and handoff to H07.
  Deliverable: H06 closeout note + archive updates.
  Notes: Completed on 2026-03-03. Handoff direction: keep `R1 -> R4` order (`R1` citation uniqueness index/migration first) when resuming persistence remediation.

## H07 Error Handling, Validation, and Security Hardening

Archived from `TASKS.md` on 2026-03-04 after H07 closure.

- [x] `H07` Error handling, validation, and security hardening.
  Goal: unify input validation, exception mapping, auth/authz checks, and security defaults.
  Deliverable: standardized error/validation/security checklist with implementation gaps.
  Exit criteria: critical endpoints and forms comply with one consistent policy.
  Notes: Completed on 2026-03-04. H07 is now the security/validation/error baseline for H08+ planning and remediation sequencing.

### H07 Subtasks

- [x] `H07-S01` Build endpoint and trust-boundary security map.
  Deliverable: `docs/tasks/closed/h07-security-surface-map.md`.
  Notes: Completed.

- [x] `H07-S02` Inventory validation and binding drift risks.
  Deliverable: `docs/tasks/closed/h07-validation-drift-inventory.md`.
  Notes: Completed.

- [x] `H07-S03` Inventory exception/error handling consistency gaps.
  Deliverable: `docs/tasks/closed/h07-error-handling-findings.md`.
  Notes: Completed.

- [x] `H07-S04` Define canonical H07 contracts and policies.
  Deliverable: `docs/tasks/closed/h07-security-validation-contracts.md`.
  Notes: Completed.

- [x] `H07-S05` Add focused regression guards for highest H07 risks.
  Deliverable: targeted characterization tests for auth/validation/error paths + error boundary tests.
  Notes: Completed on 2026-03-04 (mixed unauthorized semantics, parse/role exception baselines, upload baseline, mutating-GET baseline, access-denied redirect and error template mappings).

- [x] `H07-S06` Define phased remediation plan and lightweight enforcement.
  Deliverable: `docs/tasks/closed/h07-remediation-plan.md` + `npm run verify-h07-guardrails`.
  Notes: Completed on 2026-03-04 (`R1..R4` remediation sequence and debt-aware guardrails).

- [x] `H07-S07` Close H07 with adoption notes and handoff to H08.
  Deliverable: H07 closeout/adoption note + archive updates.
  Notes: Completed on 2026-03-04. H08 handoff: keep H07 contracts (`C1..C10`) as fixed inputs; preserve guardrail command until remediation slices are executed.

## H08 Observability and Operability Foundation

Archived from `TASKS.md` on 2026-03-04 after H08 closure.

- [x] `H08` Observability and operability foundation.
  Goal: make failures diagnosable with structured logs, metrics, and health/readiness signals.
  Deliverable: minimum observability baseline and runbook starter.
  Exit criteria: common production failure modes are detectable and actionable.
  Notes: Completed on 2026-03-04. H08 baseline is active via H08 maps/findings/contracts, observability guardrails, and `verify-h08-baseline` enforcement command.

### H08 Subtasks

- [x] `H08-S01` Build observability surface map and signal inventory.
  Deliverable: `docs/tasks/closed/h08-observability-map.md`.
  Notes: Completed.

- [x] `H08-S02` Inventory logging and diagnostics drift risks.
  Deliverable: `docs/tasks/closed/h08-logging-drift-inventory.md`.
  Notes: Completed.

- [x] `H08-S03` Inventory health/readiness/operability gaps.
  Deliverable: `docs/tasks/closed/h08-operability-findings.md`.
  Notes: Completed.

- [x] `H08-S04` Define canonical observability and operability contracts.
  Deliverable: `docs/tasks/closed/h08-observability-contracts.md`.
  Notes: Completed.

- [x] `H08-S05` Add focused observability regression guards.
  Deliverable: `docs/tasks/closed/h08-regression-guards.md` + `npm run verify-h08-observability-guardrails`.
  Notes: Completed on 2026-03-04.

- [x] `H08-S06` Define phased remediation plan and lightweight enforcement.
  Deliverable: `docs/tasks/closed/h08-remediation-plan.md` + `npm run verify-h08-baseline`.
  Notes: Completed on 2026-03-04.

- [x] `H08-S07` Close H08 with adoption notes and handoff to H09.
  Deliverable: H08 closeout note + archive updates.
  Notes: Completed on 2026-03-04. H09 handoff: promote `verify-h08-baseline` into CI-required gates and keep remediation slices ordered `P0 -> P1 -> P2`.

## TASKS.md Archive Snapshot (2026-03-04)

# Project Recovery Tasks (High-Level)

Objective: turn the current feature bundle into a maintainable, testable, and evolvable product.

Done history moved to `TASKS-done.md`.

## Backlog

- `H01` completed and archived in `TASKS-done.md`.

- [x] `H02` Architecture boundaries and ownership.
  Goal: define module boundaries, responsibilities, and allowed dependencies between layers.
  Deliverable: lightweight architecture map and dependency rules.
  Exit criteria: new code placement rules are documented and enforceable in review.
  Status: completed on 2026-03-03.
  Note: architecture baseline and enforcement are active via `docs/architecture.md`, `docs/doc-governance.md`, and `npm run verify-architecture-boundaries`.

- [x] `H03` Contract and behavior baseline.
  Goal: capture current expected behavior for key flows before refactors.
  Deliverable: minimal contract suite (controller/service integration + key UI/API flows).
  Exit criteria: high-impact flows have regression coverage and a known pass/fail baseline.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H03-S01..S07 completion details and adoption guidance.

- [x] `H04` Test strategy and pyramid rebalance.
  Goal: reduce fragile end-to-end reliance and improve unit/integration signal quality.
  Deliverable: test taxonomy, gap matrix, and priority test additions.
  Exit criteria: each critical feature has at least one stable automated regression test.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H04-S01..S07 completion details and adoption guidance.

- [x] `H05` Frontend structure and asset discipline.
  Goal: standardize JS/CSS/template patterns to avoid divergent implementations.
  Deliverable: frontend conventions (entrypoints, shared utilities, template composition patterns).
  Exit criteria: duplicated UI logic is centralized and new pages follow the same conventions.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H05-S01..S07 completion details and adoption guidance.

- [x] `H06` Data and persistence consistency review.
  Goal: verify entity design, migrations/data files, transaction boundaries, and query patterns for inconsistencies.
  Deliverable: persistence risk report and remediation plan.
  Exit criteria: integrity risks and performance hotspots are tracked with clear fixes.
  Status: completed on 2026-03-03.
  Note: archived in `TASKS-done.md` with H06-S01..S07 completion details, guardrails, and H07 handoff guidance.

- [x] `H07` Error handling, validation, and security hardening.
  Goal: unify input validation, exception mapping, auth/authz checks, and security defaults.
  Deliverable: standardized error/validation/security checklist with implementation gaps.
  Exit criteria: critical endpoints and forms comply with one consistent policy.
  Status: completed on 2026-03-04.
  Note: archived in `TASKS-done.md` with H07-S01..S07 completion details, regression guards, and H08 handoff guidance.

- [x] `H08` Observability and operability foundation.
  Goal: make failures diagnosable with structured logs, metrics, and health/readiness signals.
  Deliverable: minimum observability baseline and runbook starter.
  Exit criteria: common production failure modes are detectable and actionable.
  Status: completed on 2026-03-04.
  Note: archived in `TASKS-done.md` with H08-S01..S07 completion details, guardrails, and H09 handoff guidance.

- [x] `H09` Build, CI, and quality gates.
  Goal: ensure every change passes reproducible checks and prevents regressions from merging.
  Deliverable: CI pipeline definition with lint/test/build/security gates.
  Exit criteria: required checks are automated and block broken changes.
  Status: completed on 2026-03-04.
  Note: CI hardening is enforced via `.github/workflows/h09-quality-gates.yml` (`guardrails`, `java-smoke`, `quality-full`) and `.github/workflows/h09-security-gates.yml` (`dependency-review`, `codeql-analysis`), with local parity command `npm run verify-h09-baseline`.

- [x] `H10` Documentation and contribution workflow.
  Goal: align README/CONTRIBUTING with actual architecture, setup, and delivery flow.
  Deliverable: contributor playbook for local dev, testing, and release hygiene.
  Exit criteria: a new contributor can run, test, and modify the project without tribal knowledge.
  Status: completed on 2026-03-04.
  Note: completed via `H10-S01..S08` with the durable top-level docs set (`docs/quality-gates.md`, `docs/failure-triage.md`, `docs/release-hygiene.md`, `docs/doc-governance.md`) and walkthrough validation evidence in `docs/tasks/closed/h10-validation-walkthrough.md`.

### H10 Subtasks (Planned)

- [x] `H10-S01` Documentation inventory and gap map.
  Goal: map current docs (`README`, `CONTRIBUTING`, `docs/*`) against actual workflows and guardrails.
  Deliverable: `docs/tasks/closed/h10-doc-inventory.md` with outdated/missing sections and owners.
  Exit criteria: all contributor-critical gaps are identified and prioritized.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-doc-inventory.md` with source coverage matrix, owner mapping, and prioritized closure order for `H10-S02..S08`.

- [x] `H10-S02` Local setup and runbook alignment.
  Goal: make first-run setup deterministic for new contributors.
  Deliverable: updated `README.md` with prerequisites, local run, config overrides, and troubleshooting.
  Exit criteria: a new contributor can boot the app and run smoke checks without tribal knowledge.
  Status: completed on 2026-03-04.
  Note: `README.md` now includes a deterministic first-run quickstart, explicit config override options, health endpoint contract, and local troubleshooting baseline aligned with H09 parity checks.

- [x] `H10-S03` Contributor workflow playbook.
  Goal: define one clear change workflow from branch creation to PR merge.
  Deliverable: updated `CONTRIBUTING.md` (branching, commit conventions, required local checks, PR expectations).
  Exit criteria: workflow is explicit and consistent with enforced CI gates.
  Status: completed on 2026-03-04.
  Note: `CONTRIBUTING.md` now defines an end-to-end contributor workflow and change-type verification matrix aligned with enforced H09 CI checks.

- [x] `H10-S04` Quality gate command matrix.
  Goal: document when to run each verification command (`H03`-`H09` baselines and guardrails).
  Deliverable: `docs/tasks/closed/h10-quality-gates-matrix.md` (`change type -> required commands`).
  Exit criteria: contributors can select required checks by change scope.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-quality-gates-matrix.md` and linked it from `CONTRIBUTING.md` as the canonical change-type command selector.

- [x] `H10-S05` Failure triage and debugging guide.
  Goal: reduce time-to-fix for common guardrail/CI failures.
  Deliverable: troubleshooting sections for architecture, persistence, security, observability, and CI jobs.
  Exit criteria: each required CI check has a `failure -> likely cause -> fix path`.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-failure-triage.md` with guardrail/build/security CI triage matrix and linked it from `CONTRIBUTING.md`.

- [x] `H10-S06` Release hygiene baseline.
  Goal: define minimal release-safe merge hygiene.
  Deliverable: PR checklist + merge/release checklist (risk notes, rollback notes, evidence commands).
  Exit criteria: release-affecting changes follow a documented checklist.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-release-hygiene.md` with PR/merge/evidence/rollback baseline and linked it from `CONTRIBUTING.md`.

- [x] `H10-S07` Docs governance and ownership.
  Goal: prevent documentation drift after H10 completion.
  Deliverable: docs ownership table, update triggers, and review cadence policy.
  Exit criteria: each key doc has an owner and mandatory update triggers.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-doc-governance.md` with ownership matrix, mandatory update triggers, and review cadence; linked policy from `CONTRIBUTING.md`.

- [x] `H10-S08` Validation and closure.
  Goal: verify the documentation workflow works in practice.
  Deliverable: one walkthrough by a fresh-contributor path plus fixes, then H10 closeout note in `TASKS.md`.
  Exit criteria: all H10 docs are updated, cross-linked, and validated with current commands.
  Status: completed on 2026-03-04.
  Note: added `docs/tasks/closed/h10-validation-walkthrough.md` with executed command evidence (`npm run verify-h09-baseline`, `./gradlew bootRun -m`) and successful outcomes.

## How To Use This File

- Each `Hxx` item is intentionally high-level and should be investigated through subtasks in planning mode.
- Create subtasks only when starting work on one `Hxx`; keep this file stable as the top-level map.
- Move completed `Hxx` entries and their subtasks to `TASKS-done.md`.



`H01`-`H02` subtasks and closure details are archived in `TASKS-done.md`.

## Remediation Execution Backlog (Actionable)

Source set reviewed: `docs/tasks/closed/h02-remediation-plan.md`, `docs/tasks/closed/h06-remediation-plan.md`, `docs/tasks/closed/h07-remediation-plan.md`, `docs/tasks/closed/h08-remediation-plan.md` and linked findings/contracts inventories.

### P0 (High Priority)

- [x] `B01` H06-R1: Enforce citation pair uniqueness at DB level.
  Goal: close `Q-H06-02` with persistence-layer guarantees.
  Scope:
  - add compound unique index for citation (`citedId`, `citingId`);
  - implement one-time safe dedupe migration for existing duplicates;
  - keep app-level duplicate guard as defense in depth.
  Inputs: `docs/tasks/closed/h06-remediation-plan.md` (`R1`), `docs/tasks/closed/h06-query-consistency-findings.md`.
  Done criteria: duplicate citation writes are rejected by DB; migration is reproducible and documented.
  Status: completed on 2026-03-04.
  Note: added `CitationUniquenessMigrationService` + gated runner (`off|report|apply`) with keep-lowest-id dedupe and runtime unique index `uniq_cited_citing`; added unit + integration coverage.

- [x] `B02` H07-R1: Authorization scope and 401/403 semantics alignment.
  Goal: close `S-H07-01`, `E-H07-02`, `E-H07-04`.
  Scope:
  - explicitly scope privileged MVC/API routes;
  - enforce zone contract (MVC redirect-to-login, API 401 JSON; denied -> MVC 403 view/API 403 JSON).
  Inputs: `docs/tasks/closed/h07-remediation-plan.md` (`R1`), `docs/tasks/closed/h07-security-validation-contracts.md`.
  Done criteria: no privileged route depends only on `anyRequest().authenticated()`; behavior is consistent by zone.
  Status: completed on 2026-03-04.
  Note: added explicit `/admin/**`, `/api/admin/**`, `/api/export/**`, `/api/scrape/**` authority scoping and API-aware JSON `401/403` handlers; normalized `/user/**` unauthenticated flow to login redirect with filter-enabled security contract tests.

- [x] `B03` H08-P0: Logging hygiene and disclosure cleanup.
  Goal: close `L-H08-01`, `L-H08-04`, `L-H08-08`, `L-H08-05`, `O-H08-06`.
  Scope:
  - remove runtime `printStackTrace` and `System.out/System.err` in active paths;
  - fix logger owner drift (`ComputerScienceBookService`);
  - remove raw external payload logging in `ScopusService#parseToken`;
  - preserve endpoint behavior while improving diagnostics.
  Inputs: `docs/tasks/closed/h08-remediation-plan.md` (`P0`), `docs/tasks/closed/h08-logging-drift-inventory.md`.
  Done criteria: H08 allowlists shrink accordingly; failures are logged with structured context.
  Status: completed on 2026-03-04.
  Note: replaced active runtime `printStackTrace` and targeted `System.out/System.err` in transport/service/importing/reporting paths; fixed `ComputerScienceBookService` logger owner drift; removed raw payload print in `ScopusService#parseToken`; tightened `verify-h08-observability-guardrails` allowlists.

### P1 (Medium-High Priority)

- [x] `B04` H06-R2: Complete year-parsing safety rollout.
  Goal: close remaining `Q-H06-03` paths under contract `C3`.
  Scope:
  - replace remaining raw year parsing in high-impact report/export/search/grouping flows with `PersistenceYearSupport`;
  - finalize policy for `ActivityInstance#getYear`.
  Inputs: `docs/tasks/closed/h06-remediation-plan.md` (`R2`), `docs/tasks/closed/h06-persistence-contracts.md`.
  Done criteria: no raw `substring(0,4)` year filtering/grouping remains in targeted high-impact flows.
  Status: completed on 2026-03-04.
  Note: rolled out helper-based year parsing across scoring/grouping/export hotspots; added `PersistenceYearSupport.extractYearString(...)` and `ActivityInstance#getYearOptional()`; expanded `verify-h06-persistence` to enforce no raw year parsing regression on remediated files.

- [x] `B05` H06-R3: Identity/order/dedupe consistency.
  Goal: close `Q-H06-04`, `Q-H06-06`, `Q-H06-07`.
  Scope:
  - normalize `id`/`eid`/`doi` lookup usage per contract;
  - enforce deterministic sorting for user-visible lists/exports;
  - remove author-aggregation duplicate amplification.
  Inputs: `docs/tasks/closed/h06-remediation-plan.md` (`R3`), `docs/tasks/closed/h06-query-consistency-findings.md`.
  Done criteria: stable ordering and deduped outputs are covered by tests.
  Status: completed on 2026-03-04.
  Note: user publication aggregation now dedupes by publication ID; deterministic publication/citation ordering contract applied across user/admin/group hotspots; user edit/save flow naming normalized to canonical DB `id`; `verify-h06-persistence` extended with `R3` guard checks.

- [x] `B06` H07-R2: Validation boundary hardening.
  Goal: close `V-H07-01`, `V-H07-02`, `V-H07-03`, `V-H07-06`.
  Scope:
  - DTO + `@Valid` rollout for top-risk write and import endpoints;
  - safe/bounded parsing for `start/end` and role conversion;
  - deterministic 4xx behavior for malformed input.
  Inputs: `docs/tasks/closed/h07-remediation-plan.md` (`R2`), `docs/tasks/closed/h07-validation-drift-inventory.md`.
  Done criteria: boundary validation enforced on targeted endpoints; invalid input no longer escapes as 5xx.
  Status: completed on 2026-03-04.
  Note: migrated `/api/admin/users` + `/api/admin/researchers` create/update to DTO + `@Valid`; replaced CNFIS start/end `Integer.parseInt` with bounded year-range validation returning `400`; added role allowlist validation in `/admin/users/create` with redirect+flash fallback; updated H07 guardrails and regression tests.

- [x] `B07` H07-R3: Centralized exception mapping and transport logging cleanup.
  Goal: close `E-H07-01`, `E-H07-03`, `E-H07-05`, `E-H07-06`, `E-H07-07`.
  Scope:
  - introduce `@ControllerAdvice` mappings for common failure classes;
  - remove catch-and-print/swallowed exceptions on transport paths;
  - align API/MVC error envelopes/views.
  Inputs: `docs/tasks/closed/h07-remediation-plan.md` (`R3`), `docs/tasks/closed/h07-error-handling-findings.md`.
  Done criteria: consistent mapped error behavior with structured diagnostics.
  Status: completed on 2026-03-04.
  Note: added split centralized exception mapping (`ApiExceptionHandler` + `MvcExceptionHandler`), switched `UserService.updateUser` to `Optional` with deterministic `404` in controller, tightened `/api/export` to deterministic failure behavior, and extended `verify-h07-guardrails` to block generic export swallow-catch regressions.

- [x] `B07A` H07 login flow practical standards alignment.
  Goal: align login flow with modern browser/password-manager and explicit form-login contracts.
  Scope:
  - login template semantic/autocomplete metadata;
  - explicit Spring form-login + logout endpoints/redirects;
  - security regression tests for login success/failure/logout;
  - H07 guardrail checks for login input naming/autocomplete contract.
  Inputs: login baseline plan (practical scope), `docs/tasks/closed/h07-security-validation-contracts.md`.
  Done criteria: deterministic login/logout contract + test/guardrail coverage.
  Status: completed on 2026-03-04.
  Note: `/login` GET/POST contract is explicit; invalid credentials redirect to `/login?error`, logout redirects to `/login?logout`; login template now uses `name=\"username\"/\"password\"` with `autocomplete=\"username\"/\"current-password\"`; guardrails enforce these attributes.

- [x] `B08` H08-P1: Correlation context propagation.
  Goal: close `L-H08-02`, `L-H08-06`, `L-H08-07`, `O-H08-07`.
  Scope:
  - add request correlation IDs for HTTP flows;
  - standardize scheduler context (`jobType`, `taskId`, phase);
  - ensure error logs include correlation context and align with H07 mappings.
  Inputs: `docs/tasks/closed/h08-remediation-plan.md` (`P1`), `docs/tasks/closed/h08-observability-contracts.md`.
  Done criteria: request/job traces are diagnosable end-to-end.
  Status: completed on 2026-03-04.
  Note: implemented `X-Request-Id` adopt-and-propagate filter + request MDC (`requestId`, `route`, `userId`); added Scopus scheduler context helper and phase-aware MDC (`jobType`, `taskId`, `phase`) for batch/per-task logs; centralized exception handlers now include request correlation context; `verify-h08-observability-guardrails` extended with B08 checks.

- [x] `B09` H09 bootstrap: Promote local guardrails to required CI checks.
  Goal: operationalize H02/H06/H07/H08 enforcement in pipeline.
  Scope:
  - include `verify-architecture-boundaries`, `verify-h06-persistence`, `verify-h07-guardrails`, `verify-h08-baseline` as required CI checks;
  - document policy for tightening/allowlist shrink.
  Inputs: `docs/tasks/closed/h08-remediation-plan.md` (H09 handoff), remediation guardrail docs.
  Done criteria: CI blocks merges on guardrail failure.
  Status: completed on 2026-03-04.
  Note: added GitHub Actions workflow `.github/workflows/h09-quality-gates.yml` with `guardrails` and `java-smoke` jobs plus failure artifact upload; documented Stage 1 soft rollout and Stage 2 required-check transition in `docs/tasks/closed/h09-ci-gates.md`; included H08 baseline handoff confirmation.

### P2 (Planned / Structural)

- [x] `B10` H06-R4: Persistence consistency cleanup and namespace hygiene.
  Goal: close `Q-H06-05`, `Q-H06-08`, `Q-H06-09`, `D-H06-03`.
  Scope:
  - text-search normalization policy rollout;
  - retire typo’d repo API (`findAllByeIssn`) via compatibility step;
  - forum export dedupe normalization (remove sentinel checks);
  - plan and execute collection naming migration (`schodardex` -> `scholardex`).
  Inputs: `docs/tasks/closed/h06-remediation-plan.md` (`R4`), `docs/tasks/closed/h06-schema-drift-inventory.md`.
  Done criteria: API naming and data-shape drift items have closed implementation path.
  Status: completed on 2026-03-04.
  Note: delivered case-insensitive admin title search normalization, forum export dedupe normalization (`issn -> eIssn -> sourceId`), and single-step task namespace cutover to `scholardex.tasks.*` with startup-gated migration runner (`off|report|apply`) and integration coverage.

- [x] `B10A` H06-R4 follow-up: remove `findAllByeIssn` compatibility alias.
  Goal: complete typo-method retirement after stabilization window.
  Scope:
  - remove deprecated `findAllByeIssn` from `RankingRepository`;
  - tighten `verify-h06-persistence` to zero allowlist for typo method.
  Inputs: `B10` compatibility bridge completion evidence.
  Done criteria: no `findAllByeIssn` references remain in codebase.
  Status: completed on 2026-03-04.
  Note: deprecated alias removed from `RankingRepository`; compatibility test scaffolding removed; `verify-h06-persistence` now enforces zero-allowlist for typo method usage.

- [x] `B11` H07-R4: CSRF, mutating-GET migration, and upload hardening.
  Goal: close `C3`, `C4`, `V-H07-04`.
  Scope:
  - re-enable CSRF for browser form flows with explicit exemptions only when justified;
  - migrate `delete/duplicate` mutating GET routes to safe verbs;
  - enforce upload size/type/schema validation in group import.
  Inputs: `docs/tasks/closed/h07-remediation-plan.md` (`R4`), `docs/tasks/closed/h07-security-validation-contracts.md`.
  Done criteria: browser mutation routes are CSRF-protected and non-GET; upload policy enforced.
  Status: completed on 2026-03-04.
  Note: CSRF is re-enabled for MVC flows with explicit `/api/**` exemption; mutating `delete/duplicate` GET routes were migrated to POST across targeted controllers/templates; group CSV import now enforces strict size/type/schema validation.

- [x] `B12` H08-P2: Actuator/metrics/readiness baseline implementation.
  Goal: close `O-H08-01`, `O-H08-02`, `O-H08-03`, `O-H08-04`, `O-H08-05`.
  Scope:
  - add actuator and explicit readiness/liveness policy;
  - add minimum metrics coverage for startup/scheduler/export/external dependency calls;
  - add async executor saturation/queue diagnostics;
  - define startup phase readiness semantics.
  Inputs: `docs/tasks/closed/h08-remediation-plan.md` (`P2`), `docs/tasks/closed/h08-operability-findings.md`, `docs/tasks/closed/h08-observability-contracts.md`.
  Done criteria: production failure modes are machine-detectable via health and metrics endpoints.
  Status: completed on 2026-03-04.
  Note: actuator baseline and readiness/liveness groups are active, startup/external dependency health contributors are wired, scheduler/export/startup/external metrics are instrumented, async queue/rejection diagnostics are exposed, and H08 observability guardrails now assert P2 baseline wiring.

- [x] `B13` H02 residual V01 closure outside baseline pair.
  Goal: reduce remaining `Z1 -> Z4` controller repository debt in non-baseline controllers.
  Scope:
  - prioritize `AdminViewController` and smaller controllers still directly importing repositories;
  - migrate residual orchestration to Z2 facades while preserving behavior.
  Inputs: `docs/tasks/closed/h02-remediation-plan.md` (`R1 residual`), `docs/tasks/closed/h02-violations.md`.
  Done criteria: repository-import allowlist in `verify-architecture-boundaries` is materially reduced.
  Status: completed on 2026-03-04.
  Note: residual controllers were migrated to Z2 facades (`AdminCatalogFacade`, `UserRankingFacade`, `ActivityManagementFacade`, `GroupReportsManagementFacade`, `IndividualReportsManagementFacade`, `UrapRankingFacade`, `UserActivityInstanceFacade`, `PublicationWizardFacade`); controller/view repository imports are now zero and architecture allowlist is empty.


- [x] `H18` WoS ranking enrichment (computed fallback data + admin control page).
  Goal: enrich WoS ranking records with computed values for fields missing in import files, without overriding values explicitly provided by source files.
  Deliverable: enrichment flow that computes `rank`, `quartile`, and `quartileRank` per `category + edition`, plus an admin page to run/inspect enrichment.
  Exit criteria: for each `category + edition`, source-provided values are preserved; missing values are deterministically computed; admins can run and validate enrichment from a dedicated page.
  Status: archived from `TASKS.md` on 2026-03-13 after closing remaining subtasks based on existing implementation and regression coverage.
  Subtasks:
  - [x] `H18.1` Define enrichment computation contract.
    Deliverable: documented deterministic rules for `rank`, `quartile`, and `quartileRank` at `category + edition` scope, including tie handling and null/insufficient-data behavior.
    Exit criteria: rules are unambiguous and implementation-ready.
    Status: completed on 2026-03-08.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h18.1-wos-ranking-enrichment-contract.md`.
    - Canonical linkage amendment: `docs/tasks/closed/h17-scopus-canonical-contract.md` (H18.1 section).
    - Locked decisions: competition rank ties (`1,1,3`), position-bucket quartiles, source `quarter` precedence, missing metric value -> skip (non-conflict).
  - [x] `H18.2` Integrate enrichment into WoS ingestion/projection flow.
    Deliverable: service-level enrichment step that preserves source values and computes only missing fields.
    Exit criteria: persistence reflects "source if present, computed otherwise" for all three fields.
    Status: completed on 2026-03-08.
    Handover:
    - Canonical enrichment implementation: `WosFactBuilderService#enrichMissingCategoryRankingFields` computes missing `rank`, `quarter`, `quartileRank` while preserving source-provided fields.
    - Initialization order now includes explicit enrichment step before projections (`/admin/initialization/wos/enrichCategoryRankings`).
    - Big-bang flow executes enrichment between `build-facts` and `build-projections`.
  - [x] `H18.3` Add admin backend endpoints for enrichment operations.
    Deliverable: secured admin endpoints to trigger enrichment and retrieve summary results (processed, computed, preserved, failed).
    Exit criteria: authorized admins can execute enrichment and get deterministic run summaries.
    Status: completed on 2026-03-08.
    Handover:
    - New admin JSON endpoints: `POST /admin/initialization/wos/enrichment/run` and `GET /admin/initialization/wos/enrichment/summary`.
    - Deterministic summary DTO: `stepName`, `executed`, `startedAt`, `completedAt`, `processed`, `computed`, `preserved`, `failed`, `skipped`, `note`.
    - Locked mapping used in backend reporting: `computed=updated`, `failed=errors`, `preserved=processed-computed-failed`.
  - [x] `H18.4` Build dedicated admin page for WoS enrichment.
    Deliverable: admin UI page to start enrichment runs and review per-run outcome metrics.
    Exit criteria: page is accessible to admins only and supports operational verification.
    Status: completed on 2026-03-08.
    Handover:
    - Dedicated page endpoint: `GET /admin/initialization/wos/enrichment` with run action `POST /admin/initialization/wos/enrichment/runPage`.
    - Page shows latest deterministic enrichment metrics (`processed`, `computed`, `preserved`, `failed`, `skipped`) and links to JSON summary endpoint.
    - Initialization step 3 now exposes direct navigation to the dedicated enrichment page (`Open page`).
  - [x] `H18.5` Backfill historical WoS records.
    Deliverable: backfill-capable execution path for existing WoS category facts using the same enrichment contract as normal pipeline runs.
    Exit criteria: historical records are enriched according to the same contract, with idempotent rerun behavior.
    Status: completed on 2026-03-13.
    Handover:
    - Existing-data backfill is handled by `WosFactBuilderService#enrichMissingCategoryRankingFields`, which scans all persisted `WosCategoryFact` rows and updates only records still missing `rank`, `quarter`, or `quartileRank`.
    - The backfill-capable enrichment step is available both as a standalone admin action (`/admin/initialization/wos/enrichCategoryRankings`, `/admin/initialization/wos/enrichment/run`, `/admin/initialization/wos/enrichment/runPage`) and inside the WoS big-bang sequence between `build-facts` and `build-projections`.
    - Reruns are operationally idempotent because records with fully populated source/computed values are skipped and preserved by the same field-preservation rules.
  - [x] `H18.6` Add regression and integration test coverage.
    Deliverable: tests for preservation logic, computation correctness, and admin trigger flow.
    Exit criteria: automated tests cover success paths and key failure/edge cases.
    Status: completed on 2026-03-13.
    Handover:
    - Regression coverage for computation/preservation lives in `WosFactBuilderServiceTest` (computed rank/quarter/quartileRank, source-quarter preservation, missing-metric skip behavior, and category tuple handling).
    - Admin trigger coverage lives in `AdminInitializationControllerContractTest`, `AdminInitializationSecurityContractTest`, and `RankingMaintenanceFacadeTest` (redirect/API/page flow, authorization, and deterministic summary mapping).
    - Integration tests were intentionally not added in this closeout; the task is considered satisfied by the existing regression and controller/facade coverage until a broader workflow-level test slice is prioritized.


- [x] `H17` Scopus canonical import pipeline transition.
  Goal: replace direct Scopus document writes with a canonical ingestion pipeline aligned to WoS patterns (`events -> facts -> views`) while converging runtime publication reads to a derived `scholardex.publication` projection that merges Scopus, WoS, and Google Scholar enrichments.
  Deliverable: high-level migration to Scopus import events ledger, normalized Scopus facts layer, explicit cross-source field ownership contract, and merged projection views consumed by application/reporting flows.
  Exit criteria: Scopus ingest is replayable/idempotent from source events, source-specific facts remain authoritative, merged publication views are deterministic and lineage-backed, and guardrail checks protect against regressions and ownership drift.
  Assumption lock (2026-03-06): big-bang cutover for all Scopus entities; no historical data migration/backfill is required (clean-state bootstrap only).
  Amendment note (2026-03-06): H17.1 contract is extended to include cross-source ownership boundaries and derived merged-publication projection constraints (`scholardex.publication*`) without reopening H17.1 status.
  Amendment note (2026-03-07): WoS canonical fact semantics are split: journal score facts in `WosMetricFact` (`journalId + year + metricType`) and category ranking facts in `wos.category_facts` (`journalId + year + metricType + categoryNameCanonical + editionNormalized`); projections/read paths join score + ranking facts.
  Amendment note (2026-03-07): WoS category ranking facts now carry both `quarter + quartileRank` and `rank` where `rank` is category+edition rank (official JSON), while government data may provide only quarter.
  Amendment note (2026-03-07): WoS detail projection/read UX now includes `alternativeNames` + `alternativeIssns` and uses lightweight chart rendering for details visualizations.
  Subtasks:
  - [x] `H17.1` Canonical Scopus contract lock.
    Deliverable: `docs/tasks/closed/h17-scopus-canonical-contract.md` with canonical collections, required fields, identity keys, lineage fields, and source-policy rules for publications, citations, forums, authors, affiliations, and funding.
    Exit criteria: schema, identity, and source policy are decision-locked before implementation changes.
  - [x] `H17.2` Canonical storage and index baseline.
    Deliverable: canonical Mongo collection/index definitions for Scopus import events, normalized facts, Scopus read views, and merged `scholardex.publication*` projection/index prerequisites (lookup/sort/reporting keys) with idempotence-oriented unique constraints.
    Exit criteria: fresh environment creates canonical and merged-projection storage deterministically with required uniqueness/index coverage.
  - [x] `H17.3` Event ledger ingestion pipeline.
    Deliverable: ingestion paths write immutable Scopus import events (no direct entity writes) with deterministic metadata (`source`, `ingestedAt`, `batchId`, `correlationId`, `payloadHash`).
    Exit criteria: all Scopus import entrypoints produce events only.
  - [x] `H17.4` Deterministic fact builders (all entities).
    Deliverable: replayable transformation flow from events into normalized facts for publications, citations, forums, authors, affiliations, and funding with field-ownership safeguards that prevent Scopus builders from clobbering non-Scopus enrichments.
    Exit criteria: replaying identical event input yields identical Scopus fact state (idempotent/upsert-safe) and ownership boundaries are preserved.
  - [x] `H17.5` Projection views and query contracts.
    Deliverable: deterministic projection builders materialize `scopus.forum_search_view`, `scopus.author_search_view`, `scopus.affiliation_search_view`, and enriched `scholardex.publication_view`; runtime admin/API/reporting/scoring reads use projection-backed contracts with merged-publication lookup compatibility (`id` primary, plus `eid`/`wosId`/`googleScholarId`).
    Exit criteria: read flows are projection-backed, publication identity resolution normalizes to projection `id`, and WoS/Scholar enrichment persistence is projection-owned without Scopus field clobbering.
  - [x] `H17.6` Big-bang read/write cutover and legacy retirement.
    Deliverable: switch active Scopus write flows to canonical ingestion and publication-facing read flows to merged `scholardex.publication` projection; remove/disable legacy direct-write and direct-read Scopus document paths in runtime facades; centralize WoS/Scopus big-bang operations on dedicated admin initialization UI (`/admin/initialization`) with deterministic step actions and full-run orchestration.
    Exit criteria: no active runtime path writes legacy Scopus documents directly, publication reads no longer depend on legacy direct Scopus documents, and big-bang maintenance is executed from the dedicated initialization page (rankings page no longer exposes maintenance controls).
  - [x] `H17.7` Scheduler and task flow canonicalization.
    Deliverable: `ScopusPublicationUpdate` and `ScopusCitationsUpdate` execution publishes canonical events and triggers canonical transform/projection flow.
    Exit criteria: scheduled/manual Scopus updates are fully canonical and replay-safe.
  - [x] `H17.8` Guardrails and regression gates.
    Deliverable: guardrail checks that fail on legacy direct-write Scopus persistence and enforce canonical pipeline usage in CI.
    Exit criteria: CI blocks reintroduction of non-canonical Scopus persistence patterns.
  - [x] `H17.9` Validation and closeout evidence.
    Deliverable: run log + closeout notes capturing `./gradlew compileJava`, targeted Scopus tests, `./gradlew check`, and replay/idempotence verification evidence.
    Exit criteria: local and CI critical gates are green with canonical Scopus pipeline active.
  - [x] `H17.10` Cross-source merge policy and linker rules.
    Deliverable: production linker/merge implementation for `scholardex.publication_view` with exact-key resolution precedence (`id` -> `eid` -> `doiNormalized`), conflict quarantine persistence, NON-WOS exclusion, and migrated WoS enrichment call-sites (`UserReportFacade`, `GroupCnfisExportFacade`) that write through linker-owned lineage fields only.
    Exit criteria: enrichment writes are deterministic, ownership-safe, replay-safe, conflict-aware (quarantine/non-mutating), and no reporting/export flow bypasses linker service for WoS/Scholar-owned keys.

## H19 Multi-source Scholardex Identity and Ingestion Architecture

Archived from `TASKS.md` on 2026-03-11 after top-level closure and backlog cleanup.

- [x] `H19` Multi-source Scholardex identity and ingestion architecture.
  Goal: make Scholardex the canonical identity and link graph layer across publications, authors, forums, and affiliations, supporting four sources (`SCOPUS`, `WOS`, `GSCHOLAR`, `USER_DEFINED`) with deterministic lineage, linking, and runtime reads optimized for indicator computation.
  Deliverable: unified canonical contracts + storage models + ingestion/linking pipelines + immediate runtime cutover so all operational reads/writes resolve through Scholardex entities and canonical relationship edges, not source-specific silo models.
  Exit criteria: publication/author/forum/affiliation identity is source-agnostic and deterministic; WoS-first onboarding is complete; Scholar (Publish or Perish) and user-defined imports are supported; runtime paths are cut over to Scholardex; source-specific legacy identity paths are removed from runtime; citations are canonical-ID based across all sources; all entity conflict types are captured in generic conflict storage; source-to-canonical mapping is queryable and replay-stable; canonical publication-author linkage is queryable and deterministic; canonical author-affiliation linkage is queryable and deterministic; affiliation-side traversal for scoring/reporting is fast-path capable.
  Execution order override (locked): for remaining H19 implementation, complete citation migration first (`H19.9`) before finalizing Scopus runtime flow/data initialization and before closing runtime cutover (`H19.7`).
  Subtasks:
  - [x] `H19.1` Define canonical multi-source identity and ownership contract.
    Deliverable: locked contract for Scholardex entities (`publication`, `author`, `forum`, `affiliation`, `citation`) with per-source IDs, provenance/lineage fields, conflict rules, source-link mapping rules, and replay/idempotence semantics.
    Exit criteria: one contract document is implementation-ready and explicitly defines source ownership boundaries for Scopus/WoS/Scholar/User-defined.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h19.1-multisource-identity-contract.md`.
  - [x] `H19.2` Define canonical keying and merge policy for journal/forum identity.
    Deliverable: deterministic forum identity policy that links WoS journal identity and Scopus forum identity into Scholardex forum records, with normalization and collision handling rules.
    Exit criteria: deterministic link keys and conflict quarantine behavior are documented and testable.
    Handover:
    - Contract source of truth: `docs/tasks/closed/h19.2-forum-keying-merge-contract.md`.
  - [x] `H19.3` Implement Scholardex publication identity model v2.
    Deliverable: publication model supporting source IDs (`eid`, `wosId`, `googleScholarId`, `userSourceId`) plus canonical `scholardexPublicationId` and lineage metadata, with canonical `authorIds` aligned to relationship-edge contracts.
    Exit criteria: all publication ingest/build paths can persist and resolve the new identity model without ambiguity, and publication author linkage is consistent with canonical authorship edges.
  - [x] `H19.4` Implement Scholardex author identity model v2 (researcher-linked).
    Deliverable: author model that supports multiple source author IDs (Scopus/WoS/Scholar/User) as source-identity canonical facts, with canonical `affiliationIds` aligned to relationship-edge contracts, researcher linkage maintained on the researcher side via `primaryScholardexAuthorId`, and deterministic merge rules.
    Exit criteria: author linking and lookup are source-agnostic and deterministic for scoring/reporting entrypoints, and author-affiliation linkage is consistent with canonical author-affiliation edges.
  - [x] `H19.5` Implement Scholardex affiliation identity model v2.
    Deliverable: affiliation model that supports multiple source affiliation IDs and alias resolution across Scopus/WoS/Scholar/User, with reverse-link query support via canonical edge/index contracts (no forum-style reverse arrays required).
    Exit criteria: affiliation linking resolves deterministically, deduplicates source aliases, and supports fast affiliation-side traversal for scoring/reporting entrypoints.
  - [x] `H19.6` Build WoS-first onboarding into Scholardex entities.
    Deliverable: WoS ingestion/linking pipeline that populates/links Scholardex publication/forum/author/affiliation identities using existing WoS canonical facts/views.
    Exit criteria: WoS-only journals/publications not present in Scopus are represented and queryable in Scholardex runtime reads.
  - [x] `H19.9` Canonical citation model and migration from EID-only citation path.
    Deliverable: `scholardex.citation_facts` design and implementation keyed by canonical publication IDs, with migration/cutover from source/EID-bound citation reads.
    Exit criteria: WoS-only and Scholar-only publications participate in citation edges without EID dependency.
    Status: completed (canonical citation facts + runtime citation read cutover).
  - [x] `H19.7` Immediate runtime cutover to Scholardex read/write paths.
    Deliverable: all runtime read/write entrypoints (user/admin/report/export/scoring lookups) use Scholardex canonical paths directly; source-silo runtime identity paths are removed.
    Exit criteria: no runtime dependency remains on legacy source-specific identity stores for publication/author/forum/affiliation/citation resolution; citation runtime paths resolve via canonical citation facts.
    Status: implementation largely complete for publication/author/forum/affiliation/citation; remaining closeout is decommission/validation hardening.
  - [x] `H19.10` Generic identity conflict model + admin operations.
    Deliverable: `scholardex.identity_conflicts` contract and implementation covering publication/forum/author/affiliation ambiguity, plus operational listing/resolve/clear flows.
    Exit criteria: ambiguous merges across all canonical entity types are captured and manageable through one generic conflict surface.
  - [x] `H19.11` Source-link ledger + replay/traceability integration.
    Deliverable: `scholardex.source_links` contract and implementation mapping `(entityType, source, sourceRecordId)` to canonical entity IDs with deterministic state transitions.
    Exit criteria: traceability/replay workflows can resolve source record to canonical entity deterministically in one query path.
  - [x] `H19.12` Canonical relationship-edge model for indicator runtime.
    Deliverable: authoritative `scholardex.authorship_facts` (`publication -> author`) and `scholardex.author_affiliation_facts` (`author -> affiliation`) with deterministic ids, lineage, idempotence, and conflict policy.
    Exit criteria: canonical edge writes/replays are deterministic, conflict-safe, and consistent with `publication_facts.authorIds` and `author_facts.affiliationIds`.
  - [x] `H19.13` Indicator/report query cutover to edge-backed traversals.
    Deliverable: scoring/report/export/user/admin query paths use canonical edge-backed traversals for publication-by-author and author-by-affiliation access, with performance parity/guardrail checks.
    Exit criteria: runtime indicator computation no longer depends on source-silo author/affiliation linkage paths and passes parity/performance gates.
  - [x] `H19.8` End-to-end validation, parity, and operability gates.
    Deliverable: workflow and integration tests covering implemented sources (`SCOPUS`, `WOS`, current manual/user wizard `USER_DEFINED` path), identity-link conflicts, replay/idempotence, and cutover regressions; observability metrics and failure triage hooks.
    Exit criteria: CI gates catch identity/linking regressions and operational dashboards expose source-level ingest/link health for implemented sources.
    Handover:
    - Validation/operability contract: `docs/tasks/closed/h19.8-validation-operability-gates.md`.

## H37.5 Period Comparison

Archived from `TASKS.md` on 2026-04-16.

- [x] `H37.5` **Period comparison.**
  Completed: 2026-04-16.
  Handover:
  - "Compare with…" button in the evaluation toolbar opens a run-picker dropdown (hidden by default). Picker lists prior runs with formatted timestamp and source label seeded from `window.evalPriorRuns` (Thymeleaf inline JS).
  - `GET /user/evaluation/compare?runA={prior}&runB={current}` returns per-indicator and per-criterion score maps; client JS computes deltas as `current − prior`.
  - Aggregate panel gains a hidden "Score Δ" cell (`#eval-compare-delta-cell`) that appears when a comparison is active.
  - Each criterion card has a `.eval-criterion-delta` block span; each indicator row has a `data-indicator-id` `.eval-indicator-delta` inline span; the indicator detail panel also injects a delta badge next to the total.
  - Colour coding via `.eval-delta--positive` (green), `.eval-delta--negative` (red), `.eval-delta--neutral` (muted).
  - Comparison state persisted in URL via `history.replaceState(?compare={runId})`; page reload restores it automatically.
  - "Clear comparison" button hides the picker, removes all delta spans, strips the URL param, and hides the compare banner.
  - A compare banner (`#eval-compare-banner`, `aria-live="polite"`) is shown between the toolbar and aggregate panel while a comparison is active.
  - `RunSummary.createdAt` changed from `Instant` to `String` to fix a Thymeleaf/Jackson JSR310 serialisation error in inline JS.
  - CSS `.app-eval-compare-picker[hidden] { display: none }` explicit rule prevents `display: flex` overriding the `hidden` attribute on the picker.
  - All evaluation-page select elements given `padding-top/bottom: 0.2rem; height: auto` to correct disproportionate height at the 0.82rem context font-size.

## H37.8 Saved Report Snapshots

Archived from `TASKS.md` on 2026-04-17.

- [x] `H37.8` **Saved report snapshots.**
  Completed: 2026-04-17.
  Handover:
  - `EvaluationSnapshot` MongoDB document (`evaluationSnapshots` collection); `EvaluationSnapshotRepository` with find-by-user/report, count, and find-by-id-and-user queries.
  - Four CRUD endpoints in `EvaluationWorkspaceController`: create (50-cap, 422 on overflow), list, detail, delete.
  - `GET /user/evaluation/compare-snapshot?snapshotId=&runId=` endpoint diffs snapshot scores against a current run, returns standard `ComparisonResponse` with `runA.status="SNAPSHOT"` and `runA.name`.
  - `RunSummary` record gained a nullable `name` field; all existing call sites pass `null`.
  - Toolbar gains "Save Snapshot" (browser prompt → POST) and "My Snapshots" (toggle panel) buttons, plus an inline feedback span.
  - "My Snapshots" collapsible panel shows name, date, score per snapshot, with Compare and delete-with-confirmation actions.
  - Compare picker gains an async-populated `<optgroup>` for saved snapshots; `fetchAndApplyComparison` routes to the correct endpoint based on `snap:` prefix.
  - Compare banner updated to show `snapshot "{name}" ({date})` vs. `run from {date}`.
  - Deleting the active comparison snapshot clears deltas and URL param automatically.
