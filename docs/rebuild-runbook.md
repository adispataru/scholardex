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

1. **Back up the precious layer** (human-authored, not reconstructable). It is tiny — four config collections:
   `indicators` (the scoring criteria — admin-authored, **not** re-seeded by init; the domain + activity are
   embedded inline), `domains`, `scholardex.groups`, `individualReports`. User accounts + report run-history are
   disposable (re-onboard / re-run). `mongodump` is preferred; if unavailable, an in-Mongo copy works
   (`backups/` is git-ignored):
   ```bash
   for c in indicators domains scholardex.groups individualReports; do
     mongodump --uri="mongodb://localhost:27017/<db>" --collection="$c" --out=backups/precious-$(date +%F)
   done
   # or, type-preserving, with mongosh only:
   mongosh "<uri>" --quiet --eval 'const s=db.getSiblingDB("<src>"),d=db.getSiblingDB("<dst>");
     ["indicators","domains","scholardex.groups","individualReports"].forEach(c=>{d.getCollection(c).drop();
       const x=s.getCollection(c).find().toArray(); if(x.length) d.getCollection(c).insertMany(x);});'
   ```
   Derived data is NOT backed up — it is rebuilt. (To move to a fresh prod db, copy the four config collections
   into it, point `spring.mongodb.uri` at it, then run the from-scratch rebuild + admin/DBLP/OpenAlex below.)
2. **Confirm index integrity** (no drift / duplicates that would block startup):
   ```bash
   mongosh "mongodb://localhost:27017/<db>" scripts/h51-unique-index-duplicate-audit.js
   ```

## Build from scratch — the one self-contained command

`PipelineRebuildService.rebuildAllDerivedFromSource()` is **the** single guarded entry point. As of H66B M8-B
it is **fully self-contained**: it imports every forum/ranking feed from configured paths, ingests Scopus + WoS
publications, then builds facts → canonical → projections. **You do NOT pre-import feeds, and you do NOT call
`/scopus/ingest` or `/wos/ingest` separately** — the rebuild wipes the ledger and re-ingests everything itself,
in the correct forums-first order. (Validated 2026-06-19 on a from-scratch `scholardex` build.)

```bash
# Confirmation token is required (safety): the call no-ops without it.
curl -s -X POST http://localhost:8080/admin/initialization/rebuildAllDerived --data-urlencode "confirmation=RESET"
```

What it does, in order: reset canonical state (Scopus then WoS) → full `deleteAll()` of every owned managed
collection (repo-scoped, never a raw drop) → **WoS** (ingest official JSON + fold in MJL coverage → ledger →
facts → metrics/category/membership) → refresh DOAJ/ERIH reference snapshots → **Scopus** (import Source List +
CiteScore + Book list, ingest the publication JSON → ledger → facts → **forums-first** registry: Source List
backbone → dedup → ERIH/DOAJ onboard → WoS fold last) → canonical (DOI-primary identity) → OpenAlex/DBLP replay
(no-op unless those source-facts exist) → Postgres projections. Runtime ≈ 28 min on the full corpus.

### Required inputs on disk + their config keys

All read from `application.properties` (override via env). A blank/missing path **skips** that feed.

| Feed | Config key | Default path |
|---|---|---|
| Scopus publications | `scopus.data.file` | `data/scopus/complete_scopus_…_2026.json` |
| WoS publications | `h14.wos.official-json-dir` | `data/wos-json-1997-2019` |
| **Scopus Source List** (forum identity backbone — 48,580 serials) | `h66.scopus.source-list-file` | `data/scopus/ext_list_May_2026.xlsx` |
| Scopus CiteScore (rankings) | `h66.scopus.citescore-file` | `data/scopus/CiteScore 2023 per Nov 2024.csv` |
| Scopus Book list | `h66.scopus.book-list-file` | `data/scopus/Scopus_Books_list_February.xlsx` |
| WoS MJL coverage | `h66.wos.mjl-dir` | `data/wos/mjl` |
| DOAJ (open access) | `h66.doaj.file` | `data/doaj/doaj_journalcsv_…csv` |
| ERIH PLUS | `h66.erih.file` | `data/erih/erihplus.jsonl` |

> **Pitfall (the one that bit a from-scratch run):** the Source List — not CiteScore — is the forum identity
> backbone. Omitting it yields a thin forum registry (~33k forums instead of ~70k). The self-contained rebuild
> imports it automatically via `h66.scopus.source-list-file`; just make sure that key is set.

### Target database (Spring Boot 4)

The canonical store is bound from **`spring.mongodb.uri`** (NOT the legacy `spring.data.mongodb.uri`, which is
inert in Boot 4 — see the comment in `application.properties`). Default db is **`scholardex`**; point a prod run
elsewhere with `SPRING_MONGODB_URI`. Confirm the app connected to the intended db before firing the rebuild
(e.g. its startup index reconciliation should create the managed collections there, not in `test`).

### After the rebuild — multi-source enrichment + admin

The Scopus+WoS canonical layer is complete after the rebuild, but the Phase-4 sources are **not** (they replay
from durable source-facts that are empty on a fresh db):

1. **Admin user** — the app's `startup` health stays `OUT_OF_SERVICE` until a `PLATFORM_ADMIN` exists.
   `POST /general/adminUser` (or `/general/runAll`, which also loads URAP/CNCSIS/CORE/SENSE reference data)
   bootstraps one from `admin.email`/`admin.password` — **but only when `scholardex.users` is empty**
   (`createDefaultAdminUser` guards on `count()==0`). Clear any stray role-only user docs first, then re-run.
2. **DBLP conferences** — `POST /general/dblpLnChapterEnrichment` runs the corpus-matched dump sweep
   (`dblp.dump.file`) over the whole corpus: mints `conf/X` forums + scorer-compatible evidence (~2 min,
   ~2.3k conferences / ~850 series forms on the UVT corpus). Re-run when a new monthly dump is dropped in.
3. **OpenAlex** (citation graph, cited-by, ORCID authors) — per-researcher, runs as researchers are synced by
   ORCID; there is no bulk step.

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

## Forum registry (H66) — folded into the one rebuild

H66 made the canonical forum a first-class registry: rankings/indexing are forum-keyed attributes joined by the
stored `wosForumIds`/`scopusForumIds`/`erihIds`/`dblpIds` FK, retiring the fuzzy ISSN/name resolver. The build
is **deterministic** — a venue and its CiteScore entry share one Scopus Source ID, so they canonicalize to a
single forum; dedup-by-ISSN, the WoS↔Scopus fold, and ERIH onboarding + a second dedup all run **inside** the
forum build. **Reconcile = full rebuild; do not hand-merge.**

> **Superseded (H66B M8-B):** the per-feed admin imports that used to be manual pre-steps —
> `/scopus/importCiteScore`, `/scopus/importSourceList`, `/scopus/importBookList`, `/wos/importMjl`,
> `/forum/importDoaj`, `/forum/importErih` — are **now folded into the self-contained rebuild** (it imports
> each from its `h66.*`/`scopus.data.file`/`h14.wos.*` config path, see *Build from scratch* above). Do **not**
> run them by hand before a full rebuild; they only exist now for ad-hoc snapshot refreshes between rebuilds.
> Likewise the old `resetCheckpoints` step is unnecessary: `/rebuildAllDerived?confirmation=RESET` does a true
> full wipe, so there is no checkpoint to resume from.

So a forum-registry rebuild is just the standard from-scratch build with before/after count snapshots:

1. **Snapshot before**: `mongosh "<uri>" scripts/h54-derived-collection-snapshot.js > /tmp/before.txt`
2. **Rebuild**: `POST /admin/initialization/rebuildAllDerived` with `confirmation=RESET` (folds in all feeds).
3. **Snapshot after** and diff (see *Verify determinism*) — forum count stable across a second rebuild. On the
   UVT corpus a healthy registry is **~70k forums** (the full Source List backbone); ~33k means the Source List
   feed was missing (`h66.scopus.source-list-file` unset).

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
