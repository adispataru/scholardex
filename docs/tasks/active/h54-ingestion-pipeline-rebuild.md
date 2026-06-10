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
  - **H54.3b — WoS ledger supersede parity. DONE 2026-06-09.** Finding (2026-06-09): the WoS ledger ALREADY
    supersedes correctly — `WosImportEventIngestionService.processEventFast` loads existing events
    by row-item and does skip-if-identical / update-in-place-if-changed / insert-if-new, and the
    behavior is already covered by `rerunIsIdempotentAndChangesProduceUpdates`. The earlier
    "parallel follow-up" note wrongly assumed WoS used Scopus's old insert-and-skip. So this slice
    is not "add supersede" — it is the parity gap: WoS overwrote `ingestedAt` on every update
    (losing first-ingest time) and tracked no `version`. H54.3b adds `version` + `updatedAt` to
    `WosImportEvent`, preserves `ingestedAt` as first-ingest time, and bumps `version` on supersede
    — matching the Scopus 3a shape. (`ingestedAt` is read nowhere except the supersede's own
    checksum comparison, so changing its meaning is safe.) Implemented: `WosImportEvent` gains
    `version` + `updatedAt`; `processEventFast` preserves `ingestedAt`, sets `updatedAt`, and bumps
    `version` on supersede (insert = version 1). Extended `rerunIsIdempotentAndChangesProduceUpdates`
    to assert version 1 → 2, preserved `ingestedAt`, and non-null `updatedAt`. Full `./gradlew test`
    green. No index/startup change (WoS key unchanged), so no boot/reconcile step required.
  - **Moved to H54.6:** drop/externalize full-payload storage, once rebuild reads from source
    files — and only for sources with a retained external file (NOT user-sourced events).
- **H54.4** — Single-owner write-paths for stage-2 facts: one builder per source, route
  all existing writers through it, add provenance fields, delete redundant writers.
  **Scoping result 2026-06-09 (read-only audit): the consolidation goal is already met at
  stage-2.** Writer map:
  - `scopus.*_facts` (publication/author/affiliation/citation/forum/funding) — sole content
    writer is `ScopusFactBuilderService` (saveAll); `ScopusBigBangMigrationService` only calls
    `deleteAll()` (a rebuild wipe).
  - `wos.{category,metric}_facts` + `wos.fact_conflicts` — sole writer `WosFactBuilderService`;
    `wos.journal_identity` written by `WosIdentityResolutionService.save` (a build sub-step);
    `WosBigBangMigrationService` only `deleteAll()`.
  - `user_defined.{publication,forum}_facts` — sole writer `UserDefinedFactBuilderService`.
  - The `Scholardex*CanonicalizationService` and parity/reconciliation services only **read**
    stage-2 facts (zero writes). No redundant/rogue writers to delete.
  - Provenance already present on stage-2 facts (`sourceEventId`, `source`, `sourceRecordId`,
    `sourceBatchId`, `sourceCorrelationId`, WoS `sourceType`/`sourceFile`/`sourceVersion`/
    `sourceRowItem`; Scopus/UserDefined also `updatedAt`). **Only gap: `builderVersion`
    everywhere, and `builtAt` on WoS facts.**
  - `builderVersion` only has meaning once a versioned rebuild consumes it (H54.6), so it is
    deferred there rather than stamped in isolation now.
  - **The real multi-writer sprawl is at stage-3** (`scholardex.*`): e.g. `scholardex.publication_facts`
    is written by ~10 services (onboarding, authorship-decision, edge-reconciliation, enrichment
    linker, DBLP enrichment, two canonicalizers, projection builder, migration, user-defined
    canonicalization). That is H54.5's domain.
  - **CLOSED-as-verified 2026-06-09:** stage-2 is already single-owner with full source lineage;
    no redundant writers to remove. `builderVersion`/`builtAt` deferred to H54.6 (where a versioned
    rebuild consumes them). Proceeding to H54.5 for the stage-3 consolidation.
- **H54.5** — Single-owner write-paths for stage-3 canonical (Scholardex) with provenance
  and rebuild determinism tests.
  **Scoping result 2026-06-09 (read-only audit).** Unlike stage-2, stage-3 canonical facts are
  mutated by *multiple legitimately-different concerns* — so "one builder" does NOT fit; the
  fix is a single guarded **write facade**, not collapsing everything into one builder. Accurate
  writer map (direct `repository.save/saveAll/insert/delete` on `scholardex.*_facts`):
  - **Canonicalization builders (source → canonical):** `ScholardexPublicationCanonicalizationService`
    (publication_facts), `ScholardexAuthorCanonicalizationService` (author_facts),
    `ScholardexAffiliationCanonicalizationService` (affiliation_facts),
    `ScholardexCitationCanonicalizationService` (citation_facts),
    `UserDefinedCanonicalizationService` (publication_facts + forum_facts),
    `WosScholardexOnboardingService` (forum_facts).
  - **Enrichment/linking:** `PublicationEnrichmentLinkerService` (publication_facts).
  - **Reconciliation (edge deletes):** `ScholardexEdgeReconciliationService` (authorship_facts,
    author_affiliation_facts).
  - **User manual edits — MISLOCATED:** `ScholardexProjectionReadService.save{Forum,Author,Affiliation}`
    write canonical facts with `source = MANUAL_*_EDIT`. A write path inside a class named
    "ProjectionReadService" (CQRS smell) — relocate.
  - **Rebuild wipe:** `ScopusBigBangMigrationService.deleteAll` (→ H54.6).
  - **Confirmed NON-writers (reads only), correcting the earlier ~10 estimate:**
    `AdminDashboardService` (reads), `DblpPublicationEnrichmentService` (writes the separate
    `publication_dblp_evidence` collection, not facts), `PublicationAuthorshipDecisionService`
    (writes the `publication_authorship_decisions` collection), `ScholardexProjectionBuilderService`
    (writes Postgres `reporting_read`, i.e. stage-4).
  - Per collection: publication_facts has 3 writers/3 concerns; forum_facts 3 writers/2 concerns;
    author & affiliation 2 each (builder + manual edit); citation 1.
  - **Proposed design:** a `ScholardexCanonicalWriter` facade (unified or per-entity) that owns the
    repositories and exposes intent-named operations (`upsertFromSource`, `applyEnrichment`,
    `applyManualEdit`, `removeEdge`), centralizing canonical-id resolution, source-id merging,
    provenance stamping (+ future `builderVersion`), invariant enforcement, and dirty-marker
    emission. The ~9 call sites become callers. Likely sub-sliced: 5a builders → facade;
    5b enrichment + reconciliation → facade; 5c relocate the manual-edit path out of the read
    service. High risk (canonical linking core, many call sites) — behavior-preserving, leans on
    existing canonicalization tests + new determinism tests.
  - **Decisions 2026-06-09:** (1) facade granularity — **decide after a 5a spike** on
    `publication_facts` (build the publication writer first, see whether per-entity or one unified
    writer reads better in this codebase, then commit for the rest); (2) the mislocated user
    manual-edit write path **is in scope** as 5c. Sub-slices: **5a** publication writer spike +
    route its 3 call sites (publication canonicalization, user-defined canonicalization,
    enrichment linker); **5b** remaining-entity writers (author/affiliation/citation/forum) +
    edge reconciliation, granularity per the 5a decision; **5c** relocate manual edits onto the
    facade out of `ScholardexProjectionReadService`.
  - **5a DONE 2026-06-09.** Added `CanonicalWriteProvenance` (shared record) and
    `ScholardexPublicationWriter.upsertAndLinkSource(fact, provenance, reason)` — stamps the four
    uniform provenance fields + `updatedAt`, persists, and upserts the PUBLICATION source link
    (guard-skips when source/sourceRecordId blank, preserving prior linker behavior; links by the
    fact's own id, as the inline paths did; `sourceEventId` stays caller-managed on the fact and is
    recorded only on the link). Routed `PublicationEnrichmentLinkerService` (both wos + scholar
    paths; dropped its now-unused `sourceLinkService` field and private `upsertSourceLink`) and the
    `UserDefinedCanonicalizationService` publication path through the writer. Added
    `ScholardexPublicationWriterTest`; updated the two service tests to wire a real writer over the
    mocked repo + source-link service so their existing save/link verifications still hold. Full
    `./gradlew test` green. The writer is the future chokepoint for dirty-markers + `builderVersion`.
  - **5b DONE 2026-06-09.** Scoping found the remaining entities don't share publication's clean
    uniform shape, so 5b is narrower than the doc imagined:
    - **Forum:** added `ScholardexForumWriter` (mirrors the publication writer) and routed the
      `UserDefinedCanonicalizationService` forum path through it. `WosScholardexOnboardingService`
      is intentionally NOT routed — its `mergeForum` stamps none of the four provenance fields
      (it tracks provenance via the source link only), so forcing it through a provenance-stamping
      writer would silently add fields it never wrote; left as its own builder.
    - **Edges:** `ScholardexEdgeWriterService` already owned edge upserts and reconciliation already
      routed upserts through it, but the two reconciliation *deletes* hit the repository directly.
      Added `removeAuthorshipEdge` / `removeAuthorAffiliationEdge` (behavior-preserving delegates to
      `repository.delete`, consistent with the writer which emits no dirty-markers) and routed the
      two `ScholardexEdgeReconciliationService` deletes through them — now all edge mutations
      (upsert + delete) flow through the edge writer.
    - **Author/affiliation/citation:** no non-grandfathered, non-manual-edit writers exist, so no
      writers were built (would be speculative); their writers arrive in 5c where the manual-edit
      path needs them.
    - Tests: `ScholardexForumWriterTest`, edge-writer remove tests; updated the reconciliation test
      to verify the routed-through removes and wired a real forum writer into the user-defined test.
      Full `./gradlew test` green (one Testcontainers Mongo read-timeout flake in untouched
      integration tests, passed on isolation + re-run).
  - **5c DONE 2026-06-09.** Relocated the user manual-edit write path out of the read service —
    fixing the CQRS smell:
    - Added `applyManualEdit(fact, source, sourceRecordId, reason)` to `ScholardexForumWriter` and
      new `ScholardexAuthorWriter` / `ScholardexAffiliationWriter`. It stamps
      source/sourceRecordId/updatedAt only (NOT batch/correlation, which manual edits preserve) +
      save + source-link with null batch/correlation/event — matching the prior behavior exactly.
    - Extracted `ScholardexCanonicalIdResolver` (resolveCanonicalId + resolveCanonicalIds) shared by
      the read service and the new write service.
    - Created `ScholardexManualEditService` holding `saveForum`/`saveAuthor`/`saveAffiliation`
      (resolve canonical id → load-or-create fact → set content → delegate to the writer →
      author-affiliation edges via `ScholardexEdgeWriterService`). Redirected the two callers
      (`AdminCatalogFacade`, `CacheService`) to it.
    - `ScholardexProjectionReadService` lost the 3 save methods + write-only helpers
      (`resolveCanonicalId`, `upsertSourceLink`) and 5 now-dead injected fields (the 3 Mongo fact
      repos, `edgeWriterService`, `sourceLinkService`); `resolveCanonicalIds` delegates to the
      resolver. Feasible because only ONE test constructed it directly (the other 25 mock it).
    - Tests: migrated the save-path tests into `ScholardexManualEditServiceTest` (real writers +
      resolver over mocked repos/source-link); updated `AdminCatalogFacadeTest` (@InjectMocks),
      `CacheServiceTest` (constructor + verifies), and the one read-service constructor call. Full
      `./gradlew test` green (incl. the `@SpringBootTest` context test validating the new beans +
      changed constructors).
  - **5a spike findings 2026-06-09 (read-only).** All three publication write sites share the same
    pre-persist shape: stamp provenance (`source`/`sourceRecordId`/`sourceBatchId`/
    `sourceCorrelationId`/`updatedAt`, sometimes `sourceEventId`) → `repository.save` → usually
    `upsertSourceLink`. That common path is the natural facade responsibility (+ dirty-markers and
    future `builderVersion`). BUT the writes are not uniform:
    - `UserDefinedCanonicalizationService` and `PublicationEnrichmentLinkerService` are simple
      single-`save` + provenance + source-link — trivial to route through a facade.
    - `ScholardexPublicationCanonicalizationService` (~1100 lines) does **bulk chunked insert/update**
      partitioned by `pendingInsertPublicationIds`, with `DuplicateKeyException` recovery that looks
      up the existing fact by normalized DOI and re-merges via `applyCanonicalPublicationFields` —
      tightly coupled to its `ChunkContext`. The merge is canonicalization-specific; the
      persist+recovery+provenance is generic.
    - **Granularity recommendation:** **per-entity writers** (each entity has its own natural key /
      recovery key — publication=DOI, forum=ISSN, author=name — and source-link entity type) with a
      small shared provenance/source-link/dirty-marker helper. A single unified writer would either
      leak per-entity recovery logic or become a god-class.
    - **Facade boundary:** the writer owns persist + provenance stamping + source-link upsert +
      dirty-marker + generic dup-key-by-natural-key recovery; callers keep content computation/merge.
    - **Risk:** the 2 simple sites are low-risk; routing the canonicalization bulk path through the
      writer's batch method (while leaving merge in the service) is the high-risk part — behavior must
      be preserved, leaning on existing canonicalization tests + a new rebuild-determinism test.
  - **Scope decision 2026-06-09 — sanctioned surface, not literal single owner.** H54.5's goal is
    reframed: the per-entity writer is the **sanctioned write surface for the secondary/ad-hoc
    mutators** (where provenance/source-link/dirty-marker drift actually creeps in); the four Scopus
    bulk **canonicalization builders are grandfathered as correct** and keep their direct,
    optimized bulk+recovery write paths. The dividing line is write *shape*, not layer:
    - **Route through the writer (simple single-`save` + provenance):** `UserDefinedCanonicalizationService`,
      `WosScholardexOnboardingService`, `PublicationEnrichmentLinkerService`,
      `ScholardexEdgeReconciliationService` (delete), and the manual-edit path in
      `ScholardexProjectionReadService` (5c).
    - **Grandfathered as correct (bulk chunked insert/update + dup-key recovery):**
      `ScholardexPublication/Author/Affiliation/CitationCanonicalizationService`.
    - **Trade-off (explicit):** this does NOT yield one-writer-per-collection (e.g. `publication_facts`
      keeps the canonicalization builder + the writer). It yields one *sanctioned surface* for
      secondary mutations. Grandfathered builders carry an explicit contract: they must stamp the
      same provenance the writer enforces. Reversible — a builder may adopt the writer's batch method
      later. This removes the high-risk bulk-path rewrite from H54.5 entirely.
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
