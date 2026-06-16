# H62 FEAA Economics report — full-fišă DOCX export

**Status:** Planning
**Created:** 2026-06-15

## Goal

Add a no-formula DOCX export for the FEAA (Facultatea de Economie și de Administrare a Afacerilor,
UVT) verification fišă, bound to report `6849fb3d97a94f22948f9430` ("FEEA_partial"). Scope decided
with the user: **full fišă** (articles + books/chapters + citations + summary) and **all-position
thresholds** rendered. Same export pattern as `matematica-2016` (`ReportTypeImportSupport` +
`TemplateDocxRenderer` + `binding.json` + dedicated-indicator totals).

## Source documents (assessed 2026-06-15)

- `Fisa-verificare_prof_conf_FEAA.docx` — **methodology + annexes, not a candidate fišă**: book/chapter
  coefficients, the JCR-category → **M** multiplier table (Core Economics=10, Infoeconomics=8, Social
  Science=6), Conf/Prof thresholds, and the **Anexa 1 prestige-publisher list** (52 publishers). This is
  the reference data behind scoring.
- `Standarde-minimale-{Lector, Asistent-univ-nedeterminata, Asistent-determinata}.docx` — **the fill-in
  fišă; structure is identical across them**, differing only in threshold numbers / target position
  (Asistent-determinata bundles two: asistent univ + asistent cercetare). Canonical structure (per fišă):
  1. **Articles** — `Nr | Publicație | M | N | AIS | Punctaj final` (ISI WoS, AIS≠0, ≤10).
  2. **Books/chapters slots 7–10** — four mini-tables, each with a "Total punctaj …" row:
     carte int. `Pi=0.5/N`, capitol int. `0.25/N`, carte naț./altele `0.2/N`, capitol naț./altele or
     articol ISI Proceedings `0.1/N` (international = publisher in Anexa 1).
  3. **Citations** — `Nr | Citare | AIS | Cuartila | Punctaj final` (≤10, excl. self-citations).
  4. **Summary** — P, C, **S = P + C** min-vs-obtained per position + "Nr. articole ISI cu AIS nenul în
     Core Economics/Infoeconomics" count.
- `2024-FEAA-_Criterii-concurs.pdf` — Ordin 6129/2016 Anexa 27. Formulas: `Pi = M·AIS·(1-(N-1)·0.1)`
  (null/negative when N>10 or AIS=0); `P = Σ Pi`; citation `Cj` by AIS-quartile (Q1=1, Q2=0.75, Q3=0.5,
  Q4=0.25; prestige-book citation = 0.25 = Q4-equivalent); `C = Σ Cj`; N counts only RO-affiliated authors.

## What already exists ✅ (no new scoring for articles/citations)

- **FEEA_P** (`683618f3…`) — `Publications(role=ALL, strategy=ECONOMICS_JOURNAL_AIS)`,
  formula `M * (1 - (N-1) * 0.1) * S`, `Selector.TopN(10)`, `ScoreYearRangeSpec.ItemYear`.
  `EconomicsJournalScoringService` sets `score.getScore()`=AIS, `score.getMultiplier()`=M.
- **FEEA_C** (`683624c8…`) — `Citations(excludeSelf, strategy=AIS)`, formula
  `Q=='Q1'?1:Q2?0.75:Q3?0.5:Q4?0.25:0`, `TopN(10)`, `ItemYear`. `AISJournalScoringService` sets
  `score.getScore()`=AIS, `score.getQuarter()`=Q.
- `report.criteria` already encode per-position thresholds (ASIST_UNIV/LECT_UNIV/CONF_UNIV/PROF_UNIV) for
  C (idx0), P (idx1), and S=P+C (idx0+1). `S = P + C` (sum), not a separate indicator.
- Book/publisher infra exists to reuse: `PublicationSubtypeSupport.resolveSubtype()` → `bk`/`ch`;
  publisher via `forum.getPublisher()`; precedent tier-scoring (`ComputerScienceBookService`,
  `CNCSISPublisherListService` + `ScoringStrategy.CNCSIS`).

## Gaps to build

| # | Gap | Notes |
|---|---|---|
| A | `reportTypeKey=feaa-2024` + `indicatorRolesByIndicatorId` (FEEA_P→articles, FEEA_C→citations) on the report | DB + `seed/precious-config/individualReports.json` |
| B | Surface **M** on `PublicationSnapshotItem`, **AIS + quartile** on `CitationSnapshotItem` | projector add (both live + run-backed paths), mirrors the math `forumScore` add |
| C | Template docx + `binding.json` + `Feaa2024ReportTypeImportSupport` | mirror math; articles & citations FIXED_TABLE, book slots STACKED_BLOCKS or 4 fixed tables |
| D | **Books/chapters scoring (new)** — 4 tiers `Pi=coeff/N` by subtype(`bk`/`ch`)×publisher∈Anexa1 | new strategy/indicator(s) + **Anexa 1 publisher-list seed** (52 publishers) |
| E | **Core/Infoeconomics article count** for the summary | derive from per-article category (M>0 ⇒ ranked; category from `EconomicsJournalScoringService`) |
| F | Render **all-position** threshold/summary table from `report.criteria` | P/C/S × 4 positions + obtained values |

## Proposed slices

- **Slice 1 — articles + citations + summary (unblocked). ✅ DONE 2026-06-15.** A + B + C implemented:
  - `reportTypeKey=feaa-2024` + `indicatorRolesByIndicatorId` (FEEA_P→`journal-publications`,
    FEEA_C→`citations-per-publication`, reusing the existing routing constants) on the report (DB + seed).
  - `PublicationSnapshotItem.multiplier` (M) and `CitationSnapshotItem.CitingPublication.{authorScore,quartile}`
    (Cj, Q) added; surfaced by `RunIndicatorSnapshotProjector` (`multiplier()`/`quarter()` helpers). The
    citing `score` stays the base journal score (= AIS); `authorScore` is the new per-citation Cj.
  - `BindingDocxTotal.cellIndex` — new **cell-target** total mode (FEAA totals/summary land in a dedicated
    value cell, scoped to the role's `tableIndex`), vs. math's inline `=`.
  - `Feaa2024ReportTypeImportSupport` + `report-templates/feaa-2024/{template.docx,binding.json}`: articles
    FIXED_TABLE (M/N/AIS/Pi, table 3), citations FIXED_TABLE flattened per-citation (AIS/quartile/Cj, table 8),
    summary cell-totals P/C/**S=P+C** (table 9). Table 1 (all-position thresholds) is static template content.
  - Book slots 7–10 left as placeholders (slice 2). Unit test green; math/builder/projector tests green.
  - **Live verify:** the export runs (HTTP 200, structure + cell-totals correct) but florin's economics
    scores are **legitimately 0** — he has no economics-category (Core Economics/Infoeconomics) publications
    in years with AIS coverage (his CS journals' only AIS row is 2023; his pubs there are 2020/21/24, and
    `FEEA_P` scores on `ItemYear`). Not a bug. Rendering is proven by the unit test with real values; live
    non-zero needs an actual economics researcher's run.

### WoS resolution fix (done 2026-06-15, surfaced during this investigation)

Investigating "AIS shows in `/forums/{id}` but scoring is 0" found a real asymmetry (independent of florin's
case): **scoring resolved WoS rankings by raw ISSN only**, while the forum view / rest of the app resolves via
`WosForumResolutionService` (ISSN candidates from issn+eIssn **plus a normalized journal-name fallback**).
Measured impact: **40 journal forums resolve by name but not ISSN; 39 of those carry AIS** — they were
silently scoring 0. Fix:
- `ReportingLookupPort.getRankingsByForum(forum)` (new) — default = ISSN-only (back-compat for mocks);
  `PostgresReportingLookupFacade` overrides it to add the name fallback, reusing `WosForumResolutionService`
  (cached resolution index) + a new `loadRankingsByJournalId`; `ReportingLookupFacade` delegates.
- `AbstractForumScoringService.getRankingsForForum` now delegates to `lookupPort.getRankingsByForum`.
- Covered by a `PostgresReportingLookupFacadeTest` fallback test. Benefits **all** WoS-scored reports, not
  just FEAA.
- **Slice 2 — books/chapters (gap D). IN PROGRESS (2026-06-15).**
  - **Part A (done):** Anexa 1 prestige-publisher allowlist — modeled like CNCSIS (flat name-matched list,
    `FeaaAnexa1Publisher` @ `scholardex.feaaAnexa1` + repository). `FeaaAnexa1PublisherService` self-seeds
    the collection from a bundled CSV fixture (`resources/report-data/feaa-anexa1-publishers.csv`, 50
    publishers extracted from the methodology docx) when empty, and answers membership against a
    normalized-name set (diacritics/punctuation-insensitive). The CSV is the git-tracked source of truth.
  - **Part B (done):** `ScoringStrategy.FEAA_BOOK` + `FeaaBookScoringService` — returns the coefficient as
    the base score (indicator formula `S/N` → Pi): book∈Anexa1 0.5, chapter∈Anexa1 0.25, book∉ 0.2,
    chapter∉ / ISI-proceedings 0.1; articles get 0 here (scored by ECONOMICS_JOURNAL_AIS). The coefficient
    uniquely identifies the slot. Auto-registered via the `ScoringFactoryService` map. Unit-tested.
  - **Design refinement:** **one** `FEAA_BOOK` indicator (not 4) — the 4 slots (7–10) are the same book
    source classified by subtype×tier, so the renderer groups by coefficient at render time rather than
    4 indicators re-scoring the same books.
  - **Part C (done):** `book-publications` role (`PublicationRowProjector.ROLE_BOOKS`) routed in the
    snapshot builder; one `FEEA_Books` indicator (`FEAA_BOOK`, formula `S/N`, `Selector.All`) added to the
    report (DB + seed, role-mapped). The renderer groups book items into the 4 slot tables (T4–T7) **by
    coefficient** (0.5→7, 0.25→8, 0.2→9, 0.1→10), with per-slot cell-totals; book Pi folds into the
    summary **P = articles + books** (new `feaa-P` total) and **S = P + C**. Template switched from
    Lector → **Asistent-nedeterminata** (Lector's slot labels were garbled: T4/T5 both "capitol intl",
    no clean "carte intl 0.5"); same indices so slice 1 unaffected.
  - **Verified live (florin):** 12 ISI proceedings populate slot 10 (table cloned 11 rows) with total
    **0.39**; slots 7–9 empty (no books); summary **P = S = 0.39**. Unit tests cover all 4 slots +
    P-fold. (FEEA_C/FEEA_P still 0 for him — legitimately no economics articles.)

  **Slice 2 DONE 2026-06-15.**

  - **Decision on the P/books interaction (2026-06-16, after re-reading Anexa 27):** the standard lets
    books **substitute** articles in the ≤10-item P pool, but caps the book contribution at **25% of the
    target position's P_min** (Prof 0.5 / Conf 0.1875 / Lector 0.1 / Asist 0.0125) — i.e. the book→P
    contribution is **position-dependent**, which conflicts with the all-positions fišă. Resolution:
    **books are display-only.** `FEEA_Books` → `TopN(10)`; the top-10 books render in slots 7–10 with
    per-slot informational totals, but are **excluded from P and S** (P = articles only, S = P + C). The
    book indicator is still scored on the run but is **not** in the P/C/S criteria, so DB eligibility is
    unaffected. The candidate substitutes a book for a weaker article manually. This sidesteps modelling
    the per-position 25% cap entirely. (Verified live: florin's 10 proceedings show in slot 10; P = S = 0.)

- **Slice 3 — Core/Infoeconomics article count. DONE 2026-06-16.** The summary row 4 obtained cell now
  shows the count of `journal-publications` items with `multiplier ≥ 8` (Core Economics M=10 or
  Infoeconomics M=8; Social Science M=6 excluded) and AIS>0 — computed in the support from the `M`
  already surfaced in slice 1, written via a `feaa-core-count` cell-total. Unit-tested (positive + M=6
  exclusion); verified live (florin → 0, no economics articles). The per-position *required* min count
  (template row-4 cell 1, e.g. Asistent "1") stays static template content, like the P/C/S minimums.

  **H62 full fišă COMPLETE** (slices 1–3): articles (M/N/AIS/Pi, TopN10), books slots 7–10
  (top-10, display-only), citations (AIS/quartile/Cj, TopN10), summary P=articles, C, S=P+C, and the
  Core/Info article count. Remaining optional polish only: per-position book-cap / count-min are shown
  as static template content (candidate compares manually); the WoS resolution fix and `wos.rankings`
  cleanup landed alongside.
- **Slice 3 — Core/Infoeconomics count (gap E).** Compute and render the summary count.

## Open questions / decisions

- **Books modeling (slice 2):** one indicator with 4 internal tiers feeding 4 block slots, or 4 separate
  indicators (one per slot, simpler totals)? Reuse `CNCSIS`-style publisher matching against a new Anexa 1
  list, or a dedicated `FEAA_BOOK` strategy? Decide at slice 2.
- **Anexa 1 list source:** seed the 52 publishers from the PDF/docx; confirm name-normalization matching
  against `forum.getPublisher()`.
- **Template:** which `Standarde-minimale` file to base the position-neutral template on (structures are
  identical); strip to one fišă with the all-position threshold table.

## Exit criteria

- `feaa-2024` report type exports the full fišă for a real run: articles (M/N/AIS/Pi), book slots 7–10
  (per-tier Pi + totals), citations (AIS/quartile/Cj), and summary (P, C, S=P+C, Core-Econ count) against
  all-position thresholds. Round-trips render deterministically; replay-shape guard green; no regression to
  `matematica-2016`/`informatica-2016`.

## Dependencies

Builds on H50 export infra + H52 typed scoring. Per-report block wording uses the H50 hook
(`formatBlockPublicationDescription`). Independent of H60/H61.
