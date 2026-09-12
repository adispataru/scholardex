# Paper classification: comparison with the `puncte` reference classifier

`puncte/clas.c` (+ `puncte/clasific.tex`) is a time-proven, single-file CNATDCU/INFO
classifier (2020–2023) driven directly by Crossref/DOI metadata. This note compares its
logic with our scoring engine and tracks which patterns we adopt.

## The reference algorithm (type-first)

Read the Crossref `type`, then run exactly one path:

| `type`                                   | path       | source / rule |
|------------------------------------------|------------|---------------|
| `article-journal` / `journal-article`    | journal    | SCIE/SSCI rank by IF **and** AIS → A*/A/B/C (best position wins); ESCI if WoS-only; PlumX→Scopus ⇒ **C** floor |
| `paper-conference` / `conference-paper` / **has `event`** | conference | acronym → CORE (closest year) + **70–75% event-title match** |
| `chapter` / `book`                       | book       | **SENSE**: publisher + location + ISBN prefix → A–E |

Sharp discriminators it uses beyond the `type` word:
- **`event` field present ⇒ conference** (independent of venue metadata).
- **DOI prefix `10.1007/978…` ⇒ Springer LNCS chapter**, *force-routed to the conference path*; `10.1109 ⇒ IEEE proceedings`.
- Acronym extraction: `(ACRONYM)`, else after `" - "`, else guess from capitals of "International Conference on X" → ICCS.
- Workshop detection (`" Workshops"`, `" Companion"`) ⇒ half points.
- Edge rules: Springer LNCS pre-2006 ⇒ journal; unmatched LNCS chapter ⇒ **B** floor (we use C).

## Alignment

Already matched: Scopus→C floor, ESCI fallback, acronym→CORE by closest year, best/MAX
quartile across SCIE/SSCI + IF/AIS, book chapters excluded from journals (this session). We
also have richer acronym extraction + workshop handling + confidence tiers, and book scorers
(`ComputerScienceBookService`, `CNCSISPublisherListService`).

## Gaps / adopted patterns

1. **Type routing incomplete & bypassed.** `ComputerScienceScoringService.scoreBySubtype`
   routed `ar/re→journal`, `cp→conference`, `default→0` — `ch`/`bk` never reached the book
   scorer. And the `Info_B_*` indicators force one scorer over all publications, leaning on
   per-scorer candidate gates (root cause of the book-chapter / LNAI misclassifications).
   → make type the single authoritative discriminator; route `ch`/`bk` to the book scorer.
2. **DOI-prefix signals unused** though we have the DOI: `10.1007/978…` ⇒ LNCS/LNAI
   proceedings (overrides bad `aggregationType="Journal"`); `10.1109` ⇒ IEEE proceedings.
3. **Venue-type signal (the `event`-field equivalent).** OpenAlex has no Crossref `event`, and
   the work-level `type` is uniformly `article` for journals *and* conferences — so the only
   OpenAlex venue discriminator is `primary_location.source.type`
   (journal | conference | book series | repository | …), which we did not capture. We now
   capture it (`OpenAlexSource.type` → `OpenAlexPublicationFact.hostVenueSourceType`) and map it
   to the forum `aggregationType` at venue onboarding
   (`ForumSourceRecord.aggregationTypeForOpenAlexSourceType`): journal→Journal,
   conference→Conference Proceeding, book series→Book Series, ebook platform→Book; unknown→null
   (merge keeps any existing value / its "Journal" default). This replaces the blind "Journal"
   default that mislabels LNCS/book-series venues. Confirmed against the live API: the LNAI venue
   "Lecture notes in computer science" returns `source.type="book series"`.

   **Backfill:** existing facts lack `hostVenueSourceType` (populates on the next OpenAlex
   re-sync), and the merge engine keeps an existing forum `aggregationType` (to protect Scopus's
   reliable types) — so mislabeled forums correct on the **next from-scratch rebuild**, not
   incrementally. A lighter alternative is a venue-only `/sources` backfill keyed by
   `hostVenueOpenAlexId` (not built).

## Implemented (this session)

- Type-first routing completed (`scoreBySubtype`: ch/bk→book, sh/dp→journal). [76940ac]
- DOI-prefix signal `DoiVenueSupport` (10.1007/978 ⇒ not a journal; LNCS conference). [76940ac]
- OpenAlex `source.type` → forum `aggregationType` capture + mapping.

Policy to confirm with stakeholders: LNCS floor B vs our C; pre-2006 LNCS as journal.

## LNCS floor — evidence-first (H106 S6, 2026-09-13)

The `10.1007/978…` DOI prefix covers every Springer book series (CCIS, AISC, SIST, IFIP AICT, Studies in …,
Springer Proceedings in …), not only LNCS, and DBLP re-stamps forums to bare acronyms, so neither the prefix
nor the forum name can say which series printed a paper. Measured in prod before the change: the floor had
fired on 992 cached citation rows and 266 own-paper rows, 500 of the citations on re-stamped forums and 304
with no forum at all. The Crossref record carries the answer as `container-title[0]`, which H92 fetched and
discarded. Now:

1. `CrossrefVolumeEnrichmentService` asks Crossref for EVERY Springer-ISBN paper (any forum, DBLP or not) and
   stores `crossrefSeries` next to `volumeTitle` on the shared evidence row; runs inside every rebuild
   (`ScopusBigBangMigrationService.runEvidenceSweeps`, with the DBLP dump sweep) and daily
   (`CrossrefSeriesSweepScheduler`).
2. `ComputerScienceConferenceScoringService.lncsFloorEvidence`: a known series in the wider "Lecture Notes
   in/on …" family (LNCS, LNAI, LNBIP, LNNS, LNEE, LNICST, LNDECT — user decision) floors to C; a known series
   outside it does NOT, whatever the DOI or forum name say; no series yet → forum name in the family → C;
   otherwise the DOI prefix keeps the floor, recorded as `doi-prefix (series unknown)`. The grounds land in
   `scoringInfo.lncsFloorEvidence`.
3. Backfill after deploy: `POST /admin/initialization/crossref/volumes/apply` once (~5,200 Springer papers,
   polite pool ≈ 20 min), then report refreshes move the scores.
