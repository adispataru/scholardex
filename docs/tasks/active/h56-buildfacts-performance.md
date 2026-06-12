# H56 — buildFacts pipeline performance

**Status:** Scoped 2026-06-12 (not yet implemented)
**Motivation:** the Scopus `buildFacts` rebuild takes ~25–28 min; we re-ran it many times during H55. The
cost is concentrated and largely avoidable.

## Where the time goes (measured on the live `test`/`core` run)

| Stage | Time | Notes |
|---|---|---|
| scopus-fact-builder | ~4.2 min | rebuilds 246k import events → scopus facts |
| affiliation canon | ~17 s | 28.6k |
| author canon | **~6.7 min** | 216k authors + ~280k author-affiliation edges |
| forum dedup + canon | ~11 s | — |
| **publication canon** | **~16 min** | 92.7k pubs + 658k authorship + 271k pub-author-affiliation edges |
| citation canon | ~1 min | 153k |

Per-chunk timing for publication canon (1,000 pubs): `authorshipEdges=1777ms + pubAuthAffEdges=1906ms`
vs `publicationFacts=315ms`, `sourceLinks(pub)=228ms`. **~87% of publication-canon time is edge writing.**

## Root cause (code-confirmed)

The edge write cost is dominated by **edge source links**, not the edge facts:

1. **Edge source links are not preloaded.** `ScholardexPublicationCanonicalizationService.preloadChunkContext`
   preloads source links for `AUTHOR`, `AFFILIATION`, `PUBLICATION`, `FORUM` only (lines ~305-308). It does
   **not** preload `AUTHORSHIP` or `PUBLICATION_AUTHOR_AFFILIATION` links. The edge writer
   (`ScholardexEdgeWriterService.batchUpsert*Edges`) calls `sourceLinkService.batchUpsertWithState(linkCommands,
   context.sourceLinkCache, allowFallbackLookup=true)` — and that cache holds only the entity links. So every
   edge source link misses the cache and hits the per-item fallback `findByKey`
   (`ScholardexSourceLinkService.batchUpsertWithState` lines ~375-376). That is **~658k + ~271k ≈ 930k
   individual Mongo reads** in publication canon (plus ~280k more in author canon for author-affiliation links).
2. **No no-op skip on unchanged links.** `batchUpsertWithState` re-assembles and `pendingSaves.put`s **every**
   command (lines ~443-451) with no "existing already equals incoming → skip" check. On a re-run of unchanged
   data (every iteration we did), all ~1.2M+ source links are re-written even though nothing changed.
3. **`saveAll`, not a bulk op.** `pendingSaves` is flushed with `sourceLinkRepository.saveAll(...)` (line ~455),
   which for entities with ids performs individual upserts, not one `BulkOperations` round-trip. (The edge-fact
   writer already uses `BulkOperations` at `ScholardexEdgeWriterService` ~222; the source-link writer does not.)

The edge *facts* themselves are preloaded (`findByPublicationIdIn`) and bulk-written, so they are not the
problem — the **edge source-link read+write path** is.

## Proposed levers (by impact / confidence)

1. **Preload edge source links per chunk — DONE 2026-06-12, behaviour-preserving.** `preloadAuthorshipEdges`
   and `preloadPublicationAuthorAffiliationEdges` now also collect the edge `sourceRecordId`s and call
   `preloadSourceLinks(AUTHORSHIP|PUBLICATION_AUTHOR_AFFILIATION, …)`, seeding `context.sourceLinkCache` so the
   edge writer resolves links from cache instead of a per-edge `findByKey`. (Author canon already did this for
   AUTHOR_AFFILIATION links — lines ~266-270 — which is why it was already ~2.5× faster per record; this brings
   publication canon to parity.)
   **Measured (clean rebuild, errors=0, processed/updated 92,694 — identical behaviour):**
   - publication canon **952,700ms (~15.9 min) → 627,063ms (~10.5 min)** = **−34% (−5.4 min)**.
   - total `buildFacts` **~27.7 min → ~22.3 min** (−19%).
   - Per-chunk: `authorshipEdges 1777→1447ms`, `pubAuthAff 1906→1725ms`.

   **Refined finding:** the per-edge `findByKey` **reads** were only ~20% of edge time. The dominant remaining
   cost is the source-link **writes** — `saveAll` issuing ~930k per-document upserts and re-writing even
   unchanged links. So **levers 2 + 3 are now the bigger win**, especially lever 2 on the common re-run case
   (this rebuild changed nothing yet still re-wrote every link).
2. **No-op skip on unchanged source links — DONE 2026-06-12.** Both `batchUpsertWithState` and the single-item
   `upsertWithState` now skip the write when a *persisted* link already carries the same durable content
   (linkState, effective canonicalEntityId, linkReason). Provenance-only diffs (batchId/eventId/correlationId)
   do not trigger a rewrite — no `pendingSaves`, no `updatedAt`/provenance churn (also better for determinism).
   Unit-tested both directions (identical replay skips; `UNMATCHED→LINKED` still writes); determinism +
   scopus/onboarding suites green.
   **Measured (clean re-run on top of the lever-1 DB; only provenance differs, so ~all links skip; errors=0,
   processed/updated 92,694 — identical behaviour):**
   - publication canon **627,063ms → 306,079ms** (and **952,700ms → 306,079ms = −68% vs the pre-H56 baseline**).
   - author canon ~387,000 → **278,449ms**; citation ~53,000 → **23,364ms**; affiliation 15,653 → **9,450ms**.
   - **total `buildFacts` ~28 min → ~14.5 min (−48%, roughly halved).**
   - Per-chunk edge writes collapsed: `authorshipEdges 1447→47ms`, `pubAuthAff 1725→57ms`, `sourceLinks 217→5ms`.

   **Remaining cost** in publication canon is now the publication *fact* re-writes (`publishUpdate ~305ms/chunk`,
   `updated=92,694` every run) plus preload queries + per-pub in-memory processing. A future lever could extend
   the no-op skip to the canonical *facts* (publication/author/affiliation) too, not just their source links.
3. **Bulk fact writes — DONE 2026-06-12 (author facts), modest.** `ScholardexAuthorCanonicalizationService`
   now persists chunk author facts via `mongoTemplate.bulkOps(UNORDERED).replaceOne(...)` instead of
   `saveAll` (one round trip vs ~5,000), preserving the duplicate-key → per-record recovery fallback
   (catches `DataIntegrityViolationException`). Behaviour-identical; determinism + scopus suites green.
   **Measured: author canon 278,449ms → 240,353ms (−14%); total `buildFacts` ~14.5 → ~13.7 min.**
   - **Smaller than predicted, and the measurement corrected the model:** `saveAll` was *server-write*-bound,
     not round-trip-bound. The chunk `upsert` only dropped `5591→4578ms` — `bulkOps` collapsed the network
     round-trips (~18% of the write) but **not** the per-doc server-side re-write (~0.9ms/doc × 5,000 docs).
   - Publication canon is unaffected by lever 3 (resolve-bound, ~299s) and its fact write is small.

4. **Fact no-op skip (author facts) — ATTEMPTED then REVERTED 2026-06-12. Verified correct but marginal;
   its real value was a corrected diagnosis.** Prototyped a persisted `contentHash` on `ScholardexAuthorFact`
   plus a flush-time skip (skip the write when the content hash matches the stored one and builderVersion
   already matches). Live two-run verification proved it works and is safe — run-2 author chunks wrote
   `authorFacts=0` with the row count unchanged at 216,470 and errors=0 — and it was unit-tested (hash
   excludes provenance/timestamps, detects every content change) + determinism-green.
   **But the win was only -8% (author canon 240,353 -> 221,335ms; total ~13.7 -> ~13.1 min)**, which exposed a
   misdiagnosis: after lever 3 the fact write was already a single cheap bulk op, so skipping it saved only
   ~178ms (chunk `upsert` 4578->4400ms). The ~4,400ms `upsert` is actually the **source-link + edge batch
   *processing*** (~5,000 author source links + ~6,388 author-affiliation edges per chunk), not fact writes:
   **author canon is command-processing-bound, not write-bound.**
   **Reverted** because the persisted `contentHash` and the obligation to keep its hash comprehensive
   (staleness footgun if a field is added without updating the hash and bumping builderVersion) was not worth
   ~19s/run. (A prior run stamped `contentHash` onto the live author docs; the field is now orphaned -
   harmlessly ignored by Spring on read and dropped on the next full rebuild's replaceOne.)

## Lever 5 — author-canon cache-miss fixes (DONE + MEASURED 2026-06-12)

**Author canon 248,849ms → 48,570ms (−80%); chunk `upsert` 4,841ms → 319ms (−93%).** The "command-
processing-bound" reading from lever 4 was *also* wrong. Instrumentation (cache hit/miss/fallback
telemetry added to `batchUpsertWithState` and the edge-writer batch loops — kept as permanent telemetry,
logs only when fallbacks occur) showed **100% cache miss** on both preload caches: `commands=5000
cacheHits=0 fallbackLookups=5000` per chunk → ~216k link + ~271k edge per-command Mongo reads ≈ 139s of
the 249s stage, plus `pendingSaves=6388`/chunk of redundant edge re-writes. Three root-caused defects,
all fixed:

1. **Synthetic placeholder clobbering** — `queueSourceLinkCommand` overwrote the preloaded *persisted*
   link in `context.sourceLinkCache` with an id-less synthetic under the same key; the flush-time batch
   treats id-less entries as unresolved and falls back to `findByKey` per command. Fix: `putIfAbsent`
   (the synthetic only fills holes where nothing is persisted yet).
2. **Edge natural-key normalization mismatch** — author canon seeded `authorAffiliationEdgeByNaturalKey`
   with lowercased `normalizeToken` keys while `ScholardexEdgeWriterService` looks up with its
   case-preserving `normalize` (`…|scopus_json_bootstrap` vs `…|SCOPUS_JSON_BOOTSTRAP`) → every lookup
   missed. Fix: the writer now exposes `authorAffiliationEdgeNaturalKey(...)` as the single key
   authority; the preload uses it.
3. **Unconditional author-affiliation edge re-save** — unlike the authorship batch, the AA batch ran
   `applyLineage` + `pendingSaves.put` for every command, re-writing ~271k edges (and bumping
   `updatedAt`) per replay. Fix: gate on `created || isLineageChanged(...)`, mirroring authorship.

Verified: zero fallback lookups post-fix, errors=0, processed/updated 216,470, edge/fact/link counts
unchanged, determinism integration test green.

## Lever 6 — publication-resolve normalized cache key (DONE + MEASURED 2026-06-12)

**Publication canon 295,183ms → 127,388ms (−57%); resolve 2,125ms → 38ms/chunk (−98%).** The same
instrumentation pass on publication canon's `findSourceLink` cache wrapper showed the same disease,
fourth variant: **596,657 fallback `findByKey` reads per run, 99.98% of which FOUND the link** (163s of
the 295s stage). Root cause: resolve probes (`resolveAuthorSourceLink` / `resolveAffiliationSourceLink` /
`resolveCanonicalForumId`) pass the **raw fact source** (`SCOPUS_JSON_BOOTSTRAP`) while the preload seeds
the cache under the stored **normalized** source (`SCOPUS`) — every first probe per key missed, paid a
Mongo read (findByKey normalizes internally, hence "found"), and re-cached under the raw key; the
per-chunk context reset repeated this every chunk. Fix: `findSourceLink` now normalizes the source via
`sourceLinkService.normalizeSource(...)` before building the cache key, aligning it with both the preload
and findByKey. Post-fix: ~7 fallbacks per full run (genuinely-absent keys). The per-chunk cache-efficiency
telemetry stays in (logs only when fallbacks occur). Tests updated where mocks modeled raw-source stored
links (impossible live); determinism green.

## Lever 7 — fact-builder lineage-gated replay saves (DONE + MEASURED 2026-06-12)

**fact-builder 239,022ms → 130,414ms (−45%).** The unchanged-payload branches at all six fact types
(publication / citation / forum / author / affiliation / funding) called `refreshLineageForReplay` and
**unconditionally re-added the fact to `pendingSaves`** — on a same-ledger replay `applyLineage` writes
identical values, so ~590k byte-identical documents were re-saved per run (~85% of the stage). Fix:
`refreshLineageForReplay` now compares the five lineage fields and returns whether anything changed;
callers only enqueue the save when it did (`HasLineageFields` gained the getters; all implementors are
Lombok `@Data`). Verified: errors=0, counts identical, determinism green.

**Residual (flagged, deliberately not changed):** ~929 writes per 1,000 events remain — the lineage
"ping-pong" of shared dimension facts: an author/forum/affiliation fact touched by many publications has
its lineage re-stamped by an earlier event each run, then re-stamped back by the last one. The end state
is deterministic, but every replay re-does the churn. Eliminating it means a *semantic* decision —
lineage = "last event that touched the fact" (today) vs "last event that *changed* it" (no replay
writes) — which interacts with batch-scoped incremental processing (`sourceBatchId` stamping selects
facts for incremental canonicalization). Decide separately if fact-builder time still matters.

## Final H56 result (capstone full-rebuild measurement, 2026-06-12)

| Step | pre-H56 | final |
|---|---|---|
| fact-builder | ~239s | 130s |
| affiliation canon | ~17s | 9s |
| author canon | ~410s | **52s** |
| forum dedup+canon | ~12s | 12s |
| publication canon | ~953s | **123s** |
| citation canon | ~58s | 21s |
| **total buildFacts** | **~28 min** | **5.8 min (−79%)** |

errors=0 across all steps; fact counts identical (92,694 scopus pubs / 216,470 authors / canonical layers
matching); rebuild-twice determinism green throughout.

**Pattern that drove ~all of it (5 of 5 root-caused defects):** a preload/cache or no-op check whose
*seeding/comparison* and *lookup/write* sides were built by different code drifted into silent
per-record DB work — 100%-miss caches (levers 1, 5, 6), no-op-less rewrites (levers 2, 7). The durable
guards now in place: single key-authority methods owned by the consumer, and fallback-counting telemetry
in the source-link batch, edge batches, and publication resolve (silent unless drift recurs).

## Levers 8–10 (DONE 2026-06-12): content gates, hash/metrics micro-fixes, stage-skip gate

8. **Fact-builder content gates + blank-tolerant merges.** The residual replay writes were
   *payload-variant ping-pong*: the per-event hash reflects THIS paper's view of a shared dimension
   entity, so multi-variant authors/affiliations/forums re-took the full update path every replay. Fixes:
   author updates now skip the save when the merge changed nothing; affiliation (name/city/country) and
   forum (name/issn/eIssn/aggregationType) updates became **blank-tolerant** (a paper omitting a field no
   longer erases a value learned from another paper) with the same content gate; funding gated trivially
   (key == content). **Measured steady-state (run 2): fact-builder 130,414 → 113,630ms; update churn 929
   → 170 per 1k events.** The remaining 170 are *conflicting non-blank values* across papers (e.g. two
   city spellings) which still alternate under latest-non-blank-wins; converging them would need
   first-non-blank-wins, which would also block genuine corrections — accepted as residual. The
   blank-tolerant merge proved **precautionary, not corrective**, on this dataset (blank city/country
   counts unchanged at 1001/159 — no erasure was actually occurring). Existing fact-builder tests that
   asserted unchanged authors are still re-saved were updated to assert preservation-without-rewrite.
9. **shortHash/metrics micro-fixes.** `CanonicalizationSupport.sha256Hex` now uses a per-thread
   `MessageDigest` + hex-table encoding (~2.5M calls/run were each creating a digest and running 32×
   `String.format("%02x")`); the edge-writer/WoS private copies and the fact-builder's `hashKey`
   delegate to it. **Byte-identical output pinned by test** (NIST `sha256("abc")` vector + a verbatim
   copy of the legacy implementation as oracle) — these feed persisted ids, so output drift would be
   catastrophic. `CanonicalObservabilityMetrics` now caches `Counter` handles (~1.2M+ per-command
   registry lookups avoided).
10. **Stage-skip gate (`ScopusBuildSkipGateService`).** Opt-in `buildFacts?skipUnchanged=true`: a
    fingerprint of the pipeline inputs — `scopus.import_events` and `scholardex.forum_facts` (count +
    max `updatedAt`) plus all `BuilderVersion` constants — is recorded after every errors=0 run
    (state doc `scopus.pipeline_state`); when unchanged, the whole pipeline is skipped. **Sound only
    because levers 2/5/7/8 made unchanged replays write-free** (derived `updatedAt` no longer churns —
    the canonical-forums fingerprint stayed at its June-11 value across all subsequent replays).
    Deliberately opt-in: operator-triggered runs (e.g. after conflict resolution) use the default and
    always execute. **Live-verified: a no-change `skipUnchanged` run returns in ~1s** with
    "orchestration skipped: inputs unchanged" logged.

**End state: full rebuild ≈ 5.5 min (from ~28, −80%); no-change replay with `skipUnchanged=true` ≈ 1s.**
Remaining accepted residuals: ~170 conflicting-non-blank variant writes per 1k events in the
fact-builder; per-stage (rather than whole-pipeline) skip granularity if ever needed.

**Pattern worth naming (4 of 4 defects were this):** every preload/cache pair whose *seeding key* and
*lookup key* are built by different code drifted into silent 100% miss + per-record fallback reads. The
durable guard is (a) single key-authority methods owned by the consumer (as done for the edge writer) and
(b) the fallback-counting telemetry now in place, which makes the next drift loudly visible.
4. **Fast-iteration build path (low risk, big iteration win).** When only one stage's logic changed, re-run only
   the affected canonicalization stage(s) instead of the full `buildFacts`. Today forum dedup/canon are wired
   only into `buildFacts`, and `buildCanonical` runs specific entities but not forum/dedup. Add a selective path
   (e.g. `/scopus/buildCanonical?entities=forum,publication` that also runs dedup) and skip `scopus-fact-builder`
   when import events are unchanged (their facts are a deterministic function of events). For H55.6 we paid the
   full ~25 min when ~16 (forum+publication) would have sufficed.
5. **Larger publication-canon chunk size (low risk, modest).** ~1,000 → larger reduces per-chunk preload/txn
   overhead. Edge writes are per-record so the gain is bounded; pair with lever 1.

## Verification

- Wall-clock before/after on the full rebuild (target: publication canon from ~16 min down materially; total
  `buildFacts` well under 25 min — most of the win from levers 1+2).
- **Determinism must hold**: reuse the H54.7 rebuild-twice byte-identical integration test + a full rebuild that
  reproduces row counts and the H55 ISSN-resolution parity. Levers 1 and 3 are behaviour-preserving; lever 2
  must be proven a true no-op (no spurious `updatedAt` churn).

## Notes

- The author-canon edge path (~280k author-affiliation source links) has the same un-preloaded pattern — apply
  lever 1 there too.
- Entity source links (author/affil/pub/forum) are preloaded (no fallback read) but still re-written every run;
  lever 2 helps them as well.
