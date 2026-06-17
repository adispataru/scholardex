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
  sources through `ForumMergeEngine.ingest(ForumSourceRecord)`:
  - **ERIH create-or-match** — `ErihOnboardingService` stops being a bespoke match-only `erihIds` writer;
    ERIH journals become `ForumSourceRecord`s (`idType=ERIH`) that the engine matches *or creates*. The
    `erihIds` FK is set by the engine on the resolved/created forum (so the existing ERIH membership
    projection still reads it). Add `ERIH` to `ForumIdType` + the forum id-list it lands in.
  - **Conference-venue source** — extract the dedup'd conference-venue stream (the ~2,065 mostly-unprofiled
    venues no curated list has) from publication facts as `ForumSourceRecord`s (option-B minimal forums,
    provenance-tagged), and **remove the publication→`scopus.forum_facts` write** (D4's `fromPublication`
    flag becomes structurally impossible — publications emit only `ScopusPublicationFact`). These two are one
    change: the venue source replaces the publication-derived forum write, so conference forums are minted
    once, by the ForumBuilder, not double-minted.
  - **(later/optional) user-defined** — same shape (`idType=USER`).
  - Gate: `EXTERNAL_ID_ALREADY_LINKED` → ~0; total forum conflicts → the genuine residual; null-forumId count
    tiny; ERIH-only venues now have forums (non-STEM unblocked). Measured on the isolated rebuild.
- **M4 — RankingBuilder + membership feeds.** CiteScore scores (D2) + WoS metrics/category + WoS
  score-only→quartile enrichment → forum-keyed rankings; CiteScore stops being a forum-identity feed. **Plus
  the attach-to-existing-forum feeds split out of the old M3:** DOAJ membership + ERIH membership (both already
  stage-4 projections keyed on the resolved forum / its `erihIds` FK). All resolve against the finished
  registry.
- **M5 — PublicationBuilder + CitationBuilder.** Clean builders resolving against the registry; source-plural
  input shape (OpenAlex/DBLP/GS-ready).
- **M6 — BookBuilder.** `scholardex.book_facts` (streamed Book List), `bookId` on publications, venue branch
  on `aggregationType`, book scoring resolves the registry.
- **M7 — DAG orchestrator.** One orchestrator: parse → ForumBuilder → RankingBuilder → PublicationBuilder →
  CitationBuilder → projections. Retire `runFull`/`wosRebuild`/`PipelineRebuild` orchestration (their parsing
  survives as parser components). Folds in H66 D5/D6/D7 (one forums-first pass, MJL coverage, feed config).
  `runWosOnboarding`'s standalone caller dies here — no more "WoS rebuild that onboards forums."

### Open design choices

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
