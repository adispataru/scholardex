# Operational Playbook

Status: active operational recovery and maintenance baseline.

## Primary Triage Surfaces

- `/admin/conflicts`
- `/admin/source-links`
- `/admin/user-defined-triage`
- `/admin/incremental-updates`
- `/admin/initialization`
- actuator metrics and canonical build/reconcile logs

USER_DEFINED deep-link filters:
- `/admin/source-links?source=USER_DEFINED`
- `/admin/conflicts?incomingSource=USER_DEFINED`
- `/admin/initialization` -> `USER_DEFINED Initialization` section (`/admin/initialization/user-defined/*`)

Incremental upload split:
- `/admin/incremental-updates` is for one-file WoS or Scopus upload-driven maintenance only.
- Use `/admin/initialization` for broader rebuild, reconcile, recovery, or downstream follow-up after an incremental upload.
- Current upload scope:
  - WoS upload runs ingest plus fact build only.
  - Scopus upload runs ingest plus fact and canonical materialization only.

## Scopus Incremental Maintenance

Normal entrypoints:
- Use `/admin/incremental-updates` for one uploaded Scopus file plus batch follow-up actions tied to that uploaded batch.
- Use `/admin/initialization` for full rebuild, recovery, global maintenance, or any situation where state is uncertain beyond one batch.
- Do not use full initialization as the default retry path for a single uploaded Scopus file or a single scheduler task.
- **Building the whole dataset from scratch** is a single self-contained call (`POST /admin/initialization/rebuildAllDerived?confirmation=RESET`) that imports every feed from config and ingests both sources — do **not** pre-import feeds by hand. See [rebuild-runbook.md](rebuild-runbook.md) for the full procedure, config-key table, and post-rebuild admin/DBLP/OpenAlex steps.

Scopus replay/update invariants:
- Repeating the same Scopus upload or scheduler batch must reuse existing canonical facts, source links, edges, and OPEN conflicts rather than inserting duplicates.
- Unchanged Scopus facts still belong to the current `sourceBatchId`; batch-scoped canonicalization/projection must see the full uploaded payload, not only materially changed rows.
- Batch-scoped Scopus canonicalization must run with checkpoint resume disabled.
- Scheduler-driven Scopus publication/citation updates should run batch-scoped canonicalization and batch-scoped projection rebuilds, not full-corpus rescans.
- Incremental Scopus projection maintenance must refresh only the reconstructible batch graph scope and must not remove still-valid cross-batch citations, authorships, or author-affiliation edges.

Scopus maintenance modes:
- Upload-driven Scopus path: ingest -> fact build -> canonical materialization -> optional batch projection rebuild / edge reconcile / source-link repair follow-up.
- Scheduler publication/citation path: ingest/import events -> batch-scoped canonical materialization -> batch-scoped projection refresh.
- Full initialization path: explicit full-corpus Scopus maintenance with full rebuild semantics.

## Standard Recovery Sequence

1. Re-run the relevant canonical build or initialization step.
2. Reconcile source links if identity/linkage drift is involved.
3. Reconcile derived edges if traversal/link edges are affected.
4. Rebuild projections or reporting read models.
5. Re-run the targeted validation/guardrail baseline for the affected area.

For USER_DEFINED onboarding incidents, prefer:

1. Open `/admin/user-defined-triage` and confirm source-fact/link/conflict counts.
2. Run `/admin/initialization/user-defined/buildFacts` when source fact counts lag import events.
3. Run `/admin/initialization/user-defined/canonicalize` (optionally with source-link/edge reconcile).
4. Run `/admin/initialization/user-defined/runAll` for full maintenance when state is uncertain.
5. Validate recovery via filtered source-link/conflict pages and USER_DEFINED metrics.

## Operational Expectations

- Recovery steps must be replay-safe and deterministic.
- Rebuilds must not duplicate canonical facts, links, or projections.
- Repeated Scopus upload/scheduler runs must not require manual cleanup of duplicate-key rows in canonical Mongo collections.
- Postgres read cutovers must fail loudly if required read ports or projection state are unavailable.

## Documentation Rule

- Keep user/operator-facing playbook guidance here.
- Keep task-closeout evidence and slice-specific rollback notes under `docs/tasks/closed/`.
