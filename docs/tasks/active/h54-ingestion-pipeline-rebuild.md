# H54 Ingestion Pipeline & Record-Keeping Rebuild

**Status:** Planning
**Created:** 2026-06-09

## Purpose

Restructure the data ingestion pipeline around a single principle: **one thin layer
is precious and authored by humans; everything else is a deterministic, rebuildable
function of the source files and APIs.** Make that distinction explicit in code,
storage, write-paths, indexing, and backups, so that the pipeline becomes lean,
contracts are enforced at boundaries, and any derived collection can be wiped and
regenerated at will.

This task subsumes `H51` (the narrow "enable `auto-index-creation`" sweep). H51's exit
criteria are achieved as a side effect of doing H54 correctly.

## Motivating evidence (audit, 2026-06-08)

A unique-index/duplicate audit (`scripts/h51-unique-index-duplicate-audit.js`) and a
boot test against the local `test` DB surfaced systemic integrity gaps:

- **34 unique indexes declared in code**; 6 were never created on the server, and 2
  existed in a different key/sparse shape than declared (`scholardex.citation_facts`,
  `scholardex.identity_conflicts`). The declarations were never enforced.
- **`scopus.import_events`: 321,563 rows, 23,543 unique idempotence groups (~14x
  duplication, top offender 123 copies).** The idempotency design was *correct*
  (`insert` + catch `DuplicateKeyException` → skip), but the unique index it relied on
  never existed, so every insert succeeded.
- **`scholardex.forum_facts`: 100% of 25,701 docs have `scopusForumIds: []`.** The
  unique index uses `sparse=true`, which does not skip empty arrays in multikey
  indexes — so the index is unbuildable. The same latent bug exists on three more
  multikey-array indexes (`wosForumIds`, `scopusAuthorIds`, `scopusAffiliationIds`),
  currently masked only because no doc happens to carry an empty array.
- **Shared write surfaces with no owner:** `scopus.import_events` has 9 writer classes;
  `scholardex.forum_facts` has 8. None upsert by natural key; disagreement about field
  semantics (e.g. `[]` vs missing) is what produced the unbuildable index.
- **Migration scaffolding for derived data:** `IndicatorV1MigrationRunner` (160 lines)
  and similar replay machinery exist because derived data is treated as something to
  migrate rather than rebuild.

None of these require new abstractions to fix. They require fewer write-paths, enforced
contracts, and a rebuild model in place of migrations.

## Hard constraint: the dev Mongo is a shared, multi-app database

The local `test` Mongo holds data for at least three independent apps (this `core` app,
a curriculum app `planuri.*`, a skills/occupation taxonomy, an exam system) plus orphaned
legacy collections from this app's earlier schema. This app owns **62 of ~95**
collections. Full classification: [docs/data-ownership-inventory.md](../../data-ownership-inventory.md).

Therefore, every wipe/rebuild operation in this task MUST act on an explicit allow-list of
owned collections. **Never `dropDatabase`, never drop by namespace prefix, never "wipe
everything derived."** The `PipelineRebuildService` (H54.6) holds the owned-collection
allow-list and refuses anything outside it. This constraint supersedes any convenience of
bulk wipes.

## The core model: precious vs derived

**Precious — human-authored, not reconstructable from any source. Back up.**

- `indicators` (~42) — indicator definitions
- `scholardex.publication_authorship_decisions` (~71) — human authorship decisions
- report/group configuration, `IndividualReport` definitions
- `institutions`, `domains` seed data
- user accounts / credentials

This is a few hundred documents. Snapshot to git-tracked JSON on change.

**Derived — a deterministic function of (source files + API snapshots). Never back up; rebuild.**

- Everything else: `scopus.*`, `wos.*`, `user_defined.*`, `scholardex.*`, the Postgres
  `reporting_read` projections, `userIndicatorResults`.

The real source of truth is `data/scopus/*.json`, `data/wos-json-1997-2019/`, the Scopus
API, DBLP, and user uploads — **not** Mongo. If a rebuild cannot reproduce a derived
collection, that is a pipeline bug, not a reason to keep a snapshot.

## Target layering

Five stages. Each stage is a **pure function of the stage above**, has **exactly one
writer**, a **single natural-key unique index**, and a **`rebuild()`** that can wipe and
regenerate its collections from its predecessor.

```
[ source files / APIs ]            real source of truth (disk / object store / API)
        │  parse + dedup
        ▼
1. RAW EVENT LEDGER                scopus.events, wos.events, userdefined.events
        │                          unique key: (source, entityType, sourceRecordId)
        │  transform (pure)
        ▼
2. SOURCE FACTS                    scopus.*_facts, wos.*_facts, user_defined.*_facts
        │                          unique key: natural source id (eid, afid, authorId, …)
        │  link + merge (pure)
        ▼
3. CANONICAL (Scholardex)         scholardex.*_facts
        │                          unique key: canonical identity
        │  project (pure)
        ▼
4. REPORTING PROJECTIONS          Postgres reporting_read.*
        │  score (pure, given indicator defs)
        ▼
5. COMPUTED RESULTS               userIndicatorResults (cache of scoring runs)
```

Rules:

- **One builder service per stage** is the only writer of that stage's collections. The
  8–9 redundant writers found in the audit become callers of the builder, or are deleted.
- **No stage reaches around its predecessor.** Stage 3 reads stage 2 only, etc.
- **Every write is `findOneAndReplace(upsert=true)` keyed by the natural key.** No
  `save()` of a generated `@Id` into a collection that has a natural key. The unique
  index is a safety net, not the primary dedup mechanism.
- **`rebuild()` per stage**; chaining 1→5 is a full rebuild from source files.

## Event ledger: fix the idempotency key

Current unique key is `(entityType, source, sourceRecordId, payloadHash)`. Putting
`payloadHash` *in the key* means a re-import of the same record with a corrected payload
produces a **new row** instead of superseding the old one.

Target:

- **Identity key (unique index):** `(source, entityType, sourceRecordId)` — one current
  event per source record.
- **`payloadHash` is a field, not part of the key.** On ingest:
  - hash matches existing → **skip** (true no-op)
  - hash differs → **supersede**: replace payload reference, bump `version`, set
    `supersededAt`.
- **Do not store the full source payload in the ledger.** (Decision 2026-06-09.) Store
  `payloadHash` + enough coordinates to re-fetch from the source file/API. The source
  files are the replay source. This removes the ~641 MB `scopus.import_events` payload
  bloat. If full-payload replay is ever needed, it belongs in a separate, compressed,
  append-only store — not the dedup ledger.

## Provenance on every derived record

Each derived doc (stages 2–5) carries:

```
sourceEventIds: [...]        // or sourceBatchId
builderVersion: "scopus-fact@3"
builtAt: Instant
```

- "Where did this number come from?" becomes a query.
- **`builderVersion` replaces migrations for derived data.** Change a builder's logic →
  bump its version → rebuild re-derives. Stop writing one-shot replay runners
  (`IndicatorV1MigrationRunner` and peers) for derived collections. Flyway/explicit
  migrations remain only for the *precious* layer.

## Index policy

- Enable `spring.data.mongodb.auto-index-creation=true` project-wide.
- Delete `ReportImportSessionIndexInitializer` and the overlapping index-maintenance
  services (`IndexMaintenanceSupport`, `WosIndexMaintenanceService`,
  `ScopusCanonicalIndexMaintenanceService`) once Spring owns creation. One mechanism.
- Replace `sparse=true` with `@CompoundIndex(partialFilter = "{ field: { $exists: true,
  $ne: [] } }")` on the four multikey-array unique indexes (`scopusForumIds`,
  `wosForumIds`, `scopusAuthorIds`, `scopusAffiliationIds`).
- Pick canonical key shapes for the two drift cases:
  - `scholardex.citation_facts :: uniq_scholardex_citation_edge` — decide 2-field
    `(cited, citing)` vs 3-field `(cited, citing, source)`.
  - `scholardex.identity_conflicts :: uniq_scholardex_open_identity_conflict` — decide
    sparse vs partialFilter.
- Add a **boot integration test** that starts the app against a seeded fixture DB and
  asserts every declared index exists and matches its declaration. This is the test that
  would have caught the entire 2026-06-08 incident.

## Testing contracts (the gap that allowed the incident)

Per ingest entry point and per builder:

1. **Double-ingest:** ingest the same payload twice → exactly one row. Catches
   missing-index / missing-upsert.
2. **Supersede:** ingest a record, then the same identity with a changed payload → one
   row, new version. Catches the idempotency-key bug.
3. **Rebuild determinism:** rebuild a stage twice from fixed input → byte-identical
   output. Guarantees "wipe and reimport" remains safe permanently.

## Record-keeping policy (summary)

| Layer | Backup? | Recovery |
|---|---|---|
| Precious (indicators, authorship decisions, config, seeds, users) | Yes — git-tracked JSON on change | Restore from snapshot |
| Raw event ledger | No | Rebuild from source files/API |
| Source facts / canonical / projections / computed results | No | Rebuild from the layer above |

The 658 MB of ad-hoc backups taken during the 2026-06-08 incident are point-in-time
artifacts only; the source files are the authoritative backup. Add `data/backups/` to
`.gitignore`.

## What this lets us delete

- 8–9 redundant writers per shared collection → one builder each.
- 3 index-maintenance services + 1 boot shim → 0 (Spring owns indexing).
- Migration-runner scaffolding for derived data → replaced by versioned rebuild.
- All derived-data backups → unnecessary.

## Open Questions

- **Event ledger granularity:** one ledger per source (`scopus.events`, `wos.events`,
  `userdefined.events`) vs one unified `import.events` with a `source` discriminator.
  Default proposal: per-source collections, matching the existing fact namespacing.
- **Rebuild orchestration surface:** admin-triggered per-stage rebuild endpoints vs a
  CLI/bootstrap runner vs both. Default proposal: a single `PipelineRebuildService` with
  per-stage methods, invoked by an admin endpoint and reusable from a bootstrap profile.
- **Scholardex status:** keep `scholardex.*` as independently-writable Mongo collections
  with a single owner (lower churn) vs convert to materialized views / `$merge` pipelines
  (stronger guarantee, larger change). Default proposal: single-owner first; reassess
  materialized views after determinism tests are green.
- **builderVersion bump policy:** automatic rebuild on version mismatch at boot vs
  explicit admin-triggered rebuild. Default proposal: explicit, with a startup warning
  when any collection's `builderVersion` lags the current builder.

## Proposed Slicing

- **H54.1** — Full-DB ownership + precious/derived classification, and snapshot the precious
  layer to git-tracked JSON. (No behavior change.)
  - Inventory written: [docs/data-ownership-inventory.md](../../data-ownership-inventory.md)
    — established the shared-DB constraint, the 62 owned collections, the legacy/orphaned
    Tier 3, and the foreign Tier 4 exclusion list. `data/backups/` confirmed already
    git-ignored.
  - **Remaining:** confirm the 3 VERIFY items (`scholardex.artisticEvent`,
    `userIndividualReportRuns`, `activities`/`activityInstances`) as precious-vs-derived,
    then write + run the precious-snapshot dump (Tier 1a + confirmed items) to git-tracked
    JSON.
- **H54.2** — Index declarations: `partialFilter` on the four multikey indexes; canonical
  key shapes for the two drift cases; enable `auto-index-creation`; delete the index
  shims/maintenance services; add the boot-index integration test. (Achieves H51's exit
  criteria.) **DONE 2026-06-09.**
  - Replaced `sparse=true` with `partialFilter = "{'<field>': {'$type': 'string'}}"` on the
    four multikey-array unique indexes (`ScholardexForumFact` ×2, `ScholardexAuthorFact`,
    `ScholardexAffiliationFact`). `$type` excludes empty/absent arrays; `$ne`/`$size` are not
    permitted in `partialFilterExpression`.
  - Drift decisions: `citation_facts` keeps the 2-field `(citedPublicationId,
    citingPublicationId)` key (a citation edge is one canonical fact; source is provenance;
    audit confirmed zero dups). `identity_conflicts` kept as declared (5-field unique; the
    `sparse` flag is harmless as no key field is null). Both drifted on-disk indexes were
    already dropped during the 2026-06-08 incident, so current declarations build clean.
  - Enabled `spring.data.mongodb.auto-index-creation=true`; deleted
    `ReportImportSessionIndexInitializer` (now redundant).
  - **Index-ownership convention established:** unique constraints are declarative
    (`@CompoundIndex`/`@Indexed`, created by auto-index-creation); non-unique performance
    indexes are owned by the maintenance services (`WosIndexMaintenanceService`,
    `ScopusCanonicalIndexMaintenanceService`). Removed three redundant `@Indexed` annotations
    on `WosJournalIdentity` (`primaryIssn`/`eIssn`/`aliasIssns`) that duplicated maintenance
    indexes under different names and caused `IndexOptionsConflict` (error 85) at startup.
  - Added `UniqueIndexCreationIntegrationTest` (Testcontainers) reproducing the empty-array
    crash condition and asserting partial uniqueness semantics. Boot verified clean under
    `agent-dev`; `scripts/h51-unique-index-duplicate-audit.js` now reports 0 drift / 0
    duplicates (script updated to be partialFilter-aware). Full `./gradlew test` green.
  - **Index-evolution caveat RESOLVED 2026-06-09** via `config/MongoIndexReconciler.java`
    (a `SmartInitializingSingleton`). It replaces Spring's create-only auto-index-creation
    (`auto-index-creation=false`): for every `@Document` it creates declared indexes and
    reconciles evolution — an "optimistic create, reactive drop-on-conflict" design:
    - code 86 (`IndexKeySpecsConflict`, same name/different spec) → drop the stale same-named
      index and rebuild (this is the trap that crashed boot twice);
    - code 85 (`IndexOptionsConflict`, same key/different name) → log and skip (do not drop a
      differently-named, maintenance-owned index; resolve at the declaration level);
    - code 11000 (duplicate data) → collected and rethrown so startup fails loudly with the
      full list of integrity problems.
    It never drops an index it does not declare (maintenance-owned and removed-from-code
    indexes are left untouched). Verified end-to-end: with a deliberately drifted
    `uniq_scholardex_citation_edge` (old 3-field shape) injected, boot logged
    "drifted … dropping and recreating", healed the index to the declared 2-field shape, and
    started clean. Guarded by `MongoIndexReconcilerIntegrationTest`.
  - **Index-ownership unification — DEMOTED to optional (2026-06-09).** The current split is
    principled, not incidental duplication: unique *constraints* are declarative and created
    before any write (reconciler); non-unique *performance* indexes are owned by the
    maintenance services and built in bulk-optimized ways (background, after large loads) —
    rebuilding ~57 of them at every boot would be wrong. The reconciler already neutralized
    the only dangerous part (an accidental declared/maintenance key collision now logs-and-skips
    via code 85 instead of crashing boot). A full rewrite is high-risk, low-payoff, and may not
    even be the correct end state. **Recommended instead:** a cheap guard test asserting no
    declared index key collides with a maintenance-service index — catches the error-85 class
    at test time. Full unification is not a planned slice.
- **H54.3** — Event-ledger redesign. **Re-scoped 2026-06-09 after tracing the flow:** the
  Scopus ledger's `payload` is NOT write-only audit data — `ScopusFactBuilderService`,
  `UserDefinedFactBuilderService` and the WoS parsers read `event.getPayload()` to build
  facts, and `buildFactsFromImportEvents()` rebuilds from `findAll()` over the ledger. So the
  ledger payload IS the current replay source for the whole derived pipeline. Two
  consequences: (1) dropping payload cannot be done in isolation — it requires re-pointing
  rebuild at source files, which is the H54.6 rebuild work; (2) `user_defined.*` events come
  from user uploads with no retained source file, so the ledger payload is their ONLY copy —
  a blanket payload-drop would destroy unrecoverable data. Therefore:
  - **H54.3a — DONE 2026-06-09.** Re-keyed the Scopus ledger unique index from
    `(entityType, source, sourceRecordId, payloadHash)` to `(source, entityType, sourceRecordId)`
    (`ScopusImportEvent`); added `version` + `updatedAt`. Rewrote `ScopusImportEventIngestionService`
    for supersede semantics on both paths: the single path uses repository find-or-supersede (works
    without `MongoTemplate`); the bulk path uses an atomic aggregation-pipeline upsert keyed by the
    identity, with `$cond` against the pre-update doc so identical re-ingest is a true no-op,
    changed payload replaces + bumps `version`, and a new identity inserts at `version=1`. Outcome
    mapping: a supersede counts as `imported` (a write), identical as `skipped` — so the outcome API
    and all callers are unchanged. Bulk write errors are now counted as `errors` (previously
    miscounted as `skipped`). Added `ScopusImportEventSupersedeIntegrationTest` (Testcontainers,
    single + bulk × identical-noop/supersede) and updated the existing unit tests to the bulkWrite
    contract. Boot verified clean; the ledger index is now `(source, entityType, sourceRecordId)`
    UNIQUE. Full `./gradlew test` green. Kept `payload` (rebuild still reads it). Scopus ledger only
    — the WoS ledger key already excludes payloadHash and audited clean; WoS supersede-on-change is
    a parallel follow-up.
  - **Moved to H54.6:** drop/externalize full-payload storage, once rebuild reads from source
    files — and only for sources with a retained external file (NOT user-sourced events).
- **H54.4** — Single-owner write-paths for stage-2 facts: one builder per source, route
  all existing writers through it, add provenance fields, delete redundant writers.
- **H54.5** — Single-owner write-paths for stage-3 canonical (Scholardex) with provenance
  and rebuild determinism tests.
- **H54.6** — `PipelineRebuildService` with per-stage `rebuild()`; chain to a full
  rebuild-from-source; replace derived-data migration runners with versioned rebuild.
  **Also (moved from H54.3):** re-point fact-building at source files instead of the ledger
  `payload`, then drop/externalize ledger payload — but only for sources with a retained
  external file; `user_defined.*` (user uploads) have no source file so their payload must be
  retained.
- **H54.7** — Full wipe + reimport from source files; verify determinism (rebuild twice →
  identical) end to end.

## Verification Plan (per slice)

- Unit: builder purity (same input → same output), natural-key upsert idempotency,
  supersede semantics, partialFilter index buildability against fixtures with empty
  arrays.
- Integration: boot against seeded fixture DB asserts all declared indexes present and
  matching; double-ingest and supersede per entry point; per-stage rebuild determinism.
- End-to-end: wipe all derived collections, reimport from source files, assert row counts
  and a sample of computed scores match a pre-wipe reference; rebuild a second time and
  assert byte-identical derived output.
- Tooling: `scripts/h51-unique-index-duplicate-audit.js` reports zero drift and zero
  duplicates after H54.2 and again after H54.7.
