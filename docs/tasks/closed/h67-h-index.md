# H67 h-index (Hirsch) computation

**Status:** Planning
**Created:** 2026-06-16 (from the [standards capability assessment](../../standards-capability-assessment.md))

## Goal

Compute the candidate's **Hirsch index** from our citation data and expose it as a scoring/threshold input.
Currently nothing computes it (confirmed: no indicator/data in the live DB).

## Who needs it (cross-cutting)

- **Chimie**: WoS h-index, hard threshold ≥13 (Prof) / ≥9 (Conf).
- **Geografie**: Hirsch (WoS, **self-citations excluded**), ≥4/≥3/≥2/≥1 by position.
- **Fizica (H65)**: `h` column in the A/I/P/C/h/T summary.
- **Istorie (FLIT)**: alternative gate — **Google Scholar h ≥3 OR ≥70 citations**.

## Approach

- `h = max k such that k of the candidate's publications each have ≥ k citations`, computed over the
  candidate's confirmed publications using our per-publication citation data (`citedByCount` /
  `scholardex.citation_facts`).
- **Per-source nuance**: chimie wants WoS h; istorie wants Google Scholar h (→ depends on [H66] GS data);
  default Scopus/our-canonical otherwise. Make the citation source a parameter.
- **Self-citation exclusion** (geografie) — reuse the candidate/co-author exclusion logic already in the
  citation scoring path.
- **Aggregate, not per-item**: h is a single number over the whole corpus, unlike current per-publication
  indicators. Decide where it lives — a new aggregate-metric indicator kind, or computed in the report
  summary layer (like physics' T20 summary row).
- **Threshold**: per-position h thresholds via the criteria model; support the "h OR citation-count"
  fallback (istorie) — ties to [H68].

## Open decisions

- Aggregate-metric indicator kind vs report-summary computation.
- Citation source per domain (WoS / Scopus / Google Scholar) and how to represent "which h".
- Self-citation exclusion default; whether to also expose total-citation-count (for the istorie OR-gate).

## Method — source-attributed h off the citation graph (validated 2026-06-22)

The per-source h ("WoS h", "Scopus h") is computed by attributing each **incoming citation** to the indexing of the
forum the **citing** paper sits in: `citation_facts.citingPublicationId → publication_facts.forumId →
forum_facts.{scopusForumIds, wosForumIds}`. Per the candidate's pub:
- `scopusCitationCount` = # incoming citations whose citing forum is Scopus-indexed.
- `wosCitationCount` = # … WoS-indexed.
- `graphCitationCount` = # incoming citations total (internal graph), for a comparable graph-based total-h.
Then `h = max k such that k pubs each have ≥ k` over each count. The existing `citedByCount`-based h stays as the
"Scholardex h" (source-reported totals). Relationship to surface: Scholardex/graph-total h ≥ Scopus h, ≥ WoS h.

**Feasibility (current canonical data):** forums carry indexing (51,531 Scopus-indexed, 26,338 WoS-indexed); of
512,200 citation edges, **81% are classifiable as Scopus-venue, 74% as WoS-venue** (~18% from un-indexed/unresolved
venues). The citing pub is always in-corpus (internal-only graph).

**Validation against ground truth (Adrian Spătaru, real Scopus-h 5 / WoS-h 5, 27 pubs held):** computed Scholardex-h
**5** (exact), Scopus-venue h **4**, WoS-venue h **4** — off by one. A poorly-covered researcher (Florin Rosu, 5 pubs
held) computed 3/2 vs real 5/5. So accuracy tracks corpus completeness; label the metric **"Scholardex-computed
(indicative)"**, not the official index.

**The off-by-one has two causes, one fixable:**
1. *Inherent* — OpenAlex sees fewer citations than Scopus's own DB for some boundary papers.
2. *Fixable — the WoS conference-index gap.* Our `wosForumIds` come only from the WoS **journal** list (all 26,338
   WoS forums are journals; **0 conferences**). We never loaded the WoS **CPCI** (Conference Proceedings Citation
   Index). So WoS-indexed conferences are misclassified: e.g. **all ~19 SYNASC proceedings forums are
   `scopus=True, wos=False`**, so a SYNASC→paper citation is dropped from WoS-venue h (this is exactly Adrian's
   missing WoS citation). **1,014 conference forums are Scopus-indexed but not WoS-indexed.** Onboarding a WoS CPCI
   list (tag conference forums with `wosForumIds`, like the journal Master List) is the WoS accuracy lever — tracked
   as a **separate follow-up (`H76`)**, not a blocker for building the computation.

## Slices

- **S1 — per-pub citation source-split (projection). DONE + verified (commit `7863b12`, Flyway V15).** The Scopus
  projection build classifies each pub's incoming citations by the citing paper's forum indexing
  (`applyCitationSourceSplit`) and persists `graph/scopus/wos_citation_count` on `scholardex_publication_view`.
  **Live verify (2026-06-22):** corpus totals match the offline analysis exactly (418,462 Scopus-venue / 383,858
  WoS-venue / 512,200 graph citations); Adrian's SQL-computed h = scopus 4 / wos 4 / graph 5 / scholardex 5 over
  27 pubs (matches ground truth, off-by-one vs real 5/5 per the WoS-CPCI gap).
- **S2 — source-attributed h + surface. DONE (commit `34e86c2`).** Read the S1 columns back (added to both
  publication-view read mappers — `PostgresScholardexProjectionReadPort` + `…AdminReadPort`); compute the four
  h-indices via a new shared `HIndexCalculator` (generalized counting-sort; replaced the two duplicated `computeHIndex`
  copies). `UserPublicationsViewModel` carries an `HIndexBreakdown`; the publications page shows **H-Index + Scopus h +
  WoS h**, the source-attributed two labeled **indicative** (WoS notes the pending CPCI gap); graph-total kept in the
  model, not surfaced. No rebuild needed (S1 columns already populated). `HIndexCalculatorTest` + contract-test fixes
  green. (Live UI render not spot-checked — agent-dev principal has no linked researcher; logic + data path are proven
  via the unit tests + the S1 SQL verification on the same columns.)
- **S3 — self-citation exclusion (geografie).** A variant excluding citations whose citing pub shares an author with
  the cited pub (via authorship edges); expose total-citation-count for the istorie OR-gate.
- **S4 — wire h into criteria as an AGGREGATE indicator (the model's first non-additive metric).** Verification
  (2026-06-22) found the indicator engine is **map-then-sum**: per-item `ScoringService.getScore` → `Selector`
  (All/TopN, a *filter*) → SUM. h-index is **not additive** (a function of the whole citation distribution), so it
  fits neither a per-item scorer nor a Selector — it needs the *reduce* generalized from "sum" to a named aggregator.
  This is the first real gap in the criterion→indicator→scoring model (shared scope with **H69** scoring rework +
  **H68** gates/derived indicators). Split:
  - **S4a — the mechanism (buildable now, contained).** Add `IndicatorKind.HIndex(source, excludeSelf)` +
    `ScoringStrategy.HIRSCH` + the persisted round-trip (legacy strings `HINDEX_SCOPUS[_EXCLUDE_SELF]`, `HINDEX_WOS…`,
    `HINDEX_SCHOLARDEX…`); generalize the citation-indicator combine step to branch to a HIRSCH reduce = `hIndex` over
    the per-pub citation counts, reusing the existing `computeCitationView` (graph load + `excludeSelf` self-cit
    filter) plus a venue-source predicate (Scopus/WoS via `scholardex_forum_membership_view`, or the S1 cited-pub
    columns when `!excludeSelf`). Branch only on the new kind (additive path untouched; mirrors the inline-handled
    `GENERIC_COUNT` precedent) + the H69 regression sweep. Tracked under **H69** (scoring-engine generalization).
    **Progress:** 1/2 done (`9b1b3bb`) — the type foundation (`IndicatorKind.HIndex` + `ScoringStrategy.HIRSCH` +
    persisted round-trip). 2/2 done for the **non-self-cit** path — `UserReportFacade.buildReportScopedIndicatorDetail`
    branches on `isHIndexOutput()` and returns `hIndex` over the S1 columns (all four sources) instead of the sum;
    `HIndexCalculator.hIndexForSource` + tests green. **2/2b DONE** (`UserReportFacade.hIndexExcludingSelf`, on the H69
    scoped read `findForumCoreCollectionYears`): the `excludeSelf` path walks the citation graph, drops self-citations
    (citing pub shares a researcher author), and classifies by venue — **WoS = year-true Core** (SCIE/SSCI/AHCI in the
    citing paper's year), Scopus = current `scopusId`, GRAPH/SCHOLARDEX = all internal. Validated end-to-end via a SQL
    mirror: Adrian's year-true-Core self-cit-excluded WoS h = **2** (vs the S1-column 4 / real 5) — correctly lower
    because it drops ESCI + self-cites + (the big one for CS) **conference citations that WoS indexes via CPCI but we
    don't hold (H76)**; `hIndexOfCounts` test green. **Remaining:** the old "2/2b needs a WoS read flag" note below is
    obsolete (the scoped read covers it) — what's left is the interactive `buildIndicatorApplyView` preview branch.
    Historical note (superseded): the `excludeSelf` self-cit-filtered path needed the citation-graph walk + a forum→WoS-edition read over
    `scholardex_forum_membership_view` (NO schema change: WoS membership is already in the read model — editions
    `SCIE`/`ESCI`/`SSCI`/`AHCI`, plus JCR metrics on `scholardex_forum_metric_view`; an earlier note wrongly said the
    read model had no WoS flag because the `forum_view` *table* lacks one). Also pending: the interactive
    `buildIndicatorApplyView` preview branch (UI nicety).
    **WoS edition policy (decide before S4b):** `HIndexSource.WOS_VENUE` (and the S1 `wos_citation_count`) currently
    counts ANY `wosForumIds` venue, which **includes ESCI** (Emerging Sources). RO criteria usually mean the WoS
    **Core Collection** (SCIE/SSCI/AHCI). So WOS_VENUE likely should be Core-only (or split Core vs ESCI) — a real
    accuracy refinement, since today's WoS h is "WoS-incl-ESCI".
    **Year-true (stronger):** `scholardex_forum_category_view`/`_metric_view` are YEAR-TRUE (SCIE/SSCI from 1997, AHCI
    2021+, ESCI 2023+), so criteria-grade WoS-Core classification should be temporal — count a citation as WoS-Core iff
    the **citing venue was in SCIE/SSCI/AHCI in the citing paper's year** (citation-time), reusing the same year-true
    JCR views the journal scorers already use for AIS/IF/quartile-at-publication. That makes the accurate WoS-Core h
    inherently the 2/2b graph walk (per-citation year×venue lookup), not a precomputed scalar (S1's `wos_citation_count`
    is the current-membership fast approx). Decide "in Core when?" (citation-time vs current) + a pre-coverage-year
    fallback before S4b.
  - **S4b — per-domain activation. DISPLAY-ONLY chosen (2026-06-22); hard gate DEFERRED.** The computed year-true-Core
    WoS h UNDERCOUNTS official (Adrian 2 vs 5: coverage = OpenAlex's view, no CPCI conferences/H76, ESCI excluded), so
    a hard ≥13/9 pass/fail would false-fail real candidates. Decision: surface h as an **indicative** indicator, not a
    gate. **Code enabler DONE:** admin form offers the `HINDEX_*` output types (`AdminViewController.LEGACY_OUTPUT_TYPES`)
    + `HIRSCH` (already from the enum), so `IndicatorKind.of` builds the kind; the report's JSON detail
    (`IndicatorDetailResponseAssembler.buildDetail`) surfaces `summary.totalScore` = h generically (number shows on
    reports; per-item breakdown empty, interactive `buildIndicatorApplyView` preview falls through cleanly). **Activation
    is now a data/admin action** (create HIndex indicators on chimie/geografie/fizica via the enabled form), labeled
    indicative — no thresholds. **Hard gate (S4b-gate)** stays blocked on per-domain accuracy validation vs official WoS
    + H76; the istorie "h OR citations" gate needs **H68**.

## Status (2026-06-26) — roll-up + preview gaps CLOSED

The last code gaps are done: `UserReportFacade.scoreReport` (the report roll-up, shared by normal runs **and**
the H77 provisional path) and `buildIndicatorApplyView` (interactive preview) now branch on `isHIndexOutput()`, so an
h-index indicator scores its **h** everywhere instead of 0. A shared `computeHIndex(indicator, authors, publications)
→ [h, totalCit]` was extracted from the detail branch and reused by all three sites (detail / roll-up / preview), so
they agree by construction. No template change (the JS dashboard renders h from the detail JSON's `summary.totalScore`).
Tests: `hIndexIndicatorScoresItsHInTheReportRollUpNotZero`, `buildIndicatorApplyViewForHIndexReturnsTheComputedH`
(suite 2466/2466). **Live-verified** via a throwaway provisional run on the maths department: the `HIndex`
`IndicatorKind` (a record, persisted polymorphically) deserialized from Mongo cleanly and produced real per-person h
(Bogdan Sasu 21 / Adina 20 / Blaga 18 / Birtea & Tudoran 11 / …) — the `scoreReport` path proven end-to-end.

**H67 is functionally complete and indicative-display ready.** Remaining is non-code/deferred: **activation** (create
`HINDEX_*` indicators on chimie/geografie/fizica reports via the admin form, labeled indicative — a data/admin action)
and the **S4b hard pass/fail gate** (still deferred: computed WoS-Core h undercounts official per the H76/coverage gap;
the istorie "h OR citations" gate needs H68).

## Relation

Sibling to [H66](h66-curated-allowlists.md) (GS source) and [H68](h68-criteria-extensions.md) (OR-gate,
thresholds). Unblocks the h requirement in chimie, geografie, fizica/H65, istorie. **H76** (WoS CPCI onboarding) is the
WoS-accuracy follow-up surfaced by the 2026-06-22 validation.
