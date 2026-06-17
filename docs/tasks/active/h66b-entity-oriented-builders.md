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

- **M4 — Complete the IDENTITY layer.** Promote **MJL** and **DOAJ** to ForumBuilder sources: new
  `ForumIdType.MJL`/`DOAJ` + `ofMjl`/`ofDoaj` mappers + onboarding through `ingest` (create-or-match, like
  ERIH). Make **WoS onboarding run *after* the curated lists** as create-or-match (it already goes through
  `ingest`; the change is order + treating it as identity-of-last-resort, not first). Establish the
  `Source List → MJL → ERIH → DOAJ → WoS` authority order. Gate: registry built from curated lists; WoS-only
  historical journals still get forums; conflicts stay at residual.
- **M5 — RankingBuilder (WoS + CiteScore stop creating identity).** WoS metrics (AIS/IF/RIS/JIF) + CiteScore
  (CiteScore/SJR/SNIP) + WoS score-only→quartile enrichment **attach to the registry by FK** — CiteScore and
  WoS metric facts no longer mint forums. Plus the membership feeds (DOAJ/ERIH/MJL) → forum-keyed views.
- **M6 — PublicationBuilder + CitationBuilder (folds old M3-B).** Publications resolve venue against the
  finished registry and emit **only** `ScopusPublicationFact` — remove the inline publication→`forum_facts`
  write; the conference-venue option-B becomes its own deduplicated `ForumSourceRecord` stream feeding the
  IDENTITY layer. Source-plural input shape (OpenAlex/DBLP/GS-ready).
- **M7 — BookBuilder.** `scholardex.book_facts` (streamed Book List), `bookId` on publications, venue branch
  on `aggregationType`, book scoring resolves the registry. **Streaming precedent set (2026-06-17):**
  `ScopusDataService.importSourceListXlsxFromPath` was rewritten from a full in-memory `XSSFWorkbook` to the
  POI SAX event reader (`OPCPackage`/`XSSFReader`/`XSSFSheetXMLHandler` + `ReadOnlySharedStringsTable`) — the
  May-2026 Source List (49,599 rows) has a part exceeding POI's 100 MB byte-array ceiling, which the full
  load tripped (`RecordFormatException`, the live-rebuild blocker). Parses in ~4s now. The Book List
  (475k rows) reuses this exact streaming pattern.
- **M8 — DAG orchestrator (enforces the identity → ranking → observation ordering).** One orchestrator:
  parse all sources → **ForumBuilder over the curated identity sources in authority order
  (`Source List → MJL → ERIH → DOAJ → WoS` create-or-match)** → RankingBuilder (WoS metrics + CiteScore,
  attach-only) → PublicationBuilder + CitationBuilder (resolve + option-B) → projections. Retire
  `runFull`/`wosRebuild`/`PipelineRebuild` (their parsing survives as parser components). This is where the
  current "WoS rebuild first, then Scopus runFull, feeds as side imports" structure is replaced by the strict
  layered order. Folds in H66 D5/D6/D7. `runWosOnboarding`'s standalone caller dies here — no more "WoS
  rebuild that onboards forums."

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
