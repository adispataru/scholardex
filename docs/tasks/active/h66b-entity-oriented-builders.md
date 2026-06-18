# H66B — Entity-oriented canonical builders

**Status:** Planning (2026-06-17). Pivot from H66 Move D/E in-place work to a pipeline re-architecture.
**Parent:** [H66](h66-curated-allowlists.md) (Moves A–C done; D1/A6 + D3/D4 landed and forward-compatible).

## Why

H66's live multi-source rebuild (2026-06-17, on `scholardex_h66`/`core_h66`) worked but exposed that the
canonicalization layer is organized **by source**, not **by entity**. Forum-building is smeared across five
services with order-sensitive glue:

- `WosScholardexOnboardingService` — WoS journals → forums **and** Scopus-forum canonicalization,
- `ScopusFactBuilderService` publication path — derived forums from publication venues (the line-501
  `upsertForumFact`; H66 D4 neutered it with a `fromPublication` flag),
- `ErihOnboardingService` — `erihIds`,
- `ScholardexForumDeduplicationService` — dedup / safe-merge,
- the Source List / CiteScore / MJL / DOAJ feeds.

Glued together in `ScopusBigBangMigrationService` + `WosBigBangMigrationService` + `PipelineRebuildService`,
by source. That scattering caused the forums-first ordering bug, the **421 `EXTERNAL_ID_ALREADY_LINKED`**
churn conflicts, and the MJL-coverage gap (`coverage_facts=0` via the step-wise path). H66 D3/D4 are in-place
approximations of the fix; this task does it structurally.

## Target architecture — two layers

1. **Source parsers (stay per-source — formats differ).** Scopus JSON, WoS xlsx, CiteScore CSV, Scopus
   Source List xlsx, Scopus Book List xlsx, MJL, DOAJ, ERIH → normalized **source facts** (stage-2:
   `scopus.forum_facts`, `scopus.publication_facts`, `wos.journal_identity`, `wos.metric_facts`,
   `doaj.journal_facts`, `erih.journal_facts`, etc.). Unchanged in spirit (each format needs its parser).
2. **Entity builders (source-agnostic, a clean dependency DAG):**

```
        ┌─────────────┐
        │ ForumBuilder│  all forum sources → canonical forum registry (dedup + safe-merge inside)
        └──────┬──────┘
               ▼
        ┌──────────────┐
        │ RankingBuilder│ CiteScore + WoS metric/category + WoS score-only→quartile enrichment
        └──────┬───────┘   → forum-keyed rankings (B2/B3 views)
               ▼
        ┌──────────────────┐
        │ PublicationBuilder│ resolve venue → registry (Scopus now; OpenAlex/DBLP/GS later); books → BookBuilder
        └──────┬───────────┘
               ▼
        ┌──────────────┐
        │ CitationBuilder│  citation edges (Scopus now; source-plural later)
        └──────────────┘

   BookBuilder (sibling of ForumBuilder): Book List → scholardex.book_facts (separate entity, no rankings).
```

Key invariants:
- **Publications are never a forum input.** The ForumBuilder runs to completion first; PublicationBuilder
  only *resolves-and-links* venues against the finished registry (D4's `fromPublication` flag becomes
  structurally impossible — publications emit only `ScopusPublicationFact`).
- **Option-B forums** (a publication venue absent from every authoritative source) are minted by the
  ForumBuilder from a distinct "publication-venue" source-fact stream, provenance-tagged, never overriding
  curated data.
- **Source-plural by construction.** A new publication source (OpenAlex/DBLP/Google Scholar — see
  [H63](h63-openalex-enrichment.md)) is just another input to PublicationBuilder/CitationBuilder, not a new
  monolithic source service.

## Current → target mapping

| target builder | absorbs (today) | notes |
|---|---|---|
| **ForumBuilder** | `runScopusForumCanonicalization` (in `WosScholardexOnboardingService`), WoS forum onboarding, `ErihOnboardingService`, `ScholardexForumDeduplicationService`, the Source List/CiteScore/MJL/DOAJ feeds | one component; dedup + `ForumMergeSafetyRule` inside; consumes all forum source facts |
| **RankingBuilder** | WoS metric/category projection (H66 B2/B3 in `ScholardexProjectionBuilderService`), WoS category-ranking enrichment (`enrichCategoryRankings`), CiteScore scores (D2, new) | forum-keyed rankings; CiteScore/SJR/SNIP/quartile + WoS AIS/IF/RIS + quartile/rank; enriches WoS score-only entries |
| **PublicationBuilder** | `ScholardexPublicationCanonicalizationService` + the publication side of `ScopusFactBuilderService` | resolve-and-link venue against registry (subsumes D4); source-plural input |
| **CitationBuilder** | `ScholardexCitationCanonicalizationService` | source-plural |
| **BookBuilder** | new (Move E) | `scholardex.book_facts` from the Book List; publications gain `bookId`; venue branches on `aggregationType` |
| (unchanged) author/affiliation canonicalization | `ScholardexAuthor/AffiliationCanonicalizationService` | publication dimensions; keep |
| (unchanged) source parsers | `ScopusFactBuilderService` (Scopus JSON→stage-2), `WosFactBuilderService`, `ScopusDataService` (CiteScore/SourceList ingest), `Doaj/ErihDataService` | the line-501 forum derivation is **removed** here, not just flagged |

## Redesign (full rewrite, not strangler)

Decision (2026-06-17): this is a **rewrite of the forum-build core**, not an in-place consolidation. The
inherited order/structure is the problem, so we do **not** preserve it. North star: **conflicts collapse to
the genuine residual** (~27 = cross-journal ISSN + dedup name-mismatch + invalid ISSN), not parity with the
448-conflict baseline (421 of which were ordering churn). We can afford the longer build + the
core-identity-logic risk; the isolated-rebuild + reconcile-audit harness is the regression net.

### Components (decompose the 1177-line monolith)

`WosScholardexOnboardingService` is dissolved. Forum building becomes a **single source-agnostic engine**
fed by a normalized record:

- **`ForumSourceRecord {idType, externalId, name, issn, eIssn, aggregationType, attrs}`** — every forum
  *source* emits these. `idType ∈ {SCOPUS, WOS, ERIH, USER, …}` selects which forum id-list the externalId
  lands in.
- **`ForumIdentityIndex`** — the in-memory ISSN-token + name|agg index (today's `CanonicalForumIndex`/
  `ScopusForumIndex`), built once and updated incrementally. The performance heart (see below).
- **`ForumMergeEngine.ingest(ForumSourceRecord)`** — find-or-create the canonical forum via the index +
  `ForumMergeSafetyRule`; attach `externalId` to the `idType` list; stage the forum save + source link;
  record conflicts. **`runWosOnboarding` and `runScopusForumCanonicalization` collapse into this** — they were
  ~90% duplicate machinery differing only in idType/id-list.
- **`SourceLinkWriter`** / **`ConflictRecorder`** — batched source-link upserts and identity-conflict
  recording, lifted out of the monolith as focused collaborators.
- **`ForumBuilder.build()`** — drives the engine over **all** forum sources (Source List, WoS journal
  identity, ERIH, conference venues observed in publications, user-defined), then a final dedup. One entry,
  one place forums are built.

What stays (load-bearing, NOT cruft): the event ledger + stage-2 facts (deterministic replay/checkpoint),
**source links** (provenance + dedup re-pointing + publication resolution), **identity conflicts**, and
`ForumMergeSafetyRule`.

Publications go **pure**: they emit only `ScopusPublicationFact` (already carries `forumId = source_id`) plus
a deduplicated **conference-venue source stream** (the ~2,065 venues, mostly un-profiled conferences, that no
curated list has — a *legitimate* forum source). No publication→`scopus.forum_facts` write.

### Performance invariants (non-negotiable)

The current speed comes from specific structures; the rewrite must not regress them. Every milestone is
timed on the isolated rebuild and gated against the current baseline (forum build ≈ tens of seconds; full
rebuild ≈ 23 min dominated by the 482 MB Scopus parse, which is unchanged).

1. **One index build, incremental updates.** `ForumIdentityIndex` is loaded once per build and mutated as
   forums are created/merged — never rebuilt per record, never re-queried per lookup.
2. **No linear scans over growing collections.** Candidate lookup is O(1) amortized via the token index (the
   bug class we already fixed twice — `findScopusCandidates`, source-link batching). Any `for-forum-in-all`
   inside a per-record loop is a defect.
3. **Batched IO only.** Bulk `findByIdIn` preloads, `saveAll`, JDBC `batchUpdate` (chunk ≈ 500–1000). No
   per-record DB round-trips for forums, source links, or conflicts.
4. **Stream large inputs.** The Book List (475k rows / 42 MB) and any >50k-row xlsx are read via the POI
   streaming/SAX reader, not a full in-memory `XSSFWorkbook`. (The 48k-row Source List in-memory is fine.)
5. **Single pass, no double builds.** The orchestrator runs each builder once; no re-running `buildFacts`
   to fold a feed (the gap that cost a second full pass in the 2026-06-17 live run).

### Milestones (rewrite; branch `codex/h66b-builders`, pipeline may be red between milestones)

Each milestone: unit tests + isolated live rebuild measuring **conflict tally vs residual** *and* **timing
vs baseline** + reconcile audit `healthy=true`.

- **M0 — B66B.1 done.** `ScholardexForumBuilder` facade exists; runFull is forums-first. Carries over.
- **M1 — Merge engine + index, extracted & unit-tested.** Pull `ForumIdentityIndex` + `ForumMergeEngine` +
  `SourceLinkWriter` + `ConflictRecorder` out of `WosScholardexOnboardingService` as standalone components,
  behavior-equivalent, with the perf structures intact. Old methods delegate to them (transient).
  - **M1a DONE:** `ForumIdentityNormalization` extracted (all identity normalization + token extraction as
    pure static fns, unit-tested) and the service's ~15 private helpers delegate to it (duplication + orphaned
    constants/imports removed).
  - **M1b DONE:** `ForumIdentityIndex` (from `CanonicalForumIndex`) + `ScopusForumIndex` extracted as
    top-level classes on the normalization substrate (the O(1) token-index perf heart — shared `byId` map +
    incremental `put`); inner classes removed, type refs repointed, the reflective onboarding tests updated to
    use the top-level classes directly, `ForumIndexTest` added.
  - **M1c (in progress):**
    - **ConflictRecorder DONE:** generic `openConflict` (idempotent per entity+source+record+reason+OPEN) +
      `markConflictLink` extracted as a `@Service` collaborator (`ConflictRecorderTest`); the onboarding
      service delegates to it. Source-link writing already lives in `ScholardexSourceLinkService` (reused, no
      separate `SourceLinkWriter` needed).
    - **ForumMergeEngine DONE:** the M1c core. `ForumMergeEngine` (`@Service`) now owns the per-record
      find-or-create/merge (`upsertForumFromWos` / `upsertForumFromScopus` / `mergeForum` /
      `mergeForumFromScopus`), the H57 ISSN token-hygiene + cross-journal guard (`normalizedIssnSet` /
      `isCrossJournalToken` / `buildPrimaryIssnIndex`), `buildCanonicalForumId`, `persistForumOrRecordConflict`,
      `resolveOpenForumAmbiguityConflict`, and the source-link command building (`linkedCommand` /
      `conflictCommand` / `loadForumSourceLinks` / `loadOpenForumConflictKeys`). **The per-run gotcha is
      solved:** `primaryIssnIndex` and the threaded `linkCommands` / `existingForumLinks` / canonical maps are
      now fields of a per-invocation `ForumMergeEngine.Context` (built by `startWosRun` / `startScopusRun`),
      not Spring-singleton state. `WosScholardexOnboardingService` shrank from 927 → 213 lines: it now only
      builds the WoS journal-record list, drives the engine loop + `flush`, and owns the publication→WoS
      source-link onboarding (`onboardPublicationWosLinks`, which is not forum building). New constructor:
      `(journalIdentityRepository, sourceLinkService, scholardexPublicationFactRepository, conflictRecorder,
      forumMergeEngine)`. White-box reflective coverage for the merge/ISSN-hygiene/id branches moved to
      `ForumMergeEngineTest`; the end-to-end behavior net (`runWosOnboarding` / `runScopusForumCanonicalization`)
      stays in `WosScholardexOnboardingServiceTest`, now green through the engine (19 + 5 tests, 0 failures).
- **M2 DONE (code) — Unify WoS + Scopus ingestion through `ForumMergeEngine.ingest(ForumSourceRecord)`.**
  New `ForumSourceRecord {idType, externalId, name, issn, eIssn, aliasIssns, aggregationType, forumType, asjc}`
  with a nested `ForumIdType` enum (SCOPUS/WOS) carrying the source string + diagnostic reason strings
  (`missingIdReason` / `onboardingReason` / `ambiguousSkipPrefix`), plus static mappers `ofScopus`
  (applies SIAM eISSN correction; no alias ISSNs) / `ofWos` (null aggregation → engine default; no C-scalars).
  The two `upsertForumFrom*` methods collapsed into one `ingest(record, ctx, …)`; source-specific behavior
  keys off `idType` (Scopus-only: already-folded shortcut, H55.6 primary-ISSN tie-break, `scopusForumId→
  canonical` tracking, stale-ambiguity resolution; WoS-only: Scopus enrichment inside `mergeForum`). The
  merge bodies stay split (`mergeForumFromScopus` now takes the record; `mergeForum` keeps its scalar
  signature for the white-box test) and dispatch via `applyMerge`. The service just maps `ofScopus`/`ofWos`
  and drives the loop. Behavior-equivalent: the end-to-end net (19 + 5 tests) is green unchanged through the
  unified path; added `ForumSourceRecordTest` (2) for the mapper contract.
  - **Gate still open (live rebuild):** conflicts ≤ baseline + trending down and forum-build time ≤ baseline
    must be confirmed on the isolated `scholardex_h66` rebuild (recipe below). The code is structurally
    behavior-equivalent, but the 448→~27 conflict collapse is really delivered by **M3** (pure publications /
    no publication→forum-fact write), so M2's live gate is "no regression," not "the collapse."
- **M2 follow-on (deferred to M3+):** ForumBuilder will feed Source List + WoS identity + conference-venue +
  ERIH records through this one engine — the unified `ingest(ForumSourceRecord)` is the seam that makes those
  just more `idType`s / more record streams.

> **Resequencing (2026-06-17, after M2).** The old M3 lumped two unrelated things under "pure publications":
> a publication-pipeline change (stop writing forum facts) and *forum-source* work (conference venues, ERIH).
> Clarified by reading the code: **DOAJ is not a forum source** — `buildDoajMembershipRows` runs at stage-4
> projection time and only matches DOAJ journals to forums that already exist (membership rows), so it is
> structurally downstream of forum identity. **ERIH today is match-only** (`ErihOnboardingService` writes
> `erihIds` to existing forums, never creates). So neither was ever "after" the publication work in a
> dependency sense; the old ordering was a *metric* front-load (the 421 churn lives in the publication
> forum-write). **Decisions:** (1) ERIH becomes **create-or-match** — a genuine ForumBuilder source routed
> through `ingest` (new `idType=ERIH`), minting forums for ERIH-only humanities venues. (2) Split the old M3:
> M3 completes the ForumBuilder (forum *sources* through the seam); the "attach-to-existing-forum" feeds
> (DOAJ membership, ERIH membership, CiteScore-as-ranking) move to M4. "Pure publications" stays *inside* M3
> only because the conference-venue forum source requires it (else conference forums double-mint).

- **M3 — Complete the ForumBuilder (forum sources through `ingest`).** Route the remaining forum-*creating*
  sources through the unified engine:
  - **M3-A ERIH create-or-match DONE.** `ErihOnboardingService` is no longer a bespoke match-only `erihIds`
    writer — it builds a `ForumMergeEngine.Context` (`startErihRun`) and loops ERIH journals through
    `forumMergeEngine.ingestErih(ofErih(erih), …)`. Per the design call (kept fan-out, not conflict):
    ISSN-token match → tag the `erihId` on **every** matching forum (split-journal signal; batched `saveAll`
    on `flush` via a new `Context.dirtyForums`); no match → **create** an ERIH-only canonical forum
    (`mergeForumFromErih` mints id + carries the erihId, saved per-record for the DuplicateKey guard).
    `ForumIdType.ERIH` added; `ofErih` mapper added; ERIH now obeys the **same strict ISSN identity** as
    every source (check-digit validation + H57 hygiene via the shared index) — a correctness improvement
    (old lax compacting matched typo ISSNs). ERIH writes **no source links** (its `erihIds` FK is the
    linkage), so `startErihRun` skips the link preload. `ScholardexForumBuilder`'s re-dedup trigger
    (`erihOnboarding.getUpdatedCount() > 0`) still fires on fan-out tags; creates (imported) correctly don't
    trigger it. `ErihOnboardingServiceTest` rewritten for create-or-match through the engine (valid-ISSN
    fixtures); 3 + the engine/service nets green. The service constructor dropped `ScholardexForumFactRepository`
    (now `(erihJournalFactRepository, forumMergeEngine)`).
  - **M3-B — RECONSIDERED / DEMOTED (2026-06-17 diagnostic).** Its premise (removing the publication→forum
    write collapses the 421 `EXTERNAL_ID_ALREADY_LINKED` churn) is **false against the current code.** A
    diagnostic isolated rebuild on the M3-A state measured **25 forum conflicts total, 0
    `EXTERNAL_ID_ALREADY_LINKED`** — i.e. already at the genuine residual (14 dedup-name-mismatch + 9
    cross-journal ISSN + 2 invalid ISSN), `healthy:true`, 0 orphaned publication links. D3/D4 (FORUM-chunks-
    before-PUBLICATION + the `fromPublication && !created` guard) already eliminated the churn; the 448
    baseline was a pre-D3/D4 artifact. So "pure publications" is now **only an architectural-cleanliness
    refactor with no metric payoff, on the riskiest code** — exactly the "demolition for its own sake" the
    roast warned against. **Decision: don't do M3-B as a standalone milestone.** Fold "publications emit only
    `ScopusPublicationFact`" into **M5 (PublicationBuilder)** where it's natural and lower-risk; the existing
    publication-derived option-B forum write stays until then (it's not causing churn). Conference venues as a
    first-class `ForumSourceRecord` stream remains a nice-to-have for M5/M7, not urgent.
  - **(later/optional) user-defined** — same shape (`idType=USER`).
  - Net: the M3 *conflict* goal (`EXTERNAL_ID_ALREADY_LINKED` → ~0, total → residual) **is already met**;
    ERIH-only venues now have forums (M3-A). M3 is effectively complete bar the M5 fold.

> ### Target pipeline ordering (CONFIRMED 2026-06-17) — identity → ranking → observation
>
> The end-state pipeline runs in three strict layers. **Identity is built only from curated lists;**
> rankings and publications resolve against the finished registry and never define new identity (except the
> publication option-B long tail).
>
> 1. **IDENTITY (ForumBuilder, create-or-match through `ingest`, in authority order):**
>    `Source List → MJL → ERIH → DOAJ → WoS`. Earlier = higher authority for the stored name/identity;
>    later sources match-or-enrich, minting only for venues no earlier source had. **WoS runs *last* in this
>    layer as create-or-match** (identity-of-last-resort — preserves historical/defunct WoS journals 1997–2019
>    that the curated lists may not cover, while the curated lists win identity because they run first).
> 2. **RANKING (RankingBuilder, attach by FK, never create):** WoS metrics (AIS/IF/RIS/JIF) + CiteScore
>    (CiteScore/SJR/SNIP). Both stop being identity feeds; their numbers attach to forums the identity layer
>    built. DOAJ/ERIH/MJL membership rows also land here.
> 3. **OBSERVATION (Publication/CitationBuilder):** Scopus publications resolve their venue to the registry;
>    a venue in no curated list mints an option-B forum from a deduplicated conference-venue stream. Citations.
>
> This is a re-architecture of source roles, not just ordering: **MJL + DOAJ are promoted** from membership-
> only to identity sources; **WoS + CiteScore are demoted** from identity creators to ranking-only (WoS keeps
> a create-or-match fallback). The current code does none of this yet (WoS creates identity first; CiteScore
> creates; pubs create option-B inline) — M4–M8 below get there.

- **M4 — Complete the IDENTITY layer.**
  - **M4-A display-name rule — DONE.** WoS title wins the display name (→ Scopus → existing); ALL-CAPS WoS
    titles title-cased (`normalizeDisplayCase`); blank WoS title (id-fallback) falls through to Scopus.
  - **M4-B DOAJ as an identity source — DONE.** DOAJ promoted from a stage-4 membership-match to a
    create-or-match ForumBuilder source. Added `ForumIdType.DOAJ` + `ofDoaj`, the `doajIds` FK on the forum,
    and `DoajOnboardingService` (mirrors ERIH). Generalized the engine's ERIH path to serve both:
    `startErihRun`→`startCreateOrTagRun`, `ingestErih`→`ingestCreateOrTag`, `mergeForumFromErih`→
    `mergeForumFromCreateOrTag`, `addErihId`→`addExternalId(idType,…)` (switches `erihIds`/`doajIds`). Wired
    into `ScholardexForumBuilder` after ERIH (record gains `doajOnboarding`; `erihDedup`→`membershipDedup`,
    re-runs when ERIH **or** DOAJ tagged). `/forum/onboardDoaj` admin endpoint added for re-runs. Tests:
    `DoajOnboardingServiceTest` (2) + ERIH/builder/engine nets green. DOAJ-only OA venues now mint forums.
  - **MJL needs NO promotion (corrected 2026-06-17).** MJL is *not* identity-less: `MjlImportEventParser`
    carries `issn/eIssn/title` and `WosFactBuilderService` mints/finds a persisted `wos.journal_identity` by
    ISSN for each coverage record — so MJL-only journals already get a WoS identity and become forums via
    `runWosOnboarding` (WoS create-or-match). MJL identity = WoS identity. The "MJL coverage=0" symptom is a
    stage-4 membership-projection gap (M5/M7), not an identity gap. No `ForumIdType.MJL` / `ofMjl`.
  - **WoS create-or-match ordering** (run *after* the curated lists, identity-of-last-resort) is enforced by
    the M8 orchestrator; WoS already goes through `ingest` (create-or-match), so no engine change here.
  - Authority order target `Source List → (MJL via WoS) → ERIH → DOAJ → WoS`.
- **M5 — RankingBuilder — DONE (already satisfied + orphan reporting added).** Investigation (2026-06-18)
  showed the ranking layer already exists as the stage-4 forum-keyed projection: WoS metrics (AIS/IF/RIS) →
  `scholardex_forum_metric_view` by FK (`buildForumMetricRows`, live `fkMetricForums≈25.8k`), WoS
  category/quartile → `_category_view`, MJL coverage + DOAJ + ERIH → `_membership_view`. **CiteScore scores
  are intentionally not ingested** ("no domain uses them" — `ScopusDataService:248`); CiteScore contributes
  only forumType/asjc (classification, additive), so there is no CiteScore ranking to project. CiteScore-only
  forum creation is moot now that the Source List streams (CiteScore ⊂ Source List → it enriches, doesn't
  mint). **Added:** orphan reporting — `buildForumMetricRows`/`buildForumCategoryRows`/`buildForumMembershipRows`
  now log WoS journals that carry ranking/coverage facts but resolve to no forum (rankings dropped), per the
  agreed "attach-by-FK + report, never mint in the ranking layer" design. Expected ~0 (WoS identity is
  create-or-match); a non-zero count is a health signal (conflict-quarantined forum / identity gap), not a
  mint trigger.
- **M6 — PublicationBuilder + CitationBuilder (folds old M3-B) — CODE DONE, verify rebuild HELD.**
  Publications now emit **only** `ScopusPublicationFact` — the two inline `upsertForumFact(…,true)` calls are
  gone and `upsertForumFact` is FORUM-stream-only (the `fromPublication` flag/guard deleted). The
  conference-venue source is `flushObservedVenues`: every publication processed (publication events **and**
  citation-backfilled citing papers — both flow through `upsertPublicationItems`) contributes its venue to a
  run-level dedup accumulator (first writer per Scopus source_id wins); after publications + citations, a
  provenance-tagged `SCOPUS_OBSERVED_VENUE` forum fact is minted only for venues no authoritative FORUM
  source seeded, then the existing Scopus canonicalization folds them in (so publication venue resolution via
  the FORUM/SCOPUS source link is unchanged). Citations needed no work (venue-agnostic). Batched-IO preserved
  (chunked existence query + chunked saveAll). Behavior-equivalent at unit level (60 fact-builder tests green,
  incl. the citation-backfill venue coverage + the new `SCOPUS_OBSERVED_VENUE` provenance assertion).
  **Gate (held, per request):** isolated rebuild — forum count stable (~46.7k), conflicts ≤ 25,
  `orphanedPublicationForumLinks = 0`, observed-venue provenance count sane. Source-plural input shape
  (OpenAlex/DBLP/GS) is now the natural extension point.
- **M7 — BookBuilder.**
  - **M7-A book registry — DONE.** `ScholardexBookFact` (`scholardex.book_facts`, keyed by Scopus Source ID:
    title/print+electronic ISBN/publisher/year/ASJC) + `ScholardexBookFactRepository`. Streamed importer
    `ScopusDataService.importBookListXlsxFromPath` (POI SAX, batched saveAll during the parse — flushes every
    1,000 so the 475k rows never materialize at once) + `/scopus/importBookList` admin endpoint. Reference
    data (persists across rebuild, outside MANAGED_DERIVED_COLLECTIONS, like DOAJ/ERIH). Verified on the real
    file: **475,453 books parsed in ~5.9s under a 2 GB heap**, no ceiling/OOM. Unit-tested (fixture + missing-file).
  - **M7-B.1 venue branch (Mongo side) — DONE.** Decisions confirmed: book = `aggregationType=="Book"`
    (Book Series stays a forum); book venue → `bookId=source_id`, `forumId=null`; un-listed book venues mint
    an observed `SCOPUS_OBSERVED_BOOK` (option-B for books). `ScopusPublicationFact`/`ScholardexPublicationFact`
    gained `bookId`; `upsertPublicationAndDimensions` branches; `flushObservedVenues` partitions venues into
    forums (observed forum) vs books (observed book, via `bookFactRepository`); canonicalization carries
    `bookId` directly (no source-link resolution — books aren't merged). Unit-tested.
  - **M7-B.2 Postgres plumbing — DONE.** `bookId` reaches scoring end-to-end: `V14` migration adds
    `scholardex_publication_view.book_id`; the projection appends `book_id` as the last (44th) INSERT column
    + bind + ON CONFLICT (append-at-end, no index shift); the view-row builder sets it; both read mappers
    surface it (`ScholardexProjectionReadService` explicit SELECT + setter, `PostgresScholardexProjectionReadPort`
    via `SELECT *` + the two mappers); `ScoringPublicationReadModel.getBookId()` + `ScoringPublication.bookId`
    (with a 13-arg convenience ctor so the 14 existing fixtures compile unchanged). All green incl. the
    Testcontainers projection integration tests.
  - **M7-B.2 original plumbing map (for reference):** `bookId` must reach scoring through, in lockstep:
    1. `V13__…` Flyway migration: `ALTER TABLE reporting_read.scholardex_publication_view ADD COLUMN book_id text`.
    2. `ScholardexProjectionBuilderService`: **append** `book_id` as the **last** column in both publication-view
       INSERTs (`writePublicationRows` ~line 737 + `upsertPublicationRows` ~line 753) → one new `?` at index 44,
       `ps.setString(44, row.getBookId())` after the `auth_keywords` bind (~line 857), and `book_id =
       EXCLUDED.book_id` in the ON CONFLICT. Append-at-end avoids shifting the other 43 indices.
    3. View-row builder (`fact → ScholardexPublicationView`, ~line 417 next to `setForumId`): `view.setBookId(fact.getBookId())`.
    4. `ScholardexPublicationView`: add `bookId` field; pass it in `toScoringPublication()` (~line 113).
    5. Both read mappers add `book_id` to the SELECT + a `setBookId`/constructor arg:
       `ScholardexProjectionReadService` (SELECT ~215, mapper ~255) and `PostgresScholardexProjectionReadPort`
       (SELECT, view mapper ~280, **and** the `ScoringPublication` construction ~299).
    6. `ScoringPublicationReadModel` interface: add `getBookId()`. `ScoringPublication` record: add `bookId`.
  - **M7-C book scoring — DONE.** `FeaaBookScoringService` resolves the publisher from the book registry via
    `bookId` (forum fallback for the unlisted/edge case), through a new `ReportingLookupPort.getBook(bookId)`
    (`default` null; overridden in the `@Primary` `ReportingLookupFacade` to query Mongo `scholardex.book_facts`).
    FEAA test added (book publication → book-registry publisher, forum never consulted); the existing
    forum/journal fixtures (bookId=null) fall back to the forum unchanged. **M7 is code-complete** bar the
    held rebuild gate (book count loaded, book pubs resolve `bookId`, forum count drops by the no-longer-minted
    book venues, FEAA book scores unchanged for listed publishers) — to run after M8 with the rest of the stack.
  - **Streaming precedent set (2026-06-17):**
  `ScopusDataService.importSourceListXlsxFromPath` was rewritten from a full in-memory `XSSFWorkbook` to the
  POI SAX event reader (`OPCPackage`/`XSSFReader`/`XSSFSheetXMLHandler` + `ReadOnlySharedStringsTable`) — the
  May-2026 Source List (49,599 rows) has a part exceeding POI's 100 MB byte-array ceiling, which the full
  load tripped (`RecordFormatException`, the live-rebuild blocker). Parses in ~4s now. The Book List
  (475k rows) reuses this exact streaming pattern.
- **M8 — DAG orchestrator (enforces the identity → ranking → observation ordering).**
  - **M8-A.1 split WoS onboarding — DONE (behavior-equivalent).** `runWosOnboarding` split into
    `runWosForumOnboarding` (forum half — needs `wos.journal_identity`) + `linkPublicationsToWos` (publication
    half — needs `scholardex.publication_facts`); `runWosOnboarding` still calls both in order, so behavior is
    unchanged (19 tests green). This decomposition is the prerequisite for the reorder.
  - **M8-A.2 reorder to WoS-last — CODE DONE (rebuild validates).** WoS forum onboarding now runs LAST in
    `ScholardexForumBuilder.buildScopusForums` (dedup → Scopus canon → ERIH → DOAJ → **WoS** → final dedup;
    result record gains `wosOnboarding`, re-dedup fires when WoS touched the registry). `linkPublicationsToWos`
    runs after publication canon in all three `ScopusBigBangMigrationService` paths (build-facts / incremental
    / run-full). The WoS rebuild (`WosBigBangMigrationService`) no longer onboards forums — both
    `runWosOnboarding` calls + the `mergeResults` helper removed; it builds WoS facts + projections only. Tests
    re-pointed: `ScholardexForumBuilderTest` (WoS-last order), `ScopusBigBangMigrationServiceTest` (+inject,
    +record field, stub links), `WosBigBangMigrationServiceTest` (no longer onboards). All app + index + scopus
    suites green incl. Testcontainers integration. **Rebuild now validates the M4–M8 stack on real data.**
  - **VALIDATION REBUILD (2026-06-18, isolated `scholardex_h66`, Scopus+WoS, no feeds yet):**
    - ✅ **M7 books validated on real data.** `scholardex.book_facts`=2,185, publications with `bookId`=2,714,
      with `forumId`=89,844; forum count **30,358** — down ~2,356 = the book venues no longer minted as forums.
      Books are cleanly a distinct entity. prod `test` untouched (32,714).
    - ❌ **M8-A.2 (WoS-last) regression — 198 `WOS/FORUM_EXTERNAL_ID_ALREADY_LINKED`** (total 224 vs ~10 at the
      same Scopus+WoS stage with WoS-first). **Root cause (confirmed):** `ForumMergeEngine.mergeForum`'s
      scopus-enrichment (`scopusForumIndex.findCandidates`) claims a Scopus source id onto the WoS journal's
      forum. Harmless no-op under WoS-first (Scopus forums didn't exist yet); under WoS-last, Scopus canon
      already owns that id, so a WoS journal resolving (by ISSN) to a *different* forum but claiming an
      already-owned scopus id → DuplicateKey → conflict + skipped journal. (7,875 forums correctly carry both
      scopus+wos ids; 198 collided.)
    - **FIX (decide before re-running):** remove the scopus-id claiming from `mergeForum`'s scopus-enrichment
      (redundant under WoS-last — `forumIndex` already merges a WoS journal into the Scopus-canonical forum by
      ISSN, which already carries the scopus id; the enrichment only adds collisions + risky name-only links;
      dedup catches genuine ISSN-shared duplicates). *Or* make the enrichment ownership-aware (skip a scopus id
      already owned, merge into the owner). Either needs a confirming rebuild. Decision deferred (paused
      2026-06-18). Until fixed, **M8-A.2 is not release-ready** despite green unit tests.
    - **FIX APPLIED (2026-06-18):** removed the scopus-id claim from `mergeForum` — the one block that mutated
      the uniquely-indexed `scopusForumIds` via the enrichment (`scopusIds.add(scopusPreferred.getSourceId())`).
      `scopusForumIds` now stays whatever Scopus canon set; a WoS journal that shares an ISSN merges into the
      Scopus-canonical forum (via `forumIndex` in `ingest`) and gains the id naturally, one that doesn't no
      longer staples an owned id onto a different forum. The non-colliding issn/name/agg enrichment is kept.
      Unit-equivalent (only the white-box `mergeForumCovers…` scopus-id assertion updated; engine/onboarding/
      builder + app + scopus suites green). **Still needs the confirming rebuild** to show
      `EXTERNAL_ID_ALREADY_LINKED` drops 198 → ~0 and total → the ~26 residual (9 ambiguous + 10 cross-journal
      + 5 dedup + 2 invalid). Until that rebuild runs, M8-A.2 remains "code-fixed, data-unverified."
    - **CONFIRMING REBUILD (2026-06-18, isolated, Scopus+WoS):** ✅ **`EXTERNAL_ID_ALREADY_LINKED` 198 → 0** —
      the scopus-id-claim churn is eliminated. Total forum conflicts **224 → 103**. Books still separated
      (2,185 book_facts, 2,714 bookId pubs); WoS-name-wins + ALL-CAPS title-casing confirmed on real data
      ("Zeitschrift Fur Germanistische Linguistik", only 9 all-caps names left); prod `test` safe. Rebuild
      completed the forum build + canon + projections (laptop slept and killed the app at the tail, after the
      numbers were written — so they're trustworthy).
    - **NEW residual surfaced: `WOS/AMBIGUOUS_ISSN_MATCH` 9 → 83** (the M5 orphan-metric report flagged the
      same 83 — "83 WoS journals have metric facts but resolve to no forum"). **Diagnosed:** the fix onboards
      the ~198 journals that previously collided+skipped, densifying the registry, so more WoS journals' ISSN
      now matches multiple forums and the H55.6 primary-ISSN tiebreak couldn't resolve them — *because the
      tiebreak was gated on `scopus` and never ran for WoS at all*. Net: 224→103, the *dangerous* EXTERNAL_ID
      churn gone + ~115 more journals onboarded.
    - **RESOLVED (commit `288b545`):** a mongosh diagnostic against `scholardex_h66` proved all 83 had exactly
      one candidate carrying the journal's primary print ISSN (resolvable: 83 / not-resolvable: 0 / no-primary:
      0). Dropped the `scopus &&` gate so the primary-ISSN tiebreak applies to **every source** in
      `ForumMergeEngine.ingest`; a genuine multi-candidate tie (all candidates carry the primary) still
      conflicts. Locked with `runWosOnboardingDisambiguatesByPrimaryIssnInsteadOfConflicting` (mirrors the
      Scopus equivalent). Full suite green (1010 tests).
    - **CONFIRMING REBUILD (2026-06-18, isolated `scholardex_h66`):** AMBIGUOUS **83 → 73** (not the predicted
      ~0). The pre-fix diagnostic that claimed "all 83 resolvable" was **wrong** — it read `wos.issn` when the
      field is `wos.primaryIssn`, so its premise was bogus. The fix is still correct and kept: it resolved 10
      genuine single-primary forum-dup cases, and `EXTERNAL_ID_ALREADY_LINKED` stays **0** (the M8-A.2 churn
      regression stays dead). Final forum-conflict tally: AMBIGUOUS 73 + DEDUP_NAME_MISMATCH 11 +
      CROSS_JOURNAL_ISSN 10 + INVALID_ISSN 2. Prod `test` safe at 32,714.

- **M9 — WoS journal-identity duplication (the real cause of the 73 ambiguous). SCOPED, not yet built.**
  - **Definitive root cause (mongosh-proven on `scholardex_h66`):** exactly **73 of 25,871** `wos.journal_identity`
    records are unlinked to any forum, and they are *precisely* the 73 `FORUM/AMBIGUOUS_ISSN_MATCH` orphans. Each
    is a **duplicate identity for a journal already represented by a linked record** — same normalized title
    (73/73 identical), with the **print/eISSN roles swapped** between the two records. Example (IJCIS): linked
    `jid_073a` primary `1875-6883`; orphan `jid_1a01` primary `1875-6891` + eIssn `1875-6883`. The orphan then
    matches *two* forum candidates at WoS-onboard → ambiguous → never links; its metrics still reach reporting
    via the linked sibling, so impact is **cosmetic quarantine, not data loss**.
  - **Why the duplicate is minted** (`WosIdentityResolutionService` + `WosFactBuilderService`): all 73 were
    created via the **clean create path** (no `conflictType`; the WoS identity layer's own conflict count is 1),
    i.e. `findCandidates` returned **zero** at creation. Because the two source rows for one journal carry
    *different* ISSN-token sets (print-only vs print+eISSN), they (a) hash to different `identityKey`s — miss the
    `identityKey` cache; (b) sit in different `tokenSetResolutionCache` buckets — miss the token cache; (c) and
    `prefetchedCandidatesByTokenSet` is a **chunk-start DB snapshot never updated with mid-chunk creates**
    (`WosFactBuilderService` ~L435/759). Two same-journal rows in one chunk (≥34/73 provably created within 5 s)
    both prefetch empty → both call `createIdentity` → duplicate. `fact-chunk-size=1000`.
  - **Fix options:**
    - **(A) Post-build identity dedup pass (RECOMMENDED — robust, deterministic, independently testable):** after
      fact-building, group `wos.journal_identity` by connected ISSN component **and** matching `normalizedTitle`;
      merge each group into one canonical jid (union ISSN tokens/aliases/alt-names), repoint the duplicates'
      metric/category/coverage facts' `journalId` to the winner, delete the losers. Catches *every* creation path
      (stale prefetch, cross-chunk, future conflict-create). Only touches the ~73 duplicates' facts, so cheap.
      Add a focused service + unit test; validate by the unlinked-identity count dropping 73→~0 on rebuild.
    - **(B) Live in-chunk candidate index (prevention, complementary):** seed the prefetch pool as a mutable
      token→identity index and register newly-created identities into it during the chunk so a sibling row later
      in the same chunk merges instead of creating. Fixes only the within-chunk subset; racier; doesn't retro-fix
      cross-chunk. Good as a follow-up hardening, not the primary.
    - **Recommendation:** ship **(A)** as M9; optionally fold in **(B)** later. (A) alone is predicted to take
      unlinked WoS identities 73→~0 and forum AMBIGUOUS 73→~0.
  - **M9 IMPLEMENTED (option A) — code done, rebuild pending.** New `WosJournalIdentityDeduplicationService`
    (`service/importing/wos/`): loads all `wos.journal_identity`, union-finds groups that share an ISSN token
    **and** the same `normalizedTitle` (title guard keeps eISSN-sharing siblings apart), picks the most
    ISSN-complete record as winner (tie → earliest createdAt → smallest id), folds the losers' tokens/aliases/
    alt-names into the winner, repoints the losers' metric/category/coverage facts to the winner (dropping any
    that would collide on the winner's unique key — the duplicates describe one journal), and deletes the
    losers. Wired into `WosBigBangMigrationService.run` **after fact-build, before projections/forum
    onboarding** (so each journal presents one ISSN-complete candidate and links cleanly). Unit tests:
    `WosJournalIdentityDeduplicationServiceTest` (merge + collision-drop, title-guard non-merge, no-op) +
    migration test verifies it runs on full-run / skips on dry-run. Full suite green except **2 pre-existing
    `@WebMvcTest` contract tests** (`AdminInitializationController{,Security}ContractTest`, 41 cases) that fail
    on a context-load — the controller has ~16 `final` deps but the tests mock only 8 (missing ScopusDataService,
    Doaj/Erih data+onboarding, ForumReconcileAudit, WosImportEventIngestion, ScholardexForumDedup); unrelated to
    M9, broken since the controller's DOAJ/ERIH churn. **Confirming rebuild pending** (predict unlinked WoS
    identities 73→~0, forum AMBIGUOUS 73→~0).
  - **M9 CONFIRMING REBUILD (2026-06-18, isolated `scholardex_h66`):** dedup ran — `groupsMerged=82
    identitiesMerged=82 metric[repointed=964 dropped=44] category[repointed=1472 dropped=24]`. Forum
    AMBIGUOUS **73 → 55**, unlinked WoS **73 → 55** (resolved 18), EXTERNAL_ID still **0**. Not ~0: the 55
    remaining are duplicate journals whose two WoS identity records share **no clean key** (disjoint ISSN sets
    and abbreviated-vs-full titles), so identity-layer dedup can't catch them. Diagnosed deeper: **all 55 (like
    the 73) have one candidate forum that vanished** — the final membership dedup (`forumsDeleted~=188`, runs
    *after* WoS onboarding) removes a transient duplicate forum the WoS journal was ambiguous against, stranding
    it. A forum-ordering problem, not an identity one.

- **M10 — post-dedup WoS re-link (resolves the transient-duplicate-forum residual). IMPLEMENTED, rebuild
  pending.** After the membership dedup collapses duplicate forums, re-drive the still-OPEN
  `AMBIGUOUS_ISSN_MATCH`/`AMBIGUOUS_NAME_AGG_MATCH` WoS journals back through the engine; with the duplicate
  candidate gone each resolves to the single survivor and links.
  - `WosScholardexOnboardingService.relinkAmbiguousWosForums` — loads the open WoS forum-ambiguity conflicts,
    re-ingests only those journals (cheap, ~55) via `ForumMergeEngine.startWosRun` against the deduped registry.
  - `ForumMergeEngine`: generalized `resolveOpenForumAmbiguityConflict` from Scopus-only to **source-aware**
    (closes the WoS conflict on a now-unambiguous link, resolver `wos-forum-onboarding`), and `startWosRun` now
    loads `openForumConflictKeys` so the re-link can close them. The three ingest success-paths call resolve for
    every source (was gated `if (scopus)`).
  - `ScholardexForumBuilder.buildScopusForums` runs the re-link **after** membership dedup, only when the dedup
    merged something; added `wosRelink` to `ScopusForumBuildResult` + the build log line.
  - Tests: `relinkAmbiguousWosForumsResolvesNowUnambiguousJournalAndClosesConflict` + no-op guard;
    `ScholardexForumBuilderTest` asserts order (…→ membership dedup → relink) and skip-when-no-dedup. Application
    + WoS suites green (978).
  - **M10 CONFIRMING REBUILD (2026-06-18, isolated `scholardex_h66`) — SUCCESS.** `Forum build complete: …
    membershipDedupMerged=188 wosRelinked=55`. Forum **AMBIGUOUS_ISSN_MATCH 55 → 0 OPEN** (all 55 flipped to
    RESOLVED by the `wos-forum-onboarding` resolver); **unlinked WoS identities 55 → 0 / 25,788** (every WoS
    journal now resolves to a forum); EXTERNAL_ID still **0**. Prod `test` safe at 32,714.
  - **Final forum-conflict residual = 24, all genuine** (no churn, no orphans): `FORUM_DEDUP_NAME_MISMATCH` 12
    (same-ISSN, conflicting names — human review), `FORUM_CROSS_JOURNAL_ISSN` 10 (H57 eISSN-bridge guard firing
    correctly on distinct journals), `NORMALIZATION_INVALID_ISSN` 2 (malformed source ISSNs). North-star met:
    **448 baseline → 24 genuine**, with the 421 EXTERNAL_ID churn eliminated and the entire ambiguous-orphan
    class (M8→M9→M10) resolved.
  - **Still pending regardless:** full-feed validation (SourceList/CiteScore/ERIH/DOAJ/MJL/Books) — Scopus+WoS-only
    so far.
  - **M8-A.2 original plan (for reference):** The substantive change:
    1. `ScholardexForumBuilder.buildScopusForums` → add `runWosForumOnboarding` as the **last** forum step
       (dedup → Scopus canon → ERIH → DOAJ → **WoS** → final dedup); extend the result record + the re-dedup
       trigger to include WoS.
    2. `ScopusBigBangMigrationService.runFull` → call `linkPublicationsToWos` **after** publication
       canonicalization (where the publications exist).
    3. **Stop the WoS rebuild from onboarding forums** — remove the two `runWosOnboarding` calls in
       `WosBigBangMigrationService` (lines ~136, ~290) so it builds WoS *facts* only and forums aren't
       double-onboarded. **Blast radius:** `WosBigBangMigrationService.run` has a standalone caller
       (`RankingMaintenanceFacade.runWosBigBangMigration`, the admin "rebuild WoS" flow) + integration tests
       (`WosAdminInitializationWorkflowIntegrationTest`) that expect WoS forums after a WoS rebuild — those
       must be re-pointed (forums now come from the unified ForumBuilder, not the WoS rebuild).
    Correctness is a *data* outcome — unit tests verify call order, but the **isolated rebuild validates the
    registry** (forum count, conflicts ≤ residual, WoS-name wins display, 0 orphaned publication links). Per
    plan, run that rebuild right after M8-A.2 — it doubles as the held M4–M7 validation.
  - **M8-B unify the rebuild into one full-feed DAG — DONE (functional unification; deeper structural retire
    deferred).** Surfaced while scoping full-feed validation: `PipelineRebuildService.rebuildAllDerived` was
    the single entry but only ingested Scopus JSON + WoS — the curated feeds (Source List / CiteScore / Book
    list / MJL / DOAJ / ERIH) had to be hand-imported via separate admin endpoints, and M9 dedup was reachable
    only inside `WosBigBang.run`. So no single call produced the full-feed registry, blocking a clean
    validation. Fix: fold every source into the unified rebuild's ingest phase, dependency-ordered —
    - MJL (WoS source stream) → `WosBigBangMigrationService.run`, after the WoS-dir ingest, before buildFacts
      (so coverage facts + the M9 dedup fold in);
    - Source List / CiteScore / Book list (Scopus source streams) → `ScopusBigBangMigrationService.runFull`,
      after the Scopus JSON import, before `buildFactsFromImportEvents` (so the curated FORUM backbone is
      present when the registry canonicalizes + M10 relink runs in `buildScopusForums`);
    - DOAJ / ERIH (match-only reference snapshots) → `PipelineRebuildService`, between the WoS build and the
      Scopus forum build (their onboarding reads the reference).
    All config-driven (`h66.*` paths in `application.properties`); a blank path or missing file logs a warning
    and skips, so Scopus-JSON-only rebuilds and unit tests are unaffected. Tests:
    `PipelineRebuildServiceTest` asserts reference ingest fires between WoS and Scopus builds + skips when
    unset. App + importing suites green (1317). **One `rebuildAllDerived` now produces the complete full-feed
    registry** — the prerequisite for full-feed validation.
    - **Deferred (not required for validation):** the deeper structural retire of `runFull`/`wosRebuild` into
      explicit named builder stages (ForumBuilder → RankingBuilder → Publication/CitationBuilder) and killing
      `runWosOnboarding`'s standalone caller. The orchestration is now *functionally* one DAG; the class-level
      consolidation can follow once full-feed numbers are validated.

### Open design choices

- **WoS role + identity ordering:** RESOLVED (2026-06-17). WoS is **create-or-match, last in the identity
  layer** (identity-of-last-resort), not a first-class identity creator and not strict ranking-only — keeps
  historical/defunct WoS journals covered while the curated lists (Source List/MJL/ERIH/DOAJ) win identity by
  running first. Authority order `Source List → MJL → ERIH → DOAJ → WoS`. MJL + DOAJ promoted to identity
  sources; WoS metrics + CiteScore demoted to ranking-only. See the target-ordering block above.
- **Display-name authority:** RESOLVED (2026-06-17) — **prefer the WoS name when present** (→ Scopus →
  Source List). WoS stores the full `journalTitle` (falls back to `abbrJournal` only if blank —
  `OfficialWosJsonImportEventParser:43-45`), so this yields full titles, not ISO abbreviations. **Normalize
  an all-uppercase WoS title to Title Case** before storing (a chunk of WoS `journalTitle`s are ALL-CAPS,
  e.g. "NOISE CONTROL ENGINEERING JOURNAL"; Scopus names are uniformly Title Case). This is the one place
  WoS is authoritative despite being identity-of-last-resort: **WoS is last for identity, first for the
  display name.** Implement at M4: flip `mergeForum`'s name rule (today `firstNonBlank(target.getName(),
  wosName)` makes WoS lose) to WoS-name-wins-when-present + the casing normalizer; update
  `ForumMergeEngineTest.mergeForumAppliesScopusPreferredIssnNameAggAndAliases` (asserts Scopus name wins
  today). `nameNormalized` (dedup key) is unaffected — only the display `name` casing changes.
- **ERIH create-or-match:** RESOLVED (2026-06-17) — **create-or-match**. ERIH becomes a ForumBuilder source
  (`idType=ERIH`) routed through `ingest`; it mints forums for ERIH-only humanities venues (unblocks non-STEM)
  and the engine sets `erihIds` on the resolved/created forum. Done in M3.
- **Branch + red windows:** carry the rewrite on `codex/h66b-builders`, accepting a red pipeline between
  milestones, rather than keeping `main`-green every commit.

## Session handoff (resume here — state as of commit `c686487`)

**Branch:** `codex/h66b-builders` (off `codex/h66-forum-registry`). All H66 (A–E, B, C) + the H66B redesign +
M0/M1a/M1b/M1c-part1 are committed. `WosScholardexOnboardingService` is down to **927 lines**.

**Done so far (all green, behavior-equivalent moves):**
- `ForumIdentityNormalization` (identity normalize + token extraction; service delegates to it).
- `ForumIdentityIndex` + `ScopusForumIndex` (top-level; the O(1) token-index perf heart).
- `ConflictRecorder` (`openConflict` + `markConflictLink`; service delegates). `SourceLinkWriter` is already
  `ScholardexSourceLinkService` — reuse it, don't make a new one.

**Immediate next step — extract `ForumMergeEngine` (the M1c core, highest risk: core forum identity).**
The two entry methods `runWosOnboarding` / `runScopusForumCanonicalization` (in `WosScholardexOnboardingService`)
each: build the indexes → loop source records → find-or-create-or-conflict → batch source-link flush. The
engine to extract (current line numbers in that file):
- `mergeForumFromScopus` (≈349), `upsertForumFromWos` (≈430) — the per-record find-or-create/merge (these are
  the two ~90%-duplicate methods that collapse into one `ingest(ForumSourceRecord)` at **M2**).
- `mergeForum` (≈548) — the core merge.
- `persistForumOrRecordConflict` (≈528) — save-or-DuplicateKey→conflict.
- `buildCanonicalForumId` (≈863), `buildPrimaryIssnIndex` (≈846), `normalizedIssnSet` (≈793),
  `addSecondaryIssn` (≈829), `isCrossJournalToken` (≈841) — ISSN token-hygiene / cross-journal-bridge guard.
- `loadForumSourceLinks` (≈180), `linkedCommand`/`conflictCommand` (≈205/213) — source-link command building.
- `resolveOpenForumAmbiguityConflict` (≈763) — closes stale ambiguity conflicts (Scopus-specific).

**The gotcha:** per-run shared state — `primaryIssnIndex` (field, line 80, rebuilt per run via
`buildPrimaryIssnIndex`) and the threaded `linkCommands` list + `existingForumLinks` map. The engine must own
these as per-invocation state (pass a context/builder object, or make the engine stateful-per-call), not as a
Spring-singleton field. Keep the indexes + ForumMergeSafetyRule + ConflictRecorder as injected collaborators.
Approach: move the methods into `ForumMergeEngine`, have the two onboarding methods delegate (strangler), keep
`WosScholardexOnboardingServiceTest` green at each step (it's the behavior net — reflective white-box tests
build `ScopusForumIndex` directly now).

**Live-rebuild verification recipe (isolated; NEVER prod `test`):**
1. Mongo isolation: connect to a **separate db** via `--spring.mongodb.uri=mongodb://localhost:27017/scholardex_h66`
   (Boot-4 key; `spring.data.mongodb.*` is DEAD and silently hits prod `test`). Postgres: `core_h66`.
2. Boot: `JAVA_TOOL_OPTIONS=-Xmx6g ./gradlew bootRun --args='--spring.profiles.active=agent-dev --server.port=8282 --spring.mongodb.uri=mongodb://localhost:27017/scholardex_h66 --spring.datasource.url=jdbc:postgresql://localhost:5432/core_h66'`
3. **Confirm isolation before any write:** `GET /admin/initialization/forum/reconcileAudit` → `forumsTotal:0`
   means scholardex_h66 (safe); `32714` means PROD — abort.
4. Full rebuild: `POST /admin/initialization/rebuildAllDerived --data-urlencode confirmation=RESET` (re-ingests
   Scopus JSON + WoS; does NOT ingest CiteScore/MJL/SourceList/DOAJ/ERIH — import those separately:
   `/scopus/importSourceList`, `/scopus/importCiteScore`, `/wos/importMjl`, `/forum/importDoaj`,
   `/forum/importErih`, then re-run `/scopus/buildFacts` to fold). Full feed paths in
   [docs/rebuild-runbook.md](../../rebuild-runbook.md) H66 section.
5. Conflict tally: `mongosh scholardex_h66 → db.getCollection("scholardex.identity_conflicts").aggregate([{$match:{entityType:"FORUM"}},{$group:{_id:"$reasonCode",n:{$sum:1}}}])`.
6. Teardown: stop bootRun; `db.dropDatabase()` on scholardex_h66; verify `test` forum_facts still 32714.

**2026-06-17 baseline (the bar to beat):** 448 forum conflicts = `EXTERNAL_ID_ALREADY_LINKED` **421** +
`DEDUP_NAME_MISMATCH` 18 + `CROSS_JOURNAL_ISSN` 7 + `INVALID_ISSN` 2. Goal: → ~27 (kill the 421 churn), with
forum-build/rebuild time ≤ baseline. 39,957 forums, healthy, 100% WoS-linked resolve by FK; DOAJ 9,294 /
ERIH 6,261 / DOAJ-from-ERIH 2,144 membership; MJL coverage was 0 (D6 gap, fix in M7).

## Verification (per milestone)

Isolated full rebuild (the H66 recipe: throwaway `scholardex_h66` + `core_h66` on port-isolated Mongo via
`--spring.mongodb.uri`; **never** prod `test`). Three gates, all measured each milestone:

- **Integrity:** reconcile audit `healthy=true`, 0 orphaned publication links, 100% WoS-linked forums resolve
  metrics by FK.
- **Conflicts → residual (not parity):** drive `EXTERNAL_ID_ALREADY_LINKED` from **421 → ~0** and total forum
  conflicts from **448 → the genuine ~27** (cross-journal ISSN + dedup name-mismatch + invalid ISSN). The
  goal is *eliminating* the churn, so a milestone that merely reproduces 448 has not succeeded.
- **Timing (non-negotiable):** forum-build phase and total rebuild time **≤ the current baseline** at every
  milestone (perf invariants above). A regression here blocks the milestone regardless of correctness.

Plus: MJL coverage present (SCIE/SSCI/AHCI/ESCI membership > 0) by M7, and a baseline timing capture before
M1 so the per-milestone timing gate has a reference.

## Already landed (H66, forward-compatible)

- A1–A3 (registry views, CiteScore/MJL loaders), A4 (DOAJ), A5 (ERIH + C1 part 2 dedup), B2/B3 (forum-keyed
  FK projection + scoring), C1/C2 (resolve-or-enrich + reconcile audit).
- **D1/A6** Scopus Source List loader (serial forum backbone) — feeds ForumBuilder.
- **D3/D4** forums-first ordering + publication resolve-and-link (strict link-only) — the in-place
  approximation B66B.1–.2 make structural.
