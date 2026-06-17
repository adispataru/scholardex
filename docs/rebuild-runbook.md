# Derived-data rebuild & determinism runbook (H54)

**Status:** Living reference
**Created:** 2026-06-11

How to wipe and rebuild all derived data from source, and verify the rebuild is deterministic.
The pipeline is event-sourced: source files/APIs → ledger (`*.import_events`) → stage-2 facts →
stage-3 canonical (`scholardex.*`) → stage-4 Postgres projections. Every stage is a deterministic
function of the stage above, so a wipe + reimport reproduces the same derived data.

## The one rule

**Only ever wipe collections this app OWNS.** The dev Mongo is shared with other apps
(`planuri.*` curriculum, a skills/occupation taxonomy, an exam system) and holds orphaned legacy
collections. Never `dropDatabase`, never drop a foreign collection. Ownership is defined by the
`@Document` models and enforced at runtime by `OwnedCollectionRegistry`; `PipelineRebuildService`
asserts it before any rebuild. Full classification: [docs/data-ownership-inventory.md](data-ownership-inventory.md).

## Before you start

1. **Back up the precious layer** (human-authored, not reconstructable): indicators, report/group
   config, org structure, authorship decisions, user accounts. It is tiny.
   ```bash
   mongosh "mongodb://localhost:27017/<db>" scripts/h54-1-snapshot-precious.js
   ```
   Config goes to git-tracked `seed/precious-config/`; PII to a git-ignored `data/backups/` dump.
   Derived data is NOT backed up — it is rebuilt.
2. **Confirm index integrity** (no drift / duplicates that would block startup):
   ```bash
   mongosh "mongodb://localhost:27017/<db>" scripts/h51-unique-index-duplicate-audit.js
   ```

## Rebuild

The per-stage rebuild lives in `ScopusBigBangMigrationService` / `WosBigBangMigrationService`;
`PipelineRebuildService.rebuildAllDerivedFromSource()` is the single guarded entry point that runs
both WoS and Scopus full rebuilds (ingest source files → ledger → facts → canonical → projections)
after asserting every managed collection is owned. Wipes inside the rebuild are repo-scoped
`deleteAll()` on owned fact collections — never raw drops.

Trigger it through the admin initialization flow (which drives the same BigBang rebuild), or
programmatically via `PipelineRebuildService`. Required inputs on disk:
`scopus.data.file` (Scopus JSON) and the WoS source dir (`h14.wos.official-json-dir`,
default `data/wos-json-1997-2019`).

## Verify determinism (at scale)

1. **Snapshot counts before** any change:
   ```bash
   mongosh "mongodb://localhost:27017/<db>" scripts/h54-derived-collection-snapshot.js > /tmp/before.txt
   ```
2. **Wipe + rebuild** (as above), then snapshot again:
   ```bash
   mongosh "mongodb://localhost:27017/<db>" scripts/h54-derived-collection-snapshot.js > /tmp/after1.txt
   ```
3. **Rebuild a second time** and snapshot once more (`/tmp/after2.txt`).
4. **Diff** — counts must match across all three:
   ```bash
   diff /tmp/before.txt /tmp/after1.txt && diff /tmp/after1.txt /tmp/after2.txt && echo "DETERMINISTIC"
   ```
   A mismatch in any owned derived collection means the rebuild is non-deterministic — investigate
   the stage that owns that collection (each has exactly one builder).

Count parity is the practical at-scale check. **Content-level determinism** (byte-identical derived
documents across rebuilds, excluding generated `_id` and per-build timestamps) is proven in CI by
`PipelineRebuildDeterminismIntegrationTest`, which runs the real Scopus fact builder twice on a
fixture and asserts identical output. The full multi-GB content comparison is not run in CI.

## H66 release — one-time forum-registry rebuild (migration)

H66 makes the canonical forum a first-class registry: rankings/indexing are forum-keyed attributes joined
by the stored `wosForumIds`/`scopusForumIds` FK, retiring the fuzzy ISSN/name resolver (which silently
scored ~39 forums to 0). The build is **deterministic** — a venue and its CiteScore entry share one Scopus
Source ID, so they canonicalize to a single forum; dedup-by-ISSN and the WoS↔Scopus fold already run inside
`buildFacts`. So the H66 migration is **not bespoke merge code** — it is one full rebuild that also ingests
the two new source feeds, then a read-only verification gate. **Reconcile = full rebuild; do not hand-merge.**

Run on a prod-equivalent snapshot first, then prod. All endpoints are under `/admin/initialization`.

1. **Snapshot before** (counts incl. `scholardex.forum_facts`):
   ```bash
   mongosh "mongodb://localhost:27017/<db>" scripts/h54-derived-collection-snapshot.js > /tmp/h66-before.txt
   ```
2. **Ensure the two new source feeds are on disk and ingested** (new in H66):
   - CiteScore (A2 — Scopus FORUM stream; supplies `forumType`/`asjc`):
     `POST /scopus/importCiteScore?path=<citescore.csv>`
   - MJL coverage (A3 — WoS source stream; supplies `wos.coverage_facts` → membership view):
     `POST /wos/importMjl?dir=data/wos/mjl&sourceVersion=2025`
   - DOAJ open-access (A4 — reference data; supplies `doaj.journal_facts` → membership view `database='DOAJ'`):
     `POST /forum/importDoaj?path=data/doaj/<doaj-dump>.csv&asOf=2026`. Note: `doaj.journal_facts` is NOT
     wiped by the rebuild (it is an external snapshot, not source-replayed), so this only needs re-running to
     refresh the DOAJ snapshot — but the projection re-reads it each rebuild.
   - ERIH PLUS (A5 — reference data; supplies `erih.journal_facts` → `erihIds` FK + membership `database='ERIH'`):
     `POST /forum/importErih?path=data/erih/erihplus.jsonl&asOf=2026`. Also NOT wiped by the rebuild. To
     refresh the snapshot, re-pull from the ERIH PLUS Typesense export (`erihplus_tidsskrift_cache`) — see
     [docs/tasks/active/h66-curated-allowlists.md](tasks/active/h66-curated-allowlists.md) for host/key/pull.
     Unlike DOAJ, ERIH **writes `erihIds` onto forums** (next step), so it must be re-onboarded after the
     rebuild reconstructs forums.
3. **Reset checkpoints so the rebuild is FULL, not incremental** (the default `useCheckpoint=true` resumes
   from the last batch and would skip already-processed events):
   - `POST /scopus/resetCanonicalCheckpoints`
   - `POST /wos/resetFactCheckpoint` (and `POST /wos/resetCanonicalState` if rebuilding WoS canonical state)
4. **Full rebuild** via the guarded entry point `PipelineRebuildService.rebuildAllDerivedFromSource()` (the
   admin flow drives the same BigBang rebuild): ingest → facts → canonical (forum dedup + WoS/Scopus fold) →
   projections. If running per-step instead, the Scopus build-facts/canonical steps must run with
   `useCheckpoint=false` after the reset above.
5. **ERIH onboarding + dedup is automatic in the full rebuild.** `runFull` / `PipelineRebuildService` now
   runs `ErihOnboardingService.onboardErih()` (writes `erihIds` onto the freshly-rebuilt forums) followed by
   a second dedup pass (clusters by ISSN **+ shared erihId**, C1 part 2) right before its projection — so a
   single full rebuild produces the complete registry. No manual step needed here. (For the **step-wise**
   admin path — `buildFacts` then `buildProjections` separately — run `POST /forum/onboardErih` then
   `POST /forum/dedup` between them; both endpoints also exist for re-runs.) Prerequisite: `importErih` (and
   `importDoaj`) must have populated the reference data before the rebuild — they persist across it.
6. **Build projections** — produces the three forum-keyed views B2/B3 read by FK
   (`scholardex_forum_metric_view` / `_category_view` / `_membership_view`); membership now includes DOAJ +
   ERIH rows: `POST /scopus/buildProjections` (and `POST /wos/rebuildProjections` for the WoS side).
7. **Snapshot after** and diff against before (same procedure as *Verify determinism* above) — forum count
   should be stable across a second rebuild.

### Post-rebuild verification gate (C2.2)

Read-only; mutates nothing. **Block the release if `healthy` is false.**
```bash
curl -s http://localhost:8080/admin/initialization/forum/reconcileAudit | jq
```
Assert in the `ForumReconcileAuditReport`:
- **`healthy: true`** and **`orphanedPublicationForumLinks: 0`** — every publication still resolves to a live
  canonical forum (the hard gate; a non-zero count means a merge dropped a forum without re-pointing).
- **`wosLinkedResolvingMetricsByFk`** ≈ the WoS-linked forums that carry metric facts — proves the
  previously-fuzzy cases now resolve by `forum_id` FK. (`wosLinkedMissingMetricsByFk` is expected non-zero:
  MJL coverage-only journals have no AIS/IF metrics.)
- **`fkMetricForums` / `fkCategoryForums` / `fkMembershipForums`** match the projection view distinct
  `forum_id` counts (reference values on `core_h66`: 25,637 / 25,314 / 22,963).

Record `forumsTotal` from the audit alongside the before/after snapshots as the migration's count evidence.

## Restore precious data (if needed)

Config: `mongoimport --jsonArray --mode upsert` each file under `seed/precious-config/` (see that
directory's README). PII: from the local `data/backups/precious-pii-<timestamp>/*.jsonl` dump.

## Notes

- The ledger (`*.import_events`) holds the parsed source events and is the replay buffer for
  fact-building. WoS wipes and re-ingests it on a full rebuild; Scopus keeps it and rebuilds facts
  from it. Either way a full rebuild re-derives facts deterministically.
- `user_defined.*` events come from user uploads with no external source file — their ledger payload
  is the only copy, so it is always retained.
