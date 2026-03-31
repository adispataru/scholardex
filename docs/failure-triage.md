# Failure Triage

Status: active failure-triage baseline.

## When Guardrails Fail

Inspect in this order:

1. The failing script/test output
2. Related route, contract, or asset guardrails
3. Admin conflict/source-link surfaces when the failure involves canonicalization
4. Application logs and actuator metrics for runtime or operability failures

For WoS or Scopus incremental-upload failures, inspect in this order:

1. `/admin/incremental-updates` flash outcome and uploaded file contract details.
2. Application logs for the relevant upload route (`/admin/incremental-updates/wos` or `/admin/incremental-updates/scopus`).
3. `/admin/initialization` for downstream maintenance steps that were intentionally skipped by the upload action.
4. Conflict/source-link/projection maintenance surfaces if the issue extends beyond upload-driven ingest and fact/canonical steps.

For Scopus scheduler publication/citation update failures, inspect in this order:

1. Scheduler logs for `ScopusUpdateScheduler` with the failing task id and batch id.
2. Duplicate-key target collection, if present (`identity_conflicts`, `source_links`, canonical edge collections, author/affiliation facts).
3. Whether the failing path stayed batch-scoped; scheduler maintenance should not full-rescan the corpus for a single batch.
4. `/admin/conflicts` and `/admin/source-links` when the failure involves replay of an already-known source record.

Scopus replay-hardening expectation:
- Repeating the same upload or scheduler batch should not require deleting Mongo state by hand.
- Duplicate-key failures in canonical Scopus maintenance usually indicate a replay-safety regression, not operator error.
- Full initialization is not the default retry path for one failing Scopus upload batch or one failing scheduler task.

Scopus missing-citation expectation:
- If citations disappear after incremental upload or scheduler maintenance, inspect batch-scoped projection refresh behavior before considering DB reset or full rebuild.
- Missing citations after incremental maintenance usually indicate projection-scope regression or drift, not operator cleanup failure.
- Previously visible citations that remain valid in canonical citation storage should survive batch maintenance.

For USER_DEFINED wizard-onboarding failures, inspect in this order:

1. `/admin/user-defined-triage` snapshot counts and recent lineage rows.
2. `/admin/source-links?source=USER_DEFINED` for link-state drift.
3. `/admin/conflicts?incomingSource=USER_DEFINED` for unresolved/ambiguous identity conflicts.
4. `/admin/initialization/user-defined/*` run outcomes (buildFacts, canonicalize, runAll).
5. `CANONICAL_MAINTENANCE canonical_build` logs and `core.h21.user_defined.*` gauges/counters.

## Common Areas

- Route/canonical UI regressions
- Source-link and identity-conflict drift
- USER_DEFINED source-fact/canonicalization drift
- Projection rebuild/readiness drift
- Asset and template contract regressions
- CI workflow and required-check drift

## Documentation Rule

- Keep durable triage guidance here.
- Keep task-specific failure evidence and closeout proof under `docs/tasks/closed/`.
