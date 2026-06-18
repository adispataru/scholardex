# H66B — Initialization Build + Fast/Correct Incremental Updates (Two-Tier Builder Design)

Status: **design + phase-1 started**. Companion to [h66b-entity-oriented-builders.md](h66b-entity-oriented-builders.md)
(M1–M10 + M8-B, full-feed validation PASS). This doc defines how the *same* canonical pipeline serves both the
one-time initialization build and ongoing incremental updates without rebuilding the whole registry.

## Problem

The full rebuild is validated, but the live system mutates continuously:
- a user requests fresh info from Scopus for an author (on-demand fetch),
- a user adds a paper manually (user-defined),
- a batch upload arrives (Scopus / publisher CSV),
- (planned) Publish-or-Perish / Google Scholar data is imported as a new source.

Each must be **fast** (sub-second per item, not a 60–90 min rebuild) and **correct** (the new data resolves to the
right canonical forum/author/citation and the reporting views update). Today four fold paths exist and diverge in
what they scope vs. recompute globally.

## Core principle: **Resolve vs. Reconcile**

The forum registry is expensive to *mutate* (every `ForumMergeEngine` context loads the whole registry; dedup is
inherently global union-find). But it is cheap to *resolve against*. So split every entity builder into two modes:

- **Resolve(delta)** — Tier 2, per update. Ingest the new records (batch-scoped), canonicalize them scoped to the
  batch, and resolve each item's forum/author/etc. against the **already-built, stable** registry by lookup;
  *optimistically mint* a canonical entity only when none exists. Cheap, runs on every update.
- **Reconcile()** — Tier 1, global. Dedup, ERIH/DOAJ/WoS onboarding, M9 identity dedup, membership dedup, M10
  relink. Makes the registry globally consistent. Runs at **init** and **periodically / on curated-feed change** —
  *not* per user update.

**Init build = `reconcile()` over everything. Incremental update = `resolve(delta)` + a *deferred* `reconcile()`.**

### Why this is correct (not just fast)

The correctness guarantee is the convergence property already **proven by the full-feed validation**: the reconcile
pass (dedup → M9 → M10) deterministically collapses transient duplicate forums and re-links stranded journals. So:
- Tier 2 is *optimistic resolve + mint*: a new paper binds to its venue if it exists; if not, it mints one forum
  (possibly a transient duplicate). Correct for the common case, cheap always.
- Tier 1 reconcile makes it globally consistent: any duplicate/ambiguity a Tier-2 mint introduced is exactly what
  dedup/M9/M10 already resolve.

This is eventual consistency **via the mechanism the rebuild already uses** — not a new correctness model.

## Current state (mapped against the model)

| path | entry point | canon | forum | projections | verdict |
|---|---|---|---|---|---|
| **Scopus on-demand / scheduler** | `ScopusUpdateScheduler` → `ScopusCanonicalMaterializationService.rebuildFactsAndViews(trigger, batchId)` | **scoped** (`sourceBatchIdFilter=batchId`) | **none** — resolves venue by source-link lookup in publication canon; service has *no* `ForumBuilder` dep | **scoped** (`rebuildViewsForBatch`) | ✅ **already Tier-2, and unit-tested** (`ScopusCanonicalMaterializationServiceTest`) — the proven reference |
| **Batch upload** | `IncrementalUpdateUploadFacade` → `ScopusBigBangMigrationService.runIncrementalUploadBuildStep(batchId)` | **scoped** | ⚠️ runs the **global** `buildScopusForums` (dedup + ERIH/DOAJ onboarding + membership dedup + M10) every upload | scoped (`rebuildViewsForBatch`) | ⚠️ **Tier-1 leaking into Tier-2** — the primary divergence |
| **User-added paper** | `UserDefinedCanonicalizationService.rebuildCanonicalFacts()` | **global, no delta** | global compare vs all forums | via materialization | ⚠️ fully global; also called *unconditionally* on every materialization (below) |
| **Full rebuild (init)** | `PipelineRebuildService.rebuildAllDerived` → `runFull` | full rescan | full `buildScopusForums` | full `rebuildViews` | ✅ this **is** Tier-1 reconcile-all |

Correction worth recording: an earlier read claimed the scheduler path ran canon *globally*. It does **not** — 
`runIncrementalBatchMaintenance` injects `batchId` as `sourceBatchIdFilter` (`buildIncrementalOptions`), so canon is
batch-scoped; only `runFullMaintenance` is global. The scheduler path is the working Tier-2 template.

### What's already built (reusable seams)
- Delta canon via `sourceBatchIdFilter` on all four canonicalization services (`findBySourceBatchId`).
- Per-batch projection refresh (`rebuildViewsForBatch`: upsert affected entities + delete/reinsert affected edges).
- Per-item venue lookup in publication canon (`resolveCanonicalForumId`); per-record `ForumMergeEngine.ingest`;
  scoped M10 (`relinkAmbiguousWosForums`).

### Gaps (the work)
1. **Upload path runs global `buildScopusForums`** — should resolve+mint only the batch's new venues, deferring the
   global reconcile. (Biggest win; needs the resolve/reconcile carve — Phase 2.)
2. **No explicit `reconcile()` entry** distinct from wipe-and-rebuild — needed so reconcile can run periodically /
   on curated-feed change without a full source re-ingest. (Phase 3.)
3. **User-defined canon is global + unconditional** on every materialization (re-canonicalizes *all* user-defined
   data even on a Scopus-only poll). (Phase 1 — scope it; safe because this service never rebuilds forums, so
   user-defined forum resolution can only change when user-defined facts change.)
4. ERIH/DOAJ/WoS onboarding always scan *all* source rows — fine for Tier-1; would need new/changed-only scoping if
   ever pulled into Tier-2 (not planned).

## The contract (target)

```
interface EntityBuilder {
    ResolveResult  resolve(Delta delta);   // Tier 2: scoped to new/changed records, resolves against stable registry
    ReconcileResult reconcile();           // Tier 1: global consistency pass
}
```
- `ForumBuilder.resolve(venues)` = per-item find-or-mint against the registry (no global dedup/onboarding).
  `ForumBuilder.reconcile()` = today's `buildScopusForums` (dedup → onboarding → M9 → membership dedup → M10).
- Ranking/Publication/Citation builders' `resolve` = the existing scoped canon; `reconcile` = full rescan.
- Every update path (scheduler, upload, user-add, PoP) calls `resolve`; a scheduler/threshold/feed-change trigger
  calls `reconcile`.

## Phased plan (low-risk, incremental)

1. **Phase 1 (this change): scope the user-defined canon out of the incremental path.** Skip the global
   `rebuildCanonicalFacts()` in incremental-batch materialization when the batch produced no user-defined facts
   (correct because the materialization service has no `ForumBuilder` dep → forums are stable across the run → a
   user-defined publication's forum resolution can only change when user-defined facts change). Keeps full
   maintenance unchanged. Proves the "scope global steps out of incremental" principle on the proven path.
2. **Phase 2 — DONE.** Carved `ForumBuilder.resolve(uploadBatchId, corr)` (Tier-2): builds the full-registry
   index once, then find-or-mints **only the batch's venues** (`runScopusForumCanonicalizationForBatch` filters
   `ScopusForumFact.sourceBatchId == uploadBatchId`), deferring dedup / ERIH-DOAJ onboarding / M9 / M10. Routed
   `runIncrementalUploadBuildStep` off the global `buildScopusForums` onto `resolve`. Minted + deferred-conflict
   counts are metered (`core.h66b.forum.unreconciled_mints` / `.deferred_conflicts`) — the Phase-3 trigger
   signal. `buildScopusForums` stays as the `reconcile()` entry (init + periodic). Tests: scoped canon ingests
   only batch venues; `resolve` never invokes dedup/ERIH/DOAJ/WoS; upload path calls `resolve` not the global
   build. App + scopus suites green (1018).
   - **Scoping confirmed:** books are unaffected (no book step in either path — they flow through `buildFacts` +
     `bookId` routing); deferred items are bounded and self-healing (forum dedup re-points publications +
     source-links on merge → no orphans, proven by the full-feed run's 0-orphan result after 188 merges).
   - **Still index-bound:** `resolve` still loads the whole registry once per upload for the find-or-match index
     (the chosen "index-load" option). If per-upload latency matters, the lighter per-venue indexed-lookup +
     mint-on-miss variant is the follow-up.
   - **LIVE-UPLOAD VALIDATION (2026-06-18, isolated `scholardex_h66`) — PASS.** Uploaded a real incremental
     Scopus file (798 papers + citing works = 1,620 pub facts) via `POST /admin/incremental-updates/scopus`,
     30 s end-to-end. `Forum resolve (incremental): processed=10 minted=10 matched=0 deferredConflicts=0` —
     and the log window confirmed **no ERIH/DOAJ onboarding, no global/membership dedup, no M9/M10 ran**. Health
     gate held: `healthy=true`, orphans **0**; forum_facts 69,933→69,943 (+10 = the mints); publications +1,613
     with **0** unresolved venues; OPEN forum conflicts unchanged at 57; prod `test` safe at 32,714. The 10
     mints are the deferred-reconcile backlog (the `unreconciled_mints` signal) and are harmless until the
     Phase-3 reconcile (0 orphans confirms resolve+mint is correct standalone).
3. **Phase 3 — DONE.** `ForumReconcileService.reconcile(reason)` is the single named Tier-1 op: the full
   `buildScopusForums` (dedup → canon → ERIH/DOAJ onboarding → WoS onboarding → membership dedup → M10) +
   `rebuildViews()` projection refresh (one call covers the forum metric/category/membership views, incl.
   DOAJ/ERIH). Invoked by **both** the admin manual trigger (`POST /admin/initialization/forum/reconcile`) **and**
   the nightly scheduler (`ForumReconcileScheduler`, `@Scheduled` cron `0 0 3 * * *`, `enabled` flag + in-flight
   guard) — identical code path. Measured cost on the 70k-forum registry: ~70 s forum pass + ~187 s full
   projection rebuild; cold-path, so full (not scoped) projections are acceptable. Runs **unconditionally** each
   tick (reconcile is idempotent) rather than gating on the monotonic `unreconciled_mints` counter (which can't
   reset) — a resettable/durable threshold gate is a future refinement. Tests: reconcile runs forum-build then
   projection in order; scheduler runs when enabled / skips when disabled. App + scheduler suites green (851).
   - **MANUAL-RECONCILE VALIDATION (2026-06-18, isolated `scholardex_h66`) — PASS.** Triggered `POST
     /admin/initialization/forum/reconcile` against the registry still carrying the 10 Phase-2 mints. ~228 s
     (forum pass + full projection rebuild). `membershipDedupMerged=12` — collapsed the transient duplicate
     forums the upload minted and re-onboarded ERIH (11) / DOAJ (114) membership; forum_facts 69,943→69,932,
     `healthy=true`, orphans **0** (merges re-pointed publications), upload-batch pubs **0** unresolved, OPEN
     conflicts unchanged at 57, projection errors 0, prod `test` safe at 32,714. **End-to-end two-tier loop
     proven:** Tier-2 resolve mints fast on the hot path → Tier-1 reconcile merges + re-points on the cold path,
     0 orphans throughout — the convergence the design promised, demonstrated on real data.
4. **Phase 4: two purpose-built multi-source ingests — OpenAlex (citations) + DBLP (CS venue identity).**
   Originally scoped as "PoP / Google Scholar as a Tier-2 source," **dropped after empirical evaluation** (below).
   Both new sources slot into the Tier-2 `resolve(delta)` hot path via a `ForumSourceRecord.ofX` mapper + an
   ingest adapter; new venues mint, reconcile cleans up. No orchestrator surgery.

   **Why PoP / Google Scholar was dropped (evidence, 2026-06-18).** Evaluated a real Google-Scholar export of
   one author (25 pubs + 114 citing works) against the OpenAlex API for the same corpus:
   - **No citation edges.** The GS bulk "retrieve citing works" merges every paper's citers into one flat,
     untagged list — you cannot recover *which* citing paper cites *which* of yours. The only per-paper
     grouping (the `cites=<clusterId>` in the pub export) is dropped. Edges are unrecoverable without a
     per-cluster re-scrape.
   - **Inflated + unverifiable.** The 114 GS citing records contained ~13+ near-duplicate pairs (preprint+
     published, mirror copies, encoding-glitch variants) and only **30/114 carried a DOI**. Distinct ≈ 95–100.
   - **OpenAlex is the cleaner, *more correct* picture** despite a smaller headline number (95 distinct citers,
     all DOI-backed): it agreed with **25/30** of GS's verifiable citers and added ~70 more verifiable ones;
     the GS "extra" is duplication + DOI-less noise (GS keeps a tiny ~5-paper fresh/grey-lit tail OpenAlex
     lacks — real but not worth the noise). GS is fragile to scrape (CAPTCHA/ToS) on top.

   **Phase 4a — OpenAlex as the on-demand citation + coverage source.** Free API, no key (~271M works). Mirrors
   the proven Scopus on-demand shape (`ScopusUpdateScheduler` → fetch → ingest with a batch id → Tier-2
   materialize): an OpenAlex fetch task (by ORCID / author / DOI) → ingest works with `referenced_works`
   (outgoing edges) + `cited_by` (incoming edges) → publication/citation canon → `resolve` the venues
   (DOI/ISSN = a *tight* mint gate, unlike GS) → reconcile on the cold path.
   - **Validated on a real corpus (2026-06-18):** all 25 of the test author's works present; **22/25** carried
     `referenced_works` (the 3 zeros are 2025 preprints); incoming `cites:` resolves to DOI-bearing citers;
     **24/25 DOI**, 13/25 venue ISSN (the rest are conferences — correctly no ISSN, match by DOI); venues clean.
   - **Build notes:** new `ofOpenAlex` source-record (OpenAlex work id + DOI as the match keys); citation edges
     flow into the existing citation canon (OpenAlex IDs/DOIs → resolve cited/citing pubs). **Caveat:** OpenAlex
     author disambiguation is fragmented (the test author split across 4 author entities) — key the request on
     **ORCID** and/or resolve by **work DOI/ID**, not OpenAlex's author clustering.
   - **OpenAlex does NOT recover CS conference identity** (tested): it files LNCS conference papers (Euro-Par,
     ESOCC, ICA3PP) as `type=book-chapter` under "Lecture Notes in Computer Science" (`source.type=book series`),
     the same blind spot as Scopus, with no DBLP link. → that job is Phase 4b.

   **Phase 4b — DBLP as a first-class CS venue-identity source (retire the band-aid).** Today
   `DblpPublicationEnrichmentService` (777 LOC) streams the *entire* gzipped DBLP dump every run — to enrich
   only `subtype∈{ch,cp}` pubs in "Lecture Notes in …" forums — after disabling the JVM's XML-bomb limits
   (`jdk.xml.maxGeneralEntitySizeLimit`/`totalEntitySizeLimit`/`entityExpansionLimit`=0) and hand-rolling a
   77-entry HTML-entity sanitizer, then writes the result to a side `scholardex.publication_dblp_evidence`
   collection read by exactly one consumer (`ComputerScienceConferenceScoringService`). It is a write-only
   oracle bolted on sideways. **But DBLP is not redundant** — it is the *only* source in the stack that knows
   the real conference behind an LNCS DOI (Scopus and OpenAlex both bury it under the series). So promote it,
   don't delete it:
   - **Detect candidates without DBLP** — the "hidden conference" signal is already in Scopus/OpenAlex:
     `book-chapter` + `source.type=book series` + ISSN `0302-9743` (LNCS/LNAI/CCIS family).
   - **Resolve via the DBLP API per paper** (`dblp.org/search/publ/api?q=<doi|title>`, JSON) — the matching
     `inproceedings` record carries the real `booktitle`/conference + proceedings crossref. Same Tier-2 shape;
     no 4 GB dump, no XML wrestling.
   - **`ForumSourceRecord.ofDblp`** mints/matches the real **conference forum** (keyed by DBLP venue stream
     `conf/X` + ISSN); the LNCS paper resolves to it; CS conference scoring reads the **resolved forum**, not an
     evidence note. **Retire** `publication_dblp_evidence`, the XML-bomb switch, the 77-entity sanitizer, and the
     `/general/dblpLnChapterEnrichment` batch.
   - **Modeling decision:** a DBLP `conf/X` stream is a conference *series* — make the forum the series (year
     editions become a publication attribute), mirroring how journal series are already handled.
   - **Scope honesty:** DBLP is **CS-only** and has **no citation edges** — it is the CS venue-identity
     authority, complementary to OpenAlex, not a replacement. Keep the full dump only for an optional one-time
     CS venue-catalog seed; make the live path the API.

   **Division of labor (zero overlap):** OpenAlex = citation edges + dedup + broad coverage; DBLP = CS
   conference identity; Scopus + WoS = the metadata/metrics backbone; ERIH/DOAJ/MJL/SourceList/CiteScore = the
   curated identity/membership feeds.

## Open questions
- **Reconcile trigger policy** — nightly? after N unreconciled mints? on curated-feed import? (Phase 3 decides.)
- **Registry index warmth** — Tier-2 resolve still pays an O(registry) context load unless we keep a warm
  ISSN→forum index or resolve purely by indexed Mongo lookup (the scheduler path already does the latter via
  source-links). Prefer indexed lookup over an in-memory warm index (multi-instance coherence).
- **~~PoP identity quality~~ — RESOLVED: dropped Google Scholar/PoP** (no edges, inflated/duplicated, fragile).
  Replaced by OpenAlex (Phase 4a) + DBLP (Phase 4b). See Phase 4.
- **OpenAlex author fragmentation** — one real author maps to several OpenAlex author entities; an on-demand
  "update by author" must key on ORCID and/or resolve by work DOI/ID, not OpenAlex's author clustering.
- **DBLP venue-stream → forum modeling** — confirm conference *series* (`conf/X`) is the forum grain (year
  editions as a pub attribute), and how DBLP `conf/X` keys reconcile with existing Scopus/SourceList conference
  forums (shared ISSN where present; name+series otherwise).
- **Per-source mint-confidence gate** — OpenAlex/DBLP are DOI/stable-ID-backed (tight gate OK). The general
  question of how strict the Tier-2 mint should be per source (and whether low-confidence sources defer minting
  to reconcile) is now scoped to *high-confidence* sources only.
