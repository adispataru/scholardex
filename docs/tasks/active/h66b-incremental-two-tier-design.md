# H66B — Initialization Build + Fast/Correct Incremental Updates (Two-Tier Builder Design)

Status: **design + phase-1 started**. Companion to [h66b-entity-oriented-builders.md](h66b-entity-oriented-builders.md)
(M1–M10 + M8-B, full-feed validation PASS). This doc defines how the *same* canonical pipeline serves both the
one-time initialization build and ongoing incremental updates without rebuilding the whole registry.

> ⚠️ **Operating rule — ALWAYS trace the code path before kicking off a rebuild.** A full rebuild on
> `scholardex_h66` is ~28 minutes. Before firing one to validate a change, confirm the entry point you're testing
> (`rebuildAllDerived` → `PipelineRebuildService.rebuildAllDerivedFromSource` → `ScopusBigBangMigrationService.runFull`)
> actually invokes the code you edited — `runFull` calls the canonicalization builders **directly** and bypasses
> `ScopusCanonicalMaterializationService.rebuildFactsAndViews`, so a hook wired only into the latter never runs in a
> full rebuild. We burned a 28-min rebuild on exactly this (Phase 4a OpenAlex replay; fix `55843e8`). Grep the
> rebuild call chain for your new method first; the cost of a wrong path is half an hour, the cost of the check is
> 30 seconds.

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

   ### Phase 4 — design adjustments after code inspection (2026-06-18, decisions locked)

   **Code seams found:** `ForumSourceRecord` (enum + `ofX` mapper + `addExternalId` FK-list switch) extends
   cleanly → add `OPENALEX`/`DBLP` id types + `openAlexIds`/`dblpIds` forum FK lists. The canonical publication
   id is a **priority cascade** `eid → wos → gs → user → doi → title+date+creator+forum` (DOI is first-class; a
   vestigial `googleScholarId` slot remains from the dropped GS plan — clean it up). Cross-source bridging by DOI
   **already exists**: `PublicationEnrichmentLinkerService` resolves an external pub onto the canonical one by
   EID then DOI (`findAllByDoiNormalized`; 1→link, >1→`AMBIGUOUS_DOI_MATCH`). **But citation linking is
   EID-keyed** (`ScholardexCitationFact` is canonical-id-keyed, yet source resolution is `citedEid`/`citingEid`
   only) — OpenAlex DOI/ID edges need a DOI-resolution path. The on-demand trigger is
   `ResearcherWorkspaceController` → researcher-profile `scopusId` → `ScopusPublicationUpdate` →
   `UserScopusTaskFacade` → `ScopusUpdateScheduler`; **the profile has no ORCID**.

   **Decision 0 — DOI-primary publication identity (do FIRST, before OpenAlex).** Reorder the
   `buildCanonicalPublicationId` cascade from the legacy Scopus-first `eid → wos → gs → user → doi → title+date`
   to **`doi → eid → wos → user → title+date`**. Single derivation point, two callers (publication +
   user-defined canon) — a tiny code change with a large architectural payoff: a paper with a DOI gets the **same
   canonical id across every source**, so Scopus/WoS/OpenAlex/DBLP records of one paper unify *intrinsically* at
   canonicalization — **this collapses most of the "publication dedup-by-DOI" surface** Decision 1 would
   otherwise need. DOI-less papers keep the `eid → title+date` fallback (still source-fragmented — the unaffected
   tail).
   - **Why now:** the blast radius is *tiny today and huge later*. Pre-change diagnostic on `scholardex_h66`:
     94,171 canonical pubs (87,098 with DOI re-key; 7,073 DOI-less unaffected); only **88 DOIs map to >1 canonical
     pub (≈91 pubs, 0.1%)** — flip the identity now while that's the merge volume, not after OpenAlex/DBLP make it
     tens of thousands of cross-source duplicates.
   - **False-merge guard (the one genuinely-new piece) — must be FUZZY-title, not exact.** A naive
     "different normalized title → block" would wrongly quarantine legitimate same-paper records (big-collaboration
     papers have subtitle/record variants). Empirical acceptance test from the 88 groups: **70 share one title +
     9 are fuzzy-similar variants → MERGE; 9 are truly different papers sharing a DOI → QUARANTINE.** The 9 real
     cases are overwhelmingly **container DOIs** — a book/proceedings DOI (ISBN-pattern, e.g. `10.1201/b13111`,
     `10.1007/978-3-319-11728-7`) applied to both a "preface"/"introduction" *and* a chapter, or to multiple
     chapters. So the guard = "merge on shared DOI only if titles are fuzzy-similar (token Jaccard ≥ ~0.5) and
     years agree; else open a `PUBLICATION_SHARED_DOI` conflict." Analogous to the forum `CROSS_JOURNAL_ISSN`
     guard.
   - **Implemented as a blocklist-to-EID pre-pass (cleaner than a merge-time guard).** Code inspection
     surfaced that *every* Scopus fact has an EID (`upsertFromScopusFactInternal` hard-skips blank EIDs), so today
     the DOI slot is never reached for Scopus pubs — they are all `eid|`, which is *why* same-DOI papers don't
     merge. The guard therefore lives at id-derivation, not at merge: a build-scoped pre-pass
     (`computeSharedDoiBlocklist`, run once in `loadSourceFacts`) groups the source facts by normalized DOI,
     single-link-clusters each ≥2-record group's titles (token Jaccard ≥ `0.5`, year agreement when both present),
     and blocklists any DOI forming >1 cluster (opening one `PUBLICATION_SHARED_DOI` conflict per DOI with the group
     EIDs as candidates). `buildCanonicalPublicationId` consults the blocklist: a **blocklisted DOI falls through to
     the `eid|` slot** — i.e. exactly today's separation for the 9 container groups, so the quarantine path is a
     *no-op vs. legacy* and carries zero regression risk; the 79 clean groups collapse onto `doi|…`. The cascade
     now reads `doi(non-blocklisted) → eid → wos → user → title+date`.
   - **Validation rebuild (2026-06-18, `scholardex_h66`) — title-only clustering, year check dropped.** A
     full from-scratch unified rebuild surfaced a guard-tuning bug: the first cut (Jaccard ≥0.5 **+ year agreement**)
     blocklisted **35** of 91 source-level multi-record DOI groups, but ~16 were **false quarantines** — same paper,
     two source records differing only in `coverDate` (Scopus online-first vs print year), e.g. `10.1300/j002v41n03_09`
     "Family strengths in Romania" 2007/2013, `10.1016/b978-0-08-096513-0.00001-7` "Crystal Growth and Surfaces"
     2010/2009. Since a DOI is globally unique to one work, same-title-under-shared-DOI is the same paper regardless
     of year. **Fix: drop the year gate, cluster on token-Jaccard alone → 35 → 19 blocked**, all genuine containers
     (`10.1142/9878` 9 papers, `10.1201/b13111` 12, book+preface pairs, plus a data-error journal DOI carrying two
     unrelated papers `10.1103/physrevd.97.055001`) except 2–3 residual subset/garbled-title same-paper pairs
     (`…2013-198` "…Proceedings of the Conference" suffix; `…2013-185` "[InlineEquation not available]") that stay
     separate — the *safe* failure mode (a conservative miss = legacy behavior, never a false merge; an
     overlap-coefficient would catch them but risk corrupting false merges, so rejected).
   - **The "count drop" is mostly a baseline artifact, not Decision 0.** Post-rebuild canonical pubs = **92,507** vs
     a pre-rebuild **94,171** — but the 94,171 was the *Phase 2/3-mutated* state (upload + reconcile artifacts). The
     clean from-scratch canonical (92,507) ≈ source facts (92,600) − ~93 genuine DOI merges (≤120 theoretical max
     across the 91 groups). **No over-merging**: DOI-keying's true isolated effect is ~93–120 collapsed
     cross-source/duplicate records, *not* 1,664.
   - **The guard must be enforced in BOTH the id-derivation AND the load-merge path (orphan-check finding).** The
     2nd rebuild's orphan check (run against Mongo `_id`, not the non-existent `id` field) found 0 publication→forum
     orphans but **68 authorship + 86 affiliation edge orphans** — all from blocked container-DOI records
     (e.g. `10.4324/9780203431115` "Introduction"+"Foreword"). Root cause: `loadExistingByEidOrDoi` *still merged by
     DOI* (via the preloaded `publicationByDoi` index / `findSingleByDoi`) even for blocklisted DOIs, so the two
     records aliased onto one fact object while id derivation kept them on distinct `eid|` ids — stranding a pub
     write and orphaning its edges. **Fix:** the load-merge path now consults the same `sharedDoiBlocklist` — a
     blocklisted DOI merges by EID only. Lesson: a publication-identity guard has to hold at *every* place two
     records can be declared "the same pub," not just where the id string is minted.
   - **Migration = one full rebuild + ONE user-state remap (pre-flight finding).** Every DOI'd canonical pub id
     changes. The pre-flight swept every `@Document` carrying a `publicationId`: three are **derived** and regenerated
     by the rebuild — `scholardex.authorship_facts`, `scholardex.publication_author_affiliation_facts`,
     `scholardex.publication_dblp_evidence` (the last is retired in 4b anyway). Exactly **one is user-persisted state
     that the rebuild does NOT touch**: `scholardex.publication_authorship_decisions` (a user's CONFIRMED/REJECTED
     authorship reviews, keyed `(userEmail, publicationId)`). On a DOI-primary cutover its `publicationId` values go
     stale and would silently strand the user's decisions. **Counts:** isolated `scholardex_h66` = 0 (validation
     rebuild is safe to run as-is); prod `test` = 76 decisions, 75 DOI-bearing. **Remap is mechanical** — each row's
     `snapshot.publication` carries both `eid` and `doi`, so the prod cutover must run a one-shot migration that
     recomputes the new canonical id (snapshot DOI if not blocklisted, else EID → look up the rebuilt pub fact) and
     rewrites `publicationId`. This is a **required prod pre-step**, not an isolated-validation blocker. Citations
     still resolve by EID → the pub fact → its new doi-keyed `.id`; OpenAlex DOI-edges then land on the *same* ids →
     cross-source citation merge becomes automatic.
   - **Diagnostic to run on the validation rebuild:** assert (a) pub count drops by ≈ the predicted merges minus
     the ~9 the guard blocks, (b) `orphanedPublicationForumLinks`/edge orphans = 0, (c) the guard quarantined only
     the genuine container-DOI cases (the physics title-variants passed). Same rigor as the 448→24 forum collapse.
   - **Cleanup rides along:** drop the vestigial `gs|`/`googleScholarId` slot; the `PublicationEnrichmentLinkerService`
     DOI-bridge becomes redundant for DOI'd pubs (keep it for the DOI-less tail + the guard's quarantine surface).

   **Decision 1 — OpenAlex = FULL source (enrich + mint), on a DOI-primary identity.** With Decision 0 done,
   publications are a multi-source entity *cheaply*: DOI'd works unify by identity (no dedup pass); only the
   **DOI-less tail** + venue-resolve remain. Precedent: `UserDefinedCanonicalizationService` already mints
   non-Scopus canonical pubs, so the pipeline/projections handle non-EID pubs.
   - **Publication resolve (Tier-2):** for each OpenAlex work, find-or-mint by EID/DOI — reuse
     `PublicationEnrichmentLinkerService` to *link* by DOI to an existing canonical pub, else *mint* a new pub
     keyed on `doi|…`. So OpenAlex never duplicates a Scopus pub; it links or adds.
   - **Citation edges:** resolve each edge endpoint's DOI/OpenAlex-id → canonical pub via the linker → write the
     canonical `ScholardexCitationFact` (a new **DOI-keyed citation path** beside the EID one).
   - **Venue:** `ofOpenAlex` forum resolve (Tier-2) for each work's host venue (DOI/ISSN = tight mint gate).
   - **Publication reconcile (Tier-1):** with DOI-primary (Decision 0), DOI'd duplicates can't form (same DOI →
     same id), so the cold-path publication reconcile shrinks to the **DOI-less tail** (fuzzy title+year+author
     dedup) + clearing the guard's `PUBLICATION_SHARED_DOI` quarantine — a much smaller job than a full
     dedup-by-DOI pass.

   **Decision 2 — add ORCID to the researcher profile.** New `orcid` field on the profile; a new
   `OpenAlexAuthorUpdate` task (mirror of `ScopusPublicationUpdate`) keyed on ORCID drives the on-demand fetch
   (ORCID consolidates OpenAlex's fragmented author entities — verified the test author splits across 4). DBLP
   author queries can reuse the same identity. Scopus-author-id stays for the existing Scopus flow.

   **Decision 3 — DBLP = batch + on-demand.**
   - **Batch (back-catalog):** one pass over existing pubs flagged `type=book-chapter` under an LNCS-family ISSN
     (`0302-9743` etc.) → DBLP API by DOI → real conference → `ofDblp` forum resolve (mint/match the conference
     forum) → **re-point the pub's `forumId`** from the LNCS-series forum to the conference forum (the existing
     dedup re-point machinery). Keeps the LNCS-series forum for genuine book chapters.
   - **On-demand:** the same resolution for LNCS pubs arriving in an incremental upload.
   - **Retire** `publication_dblp_evidence`, the disabled XML-bomb limits, the 77-entity sanitizer, and
     `/general/dblpLnChapterEnrichment`; `ComputerScienceConferenceScoringService` reads the re-pointed forum.

   **Net new surface (ordered):** (0) **DOI-primary identity** — cascade reorder + fuzzy-title false-merge guard
   + validation rebuild (do first; it shrinks everything below); (1) publication Tier-2 resolve (find-or-mint by
   DOI/EID) — now mostly just the DOI-less tail + venue-resolve; (2) DOI-keyed citation path (largely free once
   pubs unify by DOI); (3) `ofOpenAlex`/`ofDblp` + FK lists (small); (4) ORCID field + `OpenAlexAuthorUpdate` task
   + scheduler (mirrors Scopus); (5) DBLP API adapter + retire the band-aid. Build order: **Decision 0 →
   OpenAlex → DBLP** (DBLP reuses the venue-resolve seam).

   ### Phase 4a — OpenAlex, architecture resolved (2026-06-19, decisions locked, build started)

   **Decisions:** (a) **fetch = Java-direct WebClient** — OpenAlex is keyless/simple JSON, so no Python-bridge
   coupling (the `scopus-python` bridge exists only for Scopus's pybliometrics/key complexity); (b) **entry point =
   on-demand by ORCID first** — build the per-researcher author-update slice before any mass DOI backfill (smaller
   blast radius, immediately testable on a real ORCID).

   **Architecture (mirrors the user-defined source pattern — the proven template for a non-Scopus pub source).**
   Code inspection settled this: `scopus.publication_facts` is **Scopus-only** (many Scopus channels, one source
   table); WoS is a separate reporting tier and never a publication source (it only *links* + populates the `wosId`
   cascade slot); `user_defined.publication_facts` is a **separate source-fact table** whose
   `UserDefinedCanonicalizationService` reads it and writes canonical via `publicationWriter.upsertAndLinkSource(...)`,
   replayed on every full rebuild. So OpenAlex gets the same shape: a durable `openalex.publication_facts` source
   table + an `OpenAlexCanonicalizationService`, NOT rows in `scopus.publication_facts`.
   - **Decision 0 makes "link" free.** An OpenAlex work with DOI `D` derives the *same* `spub_doi(D)` canonical id,
     so `upsertAndLinkSource` **merges onto an existing Scopus pub** (adds the `OPENALEX` source-link + enriches) with
     no explicit `PublicationEnrichmentLinkerService` call. The linker (EID→DOI, link-only, `>1 DOI → CONFLICT`) is
     reserved for the EID-bridge edge cases; its `CONFLICT` path is consistent with Decision 0's blocklisted
     container DOIs (mint-distinct, never link). Minting happens only for genuinely new / DOI-less works.

   **Stage 1 deliverables (on-demand ORCID slice):**
   1. `orcid` (single `String`, 1:1 per researcher) on `User.ResearcherProfile` + `ProfileSaveRequest`/admin DTOs +
      both save paths (mirrors `scopusId`).
   2. `OpenAlexClient` (Java `WebClient`) — `GET https://api.openalex.org/works?filter=author.orcid:<id>&per-page=200&cursor=*`,
      polite-pool `mailto` from config; cursor pagination; work DTO (id, doi, title, publication_year, authorships,
      primary_location/host_venue ISSN, referenced_works, cited_by_count).
   3. `OpenAlexPublicationFact` → `openalex.publication_facts` (durable, idempotent upsert on OpenAlex work id;
      mirrors `UserDefinedPublicationFact`).
   4. `OpenAlexCanonicalizationService` — read facts → `buildCanonicalPublicationId` (DOI-first) →
      `upsertAndLinkSource(source=OPENALEX)`; DOI-collision auto-merges, else mints (mirrors
      `UserDefinedCanonicalizationService`).
   5. `OpenAlexAuthorUpdate` task + repo + `UserOpenAlexTaskFacade` + `OpenAlexUpdateScheduler` (poll/retry/claim;
      mirrors `ScopusPublicationUpdate` + `ScopusUpdateScheduler`); task captures `orcid` at creation.
   6. `POST /user/workspace/profile/sync/openalex-authors` controller endpoint (mirrors `sync/publications`).
   7. Full-rebuild replay of `openalex.publication_facts` (mirrors the user-defined orchestration in the canonical
      materialization).
   - **Deferred to later stages:** Stage 2 = DOI-keyed citations (`referenced_works`/`cited_by`); Stage 3 =
     `ofOpenAlex` venue resolve. **Validation:** enter ORCID → sync → DOI'd works link onto existing canonical pubs,
     DOI-less/new ones mint, 0 orphans.
   - **Not OpenAlex:** the untracked `scopus-python/brainmap_test.py` + `.env.example` are a separate brainmap.ro
     experiment — out of scope here.

   **Stage 1 — built + validated (2026-06-19).** Files: `User.ResearcherProfile.orcid` + DTOs +
   `OrcidSupport`; `openalex.publication_facts` + `OpenAlexPublicationFact` (durable — NOT in
   `MANAGED_DERIVED_COLLECTIONS`, so it survives the full wipe; auto-owned via `@Document`); `OpenAlexClient`
   (cursor-paged `works?filter=author.orcid:`); `OpenAlexImportService` (work→source-fact upsert);
   `OpenAlexCanonicalizationService` (DOI link / mint + self-authorship edge); `OpenAlexAuthorUpdate` task +
   repo + `UserOpenAlexTaskFacade` + `OpenAlexUpdateScheduler` + `POST /profile/sync/openalex-authors`;
   full-rebuild replay wired into `ScopusCanonicalMaterializationService` (full-maintenance only).
   - **Design refinement vs. the plan:** the writer (`upsertAndLinkSource`) stamps `source`/provenance
     unconditionally, so a *link* onto a richer Scopus pub would clobber it. So link = **source-link only, no
     canonical-fact mutation**; mint = full write. And the authorship edge key is `(publicationId, authorId,
     source)`, so one `OPENALEX` self-authorship edge per syncing researcher coexists with any Scopus edge — added
     in **both** link and mint, making works visible even when Scopus never attributed them.
   - **Live validation on `scholardex_h66`** (ORCID `0000-0002-0702-6276`, Adrian Spătaru / UVT, 26 works):
     26 source-facts → **16 linked** onto existing Scopus pubs (enriched, not duplicated — Decision 0's
     DOI-collision-link) + **10 minted** (OpenAlex-only works, 92,526→92,536) + **26 self-authorship edges** (all
     works now visible under the researcher) + **0 ambiguous-DOI quarantines**. Orphan gate: **0** across
     pub→forum, authorship→pub, authorship→author, source-link→pub. Minted pubs carry `forumId=null` (venue is
     Stage 3) — not orphans.
   - **Durability — PROVEN (after a fix).** First durability rebuild FAILED: the full-rebuild endpoint
     (`rebuildAllDerivedFromSource` → `ScopusBigBangMigrationService.runFull`) calls the canonicalization builders
     *directly* and bypasses `rebuildFactsAndViews`, where the replay was first wired — so the wipe dropped the 10
     mints + 16 links + 26 edges while the durable `openalex.publication_facts` (26) survived but was never
     replayed. (Notably `runFull` doesn't run user-defined canonicalization either.) Fix: add
     `openAlexCanonicalizationService.rebuildCanonicalFacts()` into `runFull` after Scopus publication canon, before
     projections. Re-validated: a full rebuild now re-derives **exactly 10 minted + 26 source-links + 26 authorship
     edges** from the durable table, total pubs back to 92,536, **0 orphans**. (Commits `913ea67` + `55843e8`.)
   - **Known Stage-1 cost / follow-ups:** the on-demand scheduler calls a full `projectionBuilderService.rebuildViews()`
     per sync (heavy; a batch/incremental refresh is a later optimization). Co-author bridging against OpenAlex's
     fragmented author entities is deferred.

   **Stage 1.1 — corresponding authors (2026-06-19, built + validated).** OpenAlex authorships carry
   `is_corresponding`; read it while parsing authorships → `OpenAlexPublicationFact.correspondingAuthorNames`
   (durable, lossless, like `authorOrcids`/`hostVenueIssns`). On **MINT**, populate the canonical
   `correspondingAuthors` (name-string list — same shape as the Scopus field, surfaced in `ScholardexPublicationView`);
   **LINK** never touches it (Scopus's denser data wins). Commit `e7810d0`.
   - **Validated live** (ORCID `0000-0002-0702-6276`): **9/26** source-facts captured a corresponding author
     (Spătaru, Talia, Frîncu, Chondrogiannis…) — matches the API exactly; 0 orphans, no regression.
   - **Manifestation caveat (not a bug):** only the **DOI-less** minted work showed `correspondingAuthors` populated
     on re-sync, because DOI'd works minted by a *prior* run re-LINK (resolve finds them by DOI) rather than re-mint,
     and LINK doesn't mutate. A fresh full rebuild re-mints all OpenAlex pubs and would populate them; the unit test
     covers the mint-population logic directly.
   - **✅ Resolved — OpenAlex-owned pubs now refresh on re-sync (commit `80f0a90`).** The `resolve()` link branch
     now checks ownership: if the DOI-matched pub is `source == OPENALEX` (one OpenAlex itself minted) it is UPDATED
     in place via the shared `applyOpenAlexFields` helper (refreshing `citedByCount`, `correspondingAuthors`, title,
     …); foreign (Scopus/user-defined) pubs keep the link-only, no-clobber path. Validated live: a previously-frozen
     minted LNCS pub (`10.1007/978-3-030-48340-1_35`) refreshed `correspondingAuthors=[]` → `["Domenico Talia"]`,
     minted pubs carrying corresponding authors went 1→3 with no re-mint, split unchanged (10 minted), 0 orphans.
     (Minor cosmetic: a refresh is counted in the scheduler's `linked` bucket via `markUpdated`, not split out.)

   **Stage 1.2 — corresponding authors made id-based (2026-06-19, commit `31bf751`).** The name-string field
   was a dead-end join key; replaced with the proper model — corresponding-author is a `corresponding=true` flag on
   a canonical **authorship edge**, resolving to a `ScholardexAuthor` that aggregates ids across sources.
   - `ScholardexAuthorFact` += `orcidIds`, `openAlexAuthorIds` (+ finders); `ScholardexAuthorshipFact` +=
     `corresponding`; edge writer gains a `(command, corresponding)` overload (existing callers untouched).
   - Source-fact now stores corresponding authors as `{name, orcid, openAlexAuthorId}` refs and syncing researchers
     as `{canonicalAuthorId, orcid}` — durable, so the full-rebuild replay re-resolves + re-seeds.
   - `OpenAlexAuthorResolver`: find-or-mint by **ORCID → OpenAlex id → mint** (mint+reconcile policy, OpenAlex
     becomes an author source); `attachOrcid` seeds the syncing researcher's ORCID onto their existing (Scopus)
     author so they dedup instead of duplicating. `writeAuthorshipEdges` seeds, resolves, and writes one edge per
     (syncing researcher ∪ corresponding author) with the flag on the corresponding ones.
   - **Validated live:** 9 `corresponding=true` edges; the syncing researcher's Scopus author ORCID-seeded + deduped
     (not minted); 4 corresponding co-authors minted as canonical authors keyed by ORCID + OpenAlex id; **0 orphans**.
   - **⬜ Follow-ups:** (a) **author-reconcile** — the 4 minted authors (`scopusIds=0`) are likely duplicates of
     existing Scopus authors (UVT co-authors); a Tier-1 author reconcile (merge by ORCID once Scopus authors carry
     one, else fuzzy name+affiliation) collapses them — same eventual-consistency pattern as forums/pubs.
     (b) **Seed ORCID from researcher profiles** on full rebuild (currently seeded only via the per-work
     `syncedResearchers`; a profile sweep would seed all researchers up front). (c) Surface `corresponding` in the
     read projection/reports. (d) Full-rebuild durability of the author model is wired (rides `rebuildCanonicalFacts`)
     + unit-covered, but not yet re-proven live.

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
