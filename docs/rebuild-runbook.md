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

## Restore precious data (if needed)

Config: `mongoimport --jsonArray --mode upsert` each file under `seed/precious-config/` (see that
directory's README). PII: from the local `data/backups/precious-pii-<timestamp>/*.jsonl` dump.

## Notes

- The ledger (`*.import_events`) holds the parsed source events and is the replay buffer for
  fact-building. WoS wipes and re-ingests it on a full rebuild; Scopus keeps it and rebuilds facts
  from it. Either way a full rebuild re-derives facts deterministically.
- `user_defined.*` events come from user uploads with no external source file — their ledger payload
  is the only copy, so it is always retained.
