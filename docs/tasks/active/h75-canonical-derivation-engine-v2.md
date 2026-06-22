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

## Reframe (2026-06-22): V1's DB is not an oracle — move fast, validate by invariants

We never had a "correct" canonical DB. V1 is slow, unvalidated code that ran on a maybe-dirty DB; **byte-parity with
V1 is the wrong gate**. What's trustworthy is the **rules** (the identity/dedup logic the user designed across
H72/H73) — those are the spec, and we own them (free to fix/improve). What's NOT trustworthy is V1's **output**.

So: **drop build-alongside + the feature flag + the V1-differential gate.** Build V2 as the real pipeline, wire each
entity straight into the rebuild replacing V1's step, and validate by:
1. **Unit tests on the rules** (the spec) — not V1 parity.
2. **Output invariants** — referential integrity (no orphan edges), id schemes (`spub_`/`sauth_`/`saff_`/`sforum_`),
   no duplicate natural keys, counts in sane ranges.
3. **Determinism** — two V2 runs identical (`CanonicalSnapshot` still does this; repurposed from V1-diff to
   self-consistency + before/after experiment comparison).
4. **Spot-checks + "run it and look"** — the real unlock: once V2 runs in minutes (not 90), eyeballing UVT / a known
   author / a shared-DOI pub becomes the validation loop we never had. Speed *is* the validation enabler.

The DB is disposable — everything is an experiment; rebuild from source facts at will. The
`AffiliationDerivationV2DifferentialTest` is kept but reinterpreted: it asserts V2 implements the *documented rules*
consistently with V1's logic-as-reference, NOT that V1's data is correct. No more new V1-parity tests.

## Identity-rules catalog (ported from V1's logic — the spec, owned and improvable)

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

## Delivery stages (REVISED 2026-06-22 — build V2 as the real pipeline, no build-alongside)

Validate by rules-unit-tests + invariants + determinism + fast-run inspection (see the Reframe). No V1 flag, no
byte-parity gate, no cutover ceremony — wire each V2 entity into the rebuild replacing V1's step, run fast, iterate.
Prioritize by **cost** (attack the 60% before the 10%): the OpenAlex/Scopus **canon (pubs+authors+edges) ≈ 60%**
of the 90 min; forums ≈ 10% and are already in-memory.

- **Stage 0 — DONE.** Design + `CanonicalSnapshot` (now: determinism + invariants, not V1-diff) + rules catalog + baseline.
- **Stage 1 — affiliations DONE (V2).** Forums: the engine is already in-memory; a quick write-batch optimization
  (defer per-new-forum saves, share the registry across sources) makes it fast — behavior-preserving, low priority.
- **Stage 2 (the prize) — pubs + authors + edges in V2.** The in-memory canon: DOI-keyed pubs (field precedence),
  the union-find author build (ORCID + positional bridge + fuzzy/over-split reconcile folded in), and all edges.
  Validate by invariants (no orphan edges; id schemes; sane counts) + spot-checks on UVT/known authors.
- **Stage 3 — citations + wire the full V2 rebuild + run.** Citation edges; replace the V1 canon block in
  `runFull`/`rebuildAllDerived` with the V2 engine; run end-to-end (target < 10 min) and inspect. Projections run on
  top unchanged. Delete the superseded V1 canon + per-record "bulk mode" once V2's run looks right.

## Decisions settled
- **Store stays Mongo** (single-node in-memory build is enough at this scale — no Spark/columnar).
- **Build-alongside + differential validation** (not in-place replacement).
- **Full-rebuild only** for V2 initially; incremental Tier-2 stays on V1 until later.
- **Scope boundary excludes** WoS ingest + Postgres projections (V2 stops at the canonical Mongo layer).
- **OpenAlex institutions become a source fact (2026-06-22).** Today the bulk importer derives the `saff_<ror>`
  affiliation backbone *during ingest*, writing it straight into the wiped `scholardex.affiliation_facts`. V2 wipes
  that collection and the `.gz` source isn't in Mongo. So: add `openalex.institution_facts` (importer writes raw
  referenced institution records there — non-breaking, in *addition* to the current backbone during build-alongside),
  and V2's affiliation build derives the backbone from it. The backbone becomes a real derivation; V2 stays
  pure-from-Mongo. At cutover the importer's backbone-write is removed (V2 owns it).

## Stage 1 breakdown (sub-sliced; start with the cleanest self-contained entity)

Dependency order is forums → affiliations → pubs, but the skeleton is proven fastest on **affiliations** (no
create-or-match graph, and it exercises the just-decided institution source fact + the bulk writer + the differential
harness end-to-end). Then forums, then pubs.
- **S1.0** `openalex.institution_facts` source fact (model + repo) + importer writes it (referenced-only, additive).
- **S1.a** Affiliations: `CanonicalSourceLoader` (institution + Scopus-affiliation sources) → `CanonicalGraphBuilder.buildAffiliations` (ROR backbone + reuse `ScopusAffiliationRorMatcher` 3-tier + verified-only) → `BulkCanonicalWriter` (wipe → insertMany → ensureIndexes) behind `rebuildCanonicalV2`; differential-validate `affiliation_facts` + its source_links vs V1.
- **S1.b** Forums: port `ForumMergeEngine` identity + create-or-match + dedup + primary-ISSN/cross-journal rules; differential-validate `forum_facts`.
- **S1.c** Publications (minus `authorIds`): DOI-first id + Decision-0 blocklist + field precedence + forumId/affiliationIds resolution; differential-validate `publication_facts` (authorIds/pendingAuthorSourceIds excluded until Stage 2).
Determinism: V2 processes source facts in V1's sort order (pub by `eid`+`sourceRecordId`, author by `authorId`).
