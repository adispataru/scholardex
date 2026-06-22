# H75 — Canonical derivation engine V2 (batch ETL)

## Problem

A full `rebuildAllDerived` takes **~90 min** for a corpus of **~5M small documents** (~1M WoS events → ~1.4M
facts; 92k Scopus pubs + ~200k authors; 117k OpenAlex pubs + ~380–510k authors; a few million edges). That is
**~900 docs/sec** — 50–200× under what a single Mongo node does (50k–200k bulk inserts/sec). It is a code-shape
problem, not a data-volume problem.

**Root cause: the canon stages are written as per-record OLTP, not batch ETL.** The hot loops do synchronous
per-record Mongo I/O — N+1 reads + one-record-at-a-time writes. Evidence in the OpenAlex canon alone, **per pub ×
117k** (`OpenAlexCanonicalizationService`):
- `resolveForumId` → `findByOpenAlexIdsContaining` — a query per pub (should be one preloaded map);
- `findAllByDoiNormalized` — a query per pub;
- `writeAuthorshipEdges` → `findById(pubId)` — re-reads the pub just written, per pub;
- per-pub `save` for `authorIds` + `upsertAndLinkSource` (save + source-link write) in `applyOpenAlexFields`.

≈ 5–6 round trips × 117k ≈ **~600k synchronous ops ≈ ~30 min** for that stage alone. The S3 "bulk mode" only
batched *edges* + *authors*; pubs, source-links and forum lookups stayed per-record. The Scopus canon, the forum
build (~100 forums/sec — N+1 create-or-match) and the reconcile (author dedup ≈ all-pairs over ~510k) share the
shape. Structurally, every rebuild also **re-ingests** 1M WoS events + **re-parses** 2.3 GB of OpenAlex JSON even
when only canon logic changed.

## Goal

A new **canonical-derivation engine** that turns the durable source facts into the canonical layer as a **pure
in-memory batch transform with bulk loads**: target **< 10 min** (likely ~5) for the current corpus, scaling
linearly. The win comes from three rules: **(1)** no DB round-trip inside the transform, **(2)** bulk **INSERT**
(wipe-first → no upsert existence checks) in large unordered batches, **(3)** ingest is cached; derive is a pure
recompute.

## Scope

**IN** — derivation of the canonical Mongo layer from already-ingested source facts:
`scholardex.{publication,author,affiliation,forum}_facts`, all edges (authorship, author→affiliation,
pub→author→affiliation, citations), and `scholardex.source_links`. The author **reconcile** (ORCID / fuzzy /
over-split dedup) folds **into** the build, not a separate post-pass.

**OUT** (unchanged, on top of / beside V2):
- source-fact **ingestion** (Scopus JSON, OpenAlex file parse, WoS event ingest, DOAJ/ERIH/DBLP) — already durable;
- **Postgres projections** / reporting / API read side — run on V2's canonical output as today;
- the per-sync **Tier-2 incremental** path — stays on V1 until V2 proves out (V2 is full-rebuild first).

This **absorbs H74** (forums → affiliations → pubs → authors becomes the in-memory build order, not a chain reorder)
and supersedes the per-record "bulk mode" patches in H73 S3/S3.5.

## Architecture: Load → Build → Write

1. **Load (Extract).** One streaming scan per source collection into typed in-memory structures. Source facts only.
   ~5M small docs ≈ low-GB heap (fits comfortably; if it ever doesn't, partition by source).
2. **Build (Transform) — zero DB I/O.** Pure functions over the in-memory inputs, in dependency order:
   - **Forums** — union by ISSN / openalexId / dblpId across WoS identity + DOAJ/ERIH + DBLP + Scopus forum facts → `sforum_` map.
   - **Affiliations** — OpenAlex institutions → ROR backbone (`saff_<hash(ror)>`); Scopus afids resolve via the 3-tier alias matcher (exact-alias → simplification → country-gated Jaccard) or mint Scopus-only.
   - **Publications** — hash-join all sources by canonical key (DOI → eid → wos → title, with the DOI blocklist) → `spub_` map; OpenAlex field-precedence; Scopus enrich (`eid` + Scopus-only fields).
   - **Authors (the hard part)** — a **union-find** over author-identity signals. One node per source author (Scopus AU-ID, OpenAlex ORCID / openalex-id). Union edges from: same ORCID; the **positional bridge** per shared paper (equal-count + per-position surname guard); the reconcile rules (fuzzy name+verified-affiliation + co-author-overlap tiering, over-split merge, >20-author guard). Each connected component → one `sauth_` carrying every source id. **This single pass replaces the per-record author canon + the separate reconcile passes.**
   - **Edges** — formed from the resolved pub/author/affiliation maps in memory, deduped by natural key.
3. **Write (Load).** Wipe target collections, then **bulk `insertMany`** (unordered, 5–10k/batch) — no upserts,
   because wipe-first guarantees inserts. **Create indexes after load** (bulk-load-then-index beats index-then-load).
   Source-links written the same way.

## Identity-rules catalog (ported verbatim from V1 — the risk surface)

Every rule below gets a focused unit test **and** a differential check against V1. (Detailed enumeration filled in
during Stage 0 by reading V1.)
- **Pub key:** DOI → eid → wos → user → title+date+creator+forum; H66B Decision-0 DOI blocklist (container DOIs).
- **Affiliation:** ROR backbone id; 3-tier Scopus alias match; verified-afid (`60…`) vs drop ad-hoc (`1xxxxxxxx`).
- **Author:** ORCID seed; positional bridge (equal-count + surname); fuzzy name+verified-affiliation + co-author
  overlap tiers; over-split merge; >20-author-paper exclusion; AU-ID-keyed vs ORCID/openalex-keyed schemes.
- **Forum:** ISSN clustering, primary-ISSN tiebreak, ROR/openalex/dblp ids, single-tag for OpenAlex.
- **S2.2 inversion (just shipped):** OpenAlex-keyed author `@Id` is primary; AU-IDs fold in. V2 produces this
  natively (it's the in-memory union outcome), so it's the *expected* diff vs the pre-S2.2 V1 snapshot.

## Validation: differential harness (the safety net)

The only safe way to rewrite battle-tested identity logic. Build it in Stage 0, before any engine code:
- Snapshot V1's canonical collections (counts + content, excluding volatile `_id`/timestamps) on **(a)** a small
  fixture and **(b)** the real corpus.
- Run V2 → snapshot → **diff**. V2 must equal V1 **except** the encoded, intended S2.2 deltas.
- Reuse the Testcontainers `@SpringBootTest` harness (`OpenAlexFirstInversionIntegrationTest` pattern); add
  golden-count assertions; that inversion test must keep passing.

## Delivery stages (build-alongside, flag-gated — V1 stays until V2 is provably green)

- **Stage 0 — design + harness + baseline.** This doc; the differential harness; a real **profile** of V1 (phase
  timings + per-stage Mongo-op counts) as the baseline to beat. Read V1 to fill the rules catalog.
- **Stage 1 — engine skeleton.** `CanonicalSourceLoader` (in-memory model) + `CanonicalGraphBuilder` (forums +
  affiliations + pubs only) + `BulkCanonicalWriter` (wipe → insertMany → index) behind a `rebuildCanonicalV2` flag.
  Differential-validate forums/affiliations/pubs against V1.
- **Stage 2 — authors + edges.** The union-find author build (porting the reconcile rules) + all edges.
  Differential-validate the author graph + edges against V1 — **the hard gate**.
- **Stage 3 — citations + projections + real-corpus run.** Citation edges; run the Postgres projections on V2
  output; full differential on the real corpus; record the timing (target < 10 min).
- **Stage 4 — cutover.** Flip the flag default to V2; keep V1 one release; then delete V1 + the per-record
  "bulk mode" scaffolding.

## Decisions to confirm
- **Store stays Mongo** (single-node in-memory build is enough at this scale — no Spark/columnar).
- **Build-alongside + differential validation** (not in-place replacement).
- **Full-rebuild only** for V2 initially; incremental Tier-2 stays on V1 until later.
- **Scope boundary excludes** WoS ingest + Postgres projections (V2 stops at the canonical Mongo layer).
