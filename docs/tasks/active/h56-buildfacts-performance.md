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
   - **The real remaining win for author canon is a no-op skip on the *facts*** (don't re-write the ~5,000
     unchanged author docs at all → the 4,578ms would approach 0), i.e. extend lever-2's idea from source
     links to canonical facts. That is the higher-risk lever (≈30-field comparison / staleness): needs a
     robust unchanged-check (content hash + builderVersion gate) so a real change is never skipped.
   - Publication canon is unaffected by lever 3 (resolve-bound, ~299s) and its fact write is small.
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
