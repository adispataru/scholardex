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

## Migration sequence (keep the pipeline green at every step)

Strangler pattern — introduce builders as the new orchestration entry, delegating to existing services
first, then internalize and delete the old glue. Re-run the isolated live rebuild + reconcile audit after
each step; compare conflicts to the 448-forum baseline.

- **B66B.1 — ForumBuilder (facade first).** — **DONE.** `ScholardexForumBuilder.buildScopusForums(batchId,
  correlationId)` owns the Scopus-side forum build in one forums-first sequence (dedup → Scopus
  canonicalization → ERIH onboard → conditional erih-dedup), returning a `ScopusForumBuildResult` record. The
  three inline copies in `ScopusBigBangMigrationService` (runFull / buildFacts / incremental) now route
  through it; the three forum services moved out of that orchestrator into the builder. As a bonus the
  `runFull` path now runs the **whole** forum build (incl. ERIH) *before* publication/citation
  canonicalization — true forums-first (was: dedup+canon, then pub/cit, then ERIH). buildFacts/incremental
  gain the idempotent ERIH steps (consistency). Tests: `ScholardexForumBuilderTest` (ordered delegation +
  conditional erih-dedup); migration test updated to mock the builder. WoS journal onboarding still runs in
  the WoS rebuild — folded in at B66B.6.
- **B66B.2 — internalize forum logic + remove publication forum derivation.** Move the scattered logic into
  ForumBuilder; delete `upsertForumFact`'s publication path (the `fromPublication` branch) so Scopus parsing
  emits only `ScopusPublicationFact`. Option-B venues minted by ForumBuilder from a publication-venue stream.
- **B66B.3 — RankingBuilder.** Extract WoS metric/category projection + enrichment; add CiteScore scores
  (D2). Forum-keyed; runs after ForumBuilder.
- **B66B.4 — PublicationBuilder.** Make venue resolution explicit against the registry; generalize input to
  publication-event sources. Then **CitationBuilder**.
- **B66B.5 — BookBuilder** (H66 Move E): `book_facts`, `bookId` on publications, `aggregationType` branch,
  point book scoring at the registry.
- **B66B.6 — Orchestrator reorg.** Replace the source-interleaved BigBang flow with the entity DAG in
  `PipelineRebuildService`: parse sources → ForumBuilder → RankingBuilder → PublicationBuilder →
  CitationBuilder. Retire the source-coupled `runFull` interleaving (H66 D5/D6/D7 fold in here: one
  forums-first pass, MJL coverage built, config for feed paths).

## Verification

- **Behavior parity:** isolated full rebuild (the H66 recipe: throwaway `scholardex_h66` + `core_h66`,
  `--spring.mongodb.uri`) after each step; reconcile audit `healthy=true`, 0 orphaned publication links,
  100% WoS-linked forums resolve metrics by FK.
- **Conflict regression:** `EXTERNAL_ID_ALREADY_LINKED` should fall from **421 → ~0** once forum-building is a
  single ordered pass (B66B.1–.2). Compare the full `identity_conflicts` tally to the 2026-06-17 baseline
  (448 forum conflicts).
- **MJL coverage present** (B66B.6): SCIE/SSCI/AHCI/ESCI membership > 0.

## Already landed (H66, forward-compatible)

- A1–A3 (registry views, CiteScore/MJL loaders), A4 (DOAJ), A5 (ERIH + C1 part 2 dedup), B2/B3 (forum-keyed
  FK projection + scoring), C1/C2 (resolve-or-enrich + reconcile audit).
- **D1/A6** Scopus Source List loader (serial forum backbone) — feeds ForumBuilder.
- **D3/D4** forums-first ordering + publication resolve-and-link (strict link-only) — the in-place
  approximation B66B.1–.2 make structural.
