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
    - **Next:** the big one — extract `ForumMergeEngine` (`mergeForum` / `upsertForumFromWos` /
      `mergeForumFromScopus` + the ISSN token-hygiene / cross-journal guard + `buildCanonicalForumId` + the
      candidate/safe-merge decision). Highest-risk piece (core forum identity); do as its own focused pass.
      Then collapse the two onboarding methods into one `ingest(ForumSourceRecord)` (M2).
- **M2 — Unify WoS + Scopus ingestion through `ForumMergeEngine.ingest(ForumSourceRecord)`.** Delete the two
  duplicate onboarding methods. ForumBuilder feeds Source List + WoS identity records through one engine.
  Gate: conflicts ≤ baseline and trending down; forum-build time ≤ baseline.
- **M3 — Pure publications + conference-venue source.** Remove the publication→forum-fact write; ForumBuilder
  ingests the dedup'd conference-venue stream + ERIH (now create-or-match) + user-defined. Gate:
  `EXTERNAL_ID_ALREADY_LINKED` → ~0; total forum conflicts → residual; null-forumId count tiny.
- **M4 — RankingBuilder.** CiteScore scores (D2) + WoS metrics/category + WoS score-only→quartile enrichment
  → forum-keyed rankings. CiteScore stops being a forum-identity feed.
- **M5 — PublicationBuilder + CitationBuilder.** Clean builders resolving against the registry; source-plural
  input shape (OpenAlex/DBLP/GS-ready).
- **M6 — BookBuilder.** `scholardex.book_facts` (streamed Book List), `bookId` on publications, venue branch
  on `aggregationType`, book scoring resolves the registry.
- **M7 — DAG orchestrator.** One orchestrator: parse → ForumBuilder → RankingBuilder → PublicationBuilder →
  CitationBuilder → projections. Retire `runFull`/`wosRebuild`/`PipelineRebuild` orchestration (their parsing
  survives as parser components). Folds in H66 D5/D6/D7 (one forums-first pass, MJL coverage, feed config).
  `runWosOnboarding`'s standalone caller dies here — no more "WoS rebuild that onboards forums."

### Open design choices

- **ERIH create-or-match:** the unified engine naturally lets ERIH *create* forums (the deferred ERIH-only
  humanities venues), not just match. Lean **create** (unblocks non-STEM; clean) — confirm at M3.
- **Branch + red windows:** carry the rewrite on `codex/h66b-builders`, accepting a red pipeline between
  milestones, rather than keeping `main`-green every commit.

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
