# H83 University rankings — QS + ARWU ingestion, best-of resolution, URAP back-catalog

**Status:** Scoped 2026-07-24. Consumer: OM 3019/2025 Informatică footnote *3 — "Cele mai bune poziții
conform clasamentelor: topuniversities.com (QS), urapcenter.org, shanghairanking.com (ARWU)" — governing
D(viii) keynote/invited, D(ix) visiting positions, D(xi) thesis committees. The 2016 standard has the same
footnote. Today only URAP exists, so a university strong in QS/ARWU but weaker in URAP under-scores.
Second goal (same slice family): extend the URAP back-catalog — visits are often old, and 2010–2017 data
exists upstream while our closest-year fallback approximates from 2018.

## Baseline (verified in code, 2026-07-24)

- Model: `URAPUniversityRanking {@Id name, country, Map<year, Score{rank, metrics…}}` — **name IS the id**;
  spelling drift across years creates separate docs (already true for 2018–2024).
- Data: `data/urap-univ/URAP_WR_2018..2024.xlsx`, loaded by `URAPRankingService` via
  `POST /admin/initialization/general/urap`.
- Scoring: `UniversityRankScoringService` (UNI_RANKING) — `UNIVERSITY_NAME` reference →
  `findByNameIgnoreCase` (exact, cached) → `closestYearRank` (exact year, else closest, tie → earlier) →
  bracket formulas (top 20/100/200/500/floor) in Info_D_viii-a / D_ix / D_xi. Formulas need NO change.

## Slices

**S1 — URAP back-catalog 2010–2017 — DONE locally (2026-07-24).** Scraped via Wayback CDX (the live
site is now a Meteor SPA; old world.php paths serve the shell). `URAP_WR_2010..2017.xlsx` built and loaded:
4,499 ranking docs (was 3,562), Harvard spans all 15 years. Coverage: full top-500 for 2011/2013/2014/2016/
2017; top-250 only for 2010/2012/2015 (251-500 windows never usably archived) — adjacent full years cover
via closestYearRank. Only ranks ≤500 differentiate the brackets, so tail gaps are cosmetic (D_xi's 0.5
floor for >500 aside). Name drift confirmed and accepted (e.g. "Universita di Pisa" 2010-2013+2015 vs
"University of Pisa" 2014+; four Aix-Marseille variants) — S4 picker is the eventual fix.
**Loader bug found+fixed:** POI's deprecated `setCellType(STRING)` CLEARS inline-string cells
(openpyxl output) — all 7,750 back-catalog rows skipped silently; `getStringValue` now reads by actual
cell type. Verified end-to-end: a 2014 Pisa Visiting-Staff entry scores D_ix 4.00 (era-true top-200)
instead of 2.00 (closest-2018 top-500).
**Prod rollout (pending):** (1) deploy the loader fix FIRST (prod has the same inlineStr bug);
(2) copy the 8 xlsx onto the `scholardex-data` PVC via a writable helper pod; (3) drop `urap.rankings`
in prod Mongo; (4) POST `/admin/initialization/general/urap` from an admin session. Do (3)+(4) right
after a deploy restart so the name-resolution cache starts empty.

**S2a — ARWU ingestion — DONE locally (2026-07-24).** Scraped straight from ShanghaiRanking's own
public JSON API (`/api/pub/v1/arwu/rank?version=<year>`, 2003–2025; 2018 rejects the version parameter
upstream — recovered from the Wayback snapshot of the old ARWU2018.html). 23 CSVs in `data/arwu/`
(`rank,rankBand,name,country`, band lower-bounded per the pinned decision). New generic
`UniversityRanking {id=source|name, name, source, country, ranks{year -> {rank, rankBand}}}` in
`university_rankings`, loaded by `UniversityRankingCsvService` (per-source load-once guard) via
`POST /admin/initialization/general/arwu` (+ admin card). Local load: 1,505 universities, 15,316 rows,
0 skipped; Pisa spans 2003–2025 with STABLE naming (no URAP-style drift); Aix = "Aix Marseille
University" 2012+ with the "(Aix-Marseille 2)" predecessor doc 2003–2010.
**Prod rollout: defer until S3 ships** — the data is inert until the scorer consumes it; roll out code
(deploy) + `data/arwu/` PVC copy + init button together with S3.

**S2b — QS ingestion.** New generic model `UniversityRanking {name, source (QS|ARWU), country,
Map<year, rank>}` in one collection (URAP stays in its collection for now; unification is a later cleanup).
Sources, best-available first:
- ARWU: Kaggle 2003–2025 compilation (full back-catalog) — https://www.kaggle.com/datasets/pawellenartowicz/arwu-shanghai-ranking-2003-2025;
  fallback/refresh via the Chalmers `ShanghaiRankingListsToCSV` scraper. Note: pre-2017 lists are top-500,
  later top-1000; banded ranks ("151-200") map to the band's lower bound for bracket comparisons.
- QS: Kaggle 2017–2022 multi-year + 2025 + 2026 sets; 2012–2016 opportunistic (partial coverage accepted —
  URAP/ARWU carry those years). QS has no official free bulk download; internal evaluation use.
Loaders + `/admin/initialization/general/{arwu,qs}` endpoints mirroring the URAP one; files under
`data/arwu/`, `data/qs/`.

**S3 — best-of resolution in the scorer.** A `UniversityRankingLookupFacade.bestRank(name, year)` resolves
the name in each source (exact-ignore-case per source, same policy as URAP today), takes each source's
closest-year rank, returns the MINIMUM (best position) + provenance {source, dataYear, rank}.
`UniversityRankScoringService` consumes it; provenance goes into `scoringInfo` so the drilldown can show
e.g. "ARWU 2015 · #151". Candidate-favorable per the OM's own footnote. Bracket formulas untouched.

**S4 (optional, cheap) — UNIVERSITY_NAME picker.** Autocomplete over the union of ranking names, mirroring
the CORE conference picker (`/api/entities/universities?q=`, suggestion shows best current rank per
source). Kills the exact-name-resolution misses at the root and matches the picker UX Florin already has
for conferences.

## Decisions (pinned 2026-07-24)

- **Banded-rank mapping** ("151-200"): lower bound of the band — candidate-favorable, consistent with how
  the top-N brackets read.
- **Scraping is one-off**, not committed to the repo; only the produced xlsx/csv artifacts matter.
- **Durable artifact store = the `scholardex-data` PVC** (20Gi, `harvester-retain`, mounted read-only at
  `/app/data` in the core deployment — verified). `data/` stays gitignored; new files (URAP back-catalog,
  `data/arwu/`, `data/qs/`) are added incrementally: local `data/` first, then copied onto the PVC. Note
  the core pod's mount is **read-only**, so the copy goes through a short-lived helper pod mounting the
  PVC writable (RWO — schedule alongside/after scaling considerations), same route the initial transfer
  used.

## Verification targets

- Florin's real cases: University of Pisa and Aix-Marseille (D_viii/D_ix) — both ARWU-ranked in eras where
  URAP coverage was the blocker; expect bracket improvements.
- A pre-2018 visit that currently resolves via closest-year 2018 should resolve exactly after S1.

## Non-goals

- Fuzzy cross-source university identity (a la forum reconcile) — exact-per-source + the S4 picker instead.
- THE (Times Higher Education) — not in the OM footnote.
- Subject-level rankings (ARWU GRAS, QS by subject) — the OM reads as institution-level.
