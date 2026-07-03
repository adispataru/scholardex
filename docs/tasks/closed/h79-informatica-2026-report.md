# H79 Informatica 2026 report (CNATDCU standards update)

**Status:** Scoped (2026-07-03)
**Created:** 2026-07-03 — from the 2026 CNATDCU standards (`data/standards/2026/standarde-{conf,prof,abilitare}-2025.html`)
compared against the shipped `informatica-2016` report.

## Context

The 2026 Informatica standard (COMISIA 2. INFORMATICĂ) keeps the **conf/prof numeric minima essentially unchanged**
from 2016 — the changes are structural + a few new data/scoring rules. Verified against code (2026-07-03):

Standard is 4 perspectives a)–d), each **îndeplinit/neîndeplinit**, all eliminatory (must pass). Full 2026 threshold
grid (extracted from the three HTML files 2026-07-03):

| Perspective | Conferențiar | Profesor | Abilitare (HABIL) |
|---|---|---|---|
| **b) production** | min **32**; A\*+A+B≥**16** | min **56**; A\*+A≥**24**; A\*+A+B≥**40** | min **44**; A\*+A≥**12**; A\*+A+B≥**28** |
| **c) impact (citations)** | min **48**; A\*+A+B≥**12** | min **120**; A\*+A+B≥**40** | min **84**; A\*+A+B≥**26** |
| **d) academic** | min **36** | min **60** (+ ≥1 R&D project) | min **48** |

vs `informatica-2016` (conf/prof unchanged): Perspectiva B 32/56, Perspectiva C 48/120, Perspectiva D 36/60, plus
"Publicații de top" 16/40 and "Citări de top" 12/40 (= the A\*+A+B praguri). **New in 2026:** the `A*+A` sub-threshold
(prof ≥24, HABIL ≥12; conf has none), the whole HABIL column, and the asist/lect national rows are gone (kept as
internal in the 2026 copy per the versioning decision). Note the "Total" grand criterion has no 2026 national
equivalent.

## Verified findings (what is / isn't already handled)

- **A\* forum tier — ALREADY HANDLED.** `ComputerScienceJournalScoringService:229-231,195`: `A* = top 20% of Q1 = 12p`,
  labelled via `coreRankingEquivalent`. Matches the 2026 definition exactly. No new classification work.
- **Da/Nu gates — NOT a new feature.** Perspectives b/c/d are score-criteria → the existing criteria model already
  renders per-criterion "îndeplinit (DA/NU)" (seen in the xlsx export); eligibility = all criteria met. Perspective
  a) (ethics/plagiarism) is a manual human judgment, out of the engine. So H68 Da/Nu gates are **not required** here.
- **Info_C citation exclusion — NEW versioned indicator.** `Info_C.kind.policy = CANDIDATE_ONLY` today; 2026 c)
  requires *citing pubs share no author/co-author with the cited paper* = **`ANY_COAUTHOR`** (H61 — mechanism already
  shipped; the 2026 text **answers H61's open question** = per-cited-paper). Per the versioning principle: leave
  `Info_C` as-is, add **`Info_C_2026`** (`ANY_COAUTHOR`) for the 2026 report.
- **SENSE book points — 16→12 for the top tier.** `ComputerScienceBookService:125-133` scores **A=16**/B=8/C=4/D,E=2
  (chapter one rank lower); 2026 wants **A=12**/8/4/2/1. Fix: have the SENSE scorer **return the category** and let the
  indicator formula map category→points (so 2016 keeps 16, 2026 uses 12), or a `SENSE2026` variant.
- **APC/fee-journal exclusion — DATA IS ON DISK, not yet parsed.** 2026 b) excludes journals "care condiționează
  publicarea articolului de plata unei taxe" from threshold points. The offline DOAJ dump
  `data/doaj/doaj_journalcsv_20260517_2321_utf8.csv` **already carries** cols `APC` (yes/no), `APC amount`,
  `Has other fees`. But `DoajJournalFact` stores only id/issn/title — the importer (`DoajOnboardingService`) **doesn't
  capture the APC fields yet**. So: extend the DOAJ fact + import + projection to carry the APC flag, then gate the
  Informatica forum threshold-points on non-fee journals. (Predatory/Beall's is already gated.)

## Guiding principle (locked 2026-07-03): version, never mutate

The `informatica-2016` report **and its indicators stay exactly as they are, for everyone** — a stored 2016 run must
replay identically. Everything new for 2026 is a **parallel copy**: a new report + new (versioned) indicators. No
re-point, no re-stamp of existing indicators.

## Slices

1. **Copy the report. [DONE 2026-07-03]** `informatica-2016` left untouched. Cloned → `informatica-2026`
   (`_id 6a47f446…`, title "FV Info 2026"): kept asist/lect/conf/prof from 2016, added **HABIL** thresholds
   (Perspectiva B=44, C=84, Publicații de top=28, Citări de top=26, Perspectiva D=48; Total has none — no 2026 HABIL
   grand-total), repointed the two citation criteria to the 2026 ANY_COAUTHOR indicators (idx1→`Info_C_2026`,
   idx3→`Info_C (A*,A,B) 2026`) and carried their export roles/blocks. Made visible via a
   `division_report_selections` row for the same division. **Live-verified** (florin.spataru): the report lists as
   "FV Info 2026", renders all criteria with the HABIL column on 5 criteria, and Perspectiva C scores via
   `Info_C_2026` (11 citing pubs, ANY_COAUTHOR) vs 2016's 16. Mongo + seed mirrored (`individualReports.json`,
   `scholardex.division_report_selections.json`). **Kept the Total row** (inherited) for the internal asist/lect
   continuation. Remaining for full parity: slice 3 adds the prof/HABIL `A*+A` criterion.
2. **New versioned indicators (no re-point).** `informatica-2016` keeps pointing at `Info_C` (`CANDIDATE_ONLY`) and its
   existing A*+A+B "top" filter indicators. Create **`Info_C_2026`** (`ANY_COAUTHOR` — H61 mechanism done; the 2026
   text confirms per-cited-paper) and a 2026 copy of the A*+A+B "top" filter indicator; `informatica-2026` points at
   the new ones. `Info_C` itself is never modified.
   **[SLICE 2 DONE 2026-07-03]** — both citation indicators that need `ANY_COAUTHOR` now have 2026 copies (Mongo +
   seed mirror), formulas unchanged so `formulaHash` reused:
   - **`Info_C_2026`** (`_id 6a47f17b…`) — perspective C main (report idx 1). `kind {policy:ANY_COAUTHOR, strategy:CS}`,
     formula `S/max(N-2,1)`. **Live-verified** (florin.spataru, temp-appended then reverted): CANDIDATE_ONLY keeps 16
     citing pubs, ANY_COAUTHOR keeps **11** — correctly drops shared-co-author citations. `informatica-2016` restored.
   - **`Info_C (A*, A, B) 2026`** (`_id 6a47f2d2…`) — Citări de top (report idx 3). Same `ANY_COAUTHOR` change; the
     A*+A+B filter is the `(S >= 4)` gate (A*=12/A=8/B=4), formula unchanged.
   The Publications A*+A+B filter (`Info_B (A*,A,B)`, idx 2) needs **no** 2026 copy here — its only 2026 change is the
   APC exclusion, which is scorer-level (slice 5). End-to-end scoring of the top-citations copy verifies with the
   report in slice 1.
3. **New `A*+A` prof production indicator. [DONE 2026-07-03]** Created `Info_B (A*, A)` (`_id 6a47f57b…`, Publications
   CS, formula `(S >= 8) ? (S/max(N-2,1)) : 0` — forum points A*=12/A=8/B=4, so the `S>=8` gate keeps only {A*, A});
   formulaHash re-stamped by the `indicator-migration` profile (`6e38017a…`, saved=1). Added to `informatica-2026`
   (idx 22) + a **2026-only criterion "Publicații A\*+A"** (PROF_UNIV=24, HABIL=12; no conf/asist/lect). **Live-verified**
   (florin.spataru): A*+A total 8.571 over 2 qualifying pubs vs A*+A+B 16.571 over 4 — the gate correctly excludes the
   B-category pubs; criterion renders in the report. Mongo + seed mirrored.
4. **SENSE book top tier 16→12. [DONE 2026-07-03]** Cell-by-cell, **only one value changed**: authored/edited book in
   a SENSE-A publisher 16→12 (books B/C/D and *all* chapter values unchanged; 2026 `capitole(A)=8`=2016 chapter-A).
   So — no scorer refactor (the category approach over-matches chapter-A, which must stay 8): a **pure-config 2026 book
   indicator** `Info_D_i_2026` (`_id 6a47f72a…`, strategy `CS_SENSE`) with formula
   `(S >= 16) ? (12/max(N-2,1)) : (S/max(N-2,1))` — `S=16` is uniquely book-A (chapters cap at 8), so it remaps only
   book-A→12 and leaves everything else identical. formulaHash re-stamped (`afd70cd4…`). Swapped `Info_D_i`→
   `Info_D_i_2026` at report idx 4 (carried the `activities-perspectiva-d` role). **Live-verified** (florin.spataru):
   his books are SENSE-**B** → 2016 and 2026 score identically (2.333), confirming non-A untouched; he has no A-book so
   the 16→12 delta isn't exercised live, but the formula is correct by construction. `Info_D_i` (2016) untouched.
   **Book-citation check resolved (no-op):** the 2016 standard (Standarde-minimale-Info.pdf, perspective c) already
   scores cites-in-books `12/8/4/2/1` — identical to 2026 — so `Info_C_2026` needs no book-citation change.
5. **DOAJ APC capture + fee-journal exclusion.** Rule (settled 2026-07-03): a journal **in DOAJ AND `APC=Yes`** →
   excluded from the 2026 perspective-b/c threshold points. DOAJ lists only fully-OA journals, so this is a near-exact
   proxy for "condiționează publicarea de plata unei taxe" — **hybrids/subscription (ACM, IEEE Transactions) aren't in
   DOAJ so they're never excluded; gold-OA (MDPI, IEEE Access/Open Journals) are excluded** (verified against the dump:
   ACM absent; IEEE only its Open-Journal/Access titles, all APC=Yes). **APC-only** (not "other fees"). **New in 2026**
   (absent from the 2016 PDF) → 2026 indicators only. Applies to **any ranked-journal score**, so it also drops
   **citations whose citing journal is APC** (perspective c says citing-forum scores inherit "reducerile sau
   excluderile" from b) — books/theses citations keep their fixed values.
   - **[5a DONE 2026-07-03]** — `DoajJournalFact.apc` (Boolean) + `DoajDataService` parses the `APC` column
     (Yes/No/blank → true/false/null). Re-imported the May-2026 dump: **8430 apc=true, 14485 apc=false, 0 null**. Test
     added. (doaj.journal_facts is reference data, re-importable — not seed-mirrored.)
   - **[5b DONE 2026-07-03]** — chose the **projected** path (option A). `V21` adds an `apc` column to
     `scholardex_forum_membership_view`; the DOAJ membership projection emits `doaj.getApc()` (other databases null).
     `ReportingLookupPort.isFeeJournal(forumId)` (default false; Postgres facade queries `database='DOAJ' AND apc IS
     TRUE`; `@Primary` delegator forwards). `ScientificProductionService` binds a `feeJournal` boolean on the **scored**
     forum (candidate pub in b, citing pub in c); **declared in `FormulaVariableContract`** (like Nef — a missing
     declaration crashed the migration until added). Ran the full projection: **8427 apc=true membership rows**;
     florin's JMIR forum row = DOAJ apc=t.
   - **[5c DONE 2026-07-03]** — gated the 2026 perspective-b/c **journal** indicators `!feeJournal ? … : 0`: edited
     idx 1/3/22 (already 2026) + created gated 2026 copies of idx 2 (`Info_B (A*,A,B) 2026`) and idx 20 (`Info_B_Jurnale
     2026`), swapped in. **Conferences (idx 19) skipped** — never in DOAJ, so the gate is a no-op. Hashes re-stamped.
     **Live-verified** (florin.spataru): his **JMIR Formative Research** paper (gold-OA APC) is now **zeroed** in the
     2026 `Info_B_Jurnale` (total 12.988→12.655, 8→7 nonzero items; the zeroed item keeps its forumScore 2.0 but
     authorScore 0), while `informatica-2016` scores it normally (12.988, unchanged). Full suite green.
   - **[5d DONE 2026-07-03] — OpenAlex source APC ingest (offline; closes the DOAJ coverage gap).** DOAJ misses
     gold-OA venues that aren't in the directory — florin's own **MDPI Electronics** paper (*A Solar Radiation
     Forecast Platform…*) is the exemplar: `is_oa=true`, **`is_in_doaj=false`**, `apc_usd=2165`, so the DOAJ-only
     signal never excluded it. Fix, entirely offline from the works dumps we already downloaded
     (`data/openalex/uvt_works.jsonl` + `uvt_citing_works.jsonl` — no API): each work embeds
     `primary_location.source` (id/issn/`is_oa`/`is_in_doaj`) plus a work-level `apc_list` (advertised APC,
     USD-normalized via `value_usd`). Aggregating by source id → one `OpenAlexSourceFact` per venue
     (`isOa`=OR across works, `apcUsd`=max). **Fee predicate = `isOa && apcUsd>0`** — this cleanly separates gold-OA
     (MDPI, excluded) from **hybrid** journals (paid OA option but `is_oa=false`, NOT excluded; e.g. ISPRS J.
     Photogrammetry, apc but is_oa=false). Files: `OpenAlexSourceFact` + repo; `OpenAlexSourceApcImportService`
     (streams the dumps); DTO gained `apc_list`/`apc_paid` on the work + `is_oa`/`is_in_doaj` on the source; admin
     endpoint `/openalex/importSourceApc`; projection `buildOpenAlexApcMembershipRows` (ISSN-match → `database='OPENALEX'`,
     `apc=true`, fee journals only); `isFeeJournal` **broadened** from `database='DOAJ'` to any `apc IS TRUE` (unions
     DOAJ ∪ OpenAlex). No engine/scorer change; the 2026 indicators' `!feeJournal` gate is unchanged. **Real-data
     validated** over the own-works dump: 2,514 sources → **363 fee journals, 27 of them not in DOAJ** (all the MDPI
     titles: Electronics, Sustainability, IJMS, Polymers, JCM, Healthcare…). Unit tests: aggregation (gold vs hybrid
     vs diamond, max-apc/OR-is_oa across works) + projection membership (ISSN match, hybrid excluded).
   - **Per-year:** v1 uses the single May-2026 snapshot as the floor (retroactive exclusion accepted). Per-pub-year
     resolution via closest-earlier DOAJ edition (mirrors H60) is a later add needing historical dumps.
   - **[5d LIVE-VERIFIED 2026-07-03]** — ran the full path locally (agent-dev, 8181): `POST /openalex/importSourceApc`
     streamed both dumps in ~23 s → **117,422 works → 18,575 sources, 2,140 fee journals**; `POST /scopus/buildProjections`
     (~240 s) published **2,140 `OPENALEX` apc=true membership rows**. Postgres proof for the **MDPI Electronics** forum
     (`sforum_76ef61e3a21a411fd466806e`, issn 2079-9292): `DOAJ|apc=f` but `OPENALEX|apc=t` → the broadened
     `isFeeJournal` returns **true** (was **false** under the DOAJ-only query). **177 forums total are newly flagged by
     OpenAlex** that DOAJ missed. Report-level end-to-end (delegated refresh of florin.spataru's FV Info 2016 vs 2026):
     his Electronics paper *A Solar Radiation Forecast Platform…* scores **authorScore 2.0 in 2016** (ungated, frozen)
     and **authorScore 0.0 in 2026** (`Info_B_Jurnale 2026`, gated) — forumScore stays 2.0 in both (rank preserved,
     only the threshold contribution zeroed). "Version, never mutate" confirmed: 2016 total 12.988 unchanged, 2026 total
     10.655.
   - **Deploy note:** production needs (a) the DOAJ re-import (APC parse), (b) the OpenAlex source APC import
     (`POST /openalex/importSourceApc` — streams both works dumps offline), then (c) a projection run to populate
     `membership.apc` from both signals.

6. **Conference-workshop category — 2026 relabel (id_parA82).** The 2026 standard changes how a CORE-unclassified
   workshop attached to a ranked conference is categorized: 2016 = one category lower (A*→A, A→B, B→C, C→D); **2026 =
   A*/A/B all → C, C → D**. The **point ladder is identical** (6/4/2/1), so no total moves — only the reported category
   on the Fișă. Posters/system demos and category-based eligibility were considered and **deferred** (user scope:
   relabel only). Perspective-d conference indicators are **out of scope** (A82 is a perspective-b rule).
   - **[6a DONE 2026-07-03] mechanism.** `Indicator.workshopCategory2026` (nullable Boolean; legacy docs → null = frozen
     2016 behaviour). `ComputerScienceConferenceScoringService` threads the flag from `getScore(pub|activity, indicator)`
     through `resolveConferenceScore`/`scoreResolvedConference`; new `workshop2026DowngradedRank` (A*/A/B→C, C→D) is used
     only when the flag is set. Points unchanged. External/classification callers (`VenueClassifier`) default to 2016.
     Unit tests: A*-workshop 2016→**A**/6pts vs 2026→**C**/6pts; A-workshop 2026→**C**/4pts.
   - **[6b DONE 2026-07-03] rollout (version, never mutate).** Flag set on the 4 already-2026-only CS indicators
     (`Info_C_2026`, `Info_C (A*, A, B) 2026`, `Info_B (A*, A)`, `Info_B (A*, A, B) 2026`). The perspective-b conference
     indicator `Info_B_Conferințe` is **shared** with FV Info 2016 — so a 2026 copy **`Info_B_Conferințe 2026`** (id
     `6a481bc6…`, flag=true) was created and swapped into FV Info 2026 idx 19 (carrying role/block maps), mirroring the
     slice-5c journal pattern. 2016 report still references the original. Mongo + seed (`indicators.json` +1 indicator
     & 5 flags, `individualReports.json` swap) mirrored via a byte-fidelity targeted edit.
   - **[6d DONE + LIVE-VERIFIED 2026-07-04] — category-based eligibility for the 2026 "top A*/A/B" indicators.**
     Consequence of 6a/6b that surfaced in review: the top indicators gate on `S >= 4` (points), a proxy for
     "category ∈ {A*,A,B}" that is exact for every item EXCEPT a workshop, whose 2026 category (C) diverges from its
     inflated 6/4 points. So `Info_B (A*, A, B) 2026` and `Info_C (A*, A, B) 2026` were wrongly counting A\*/A workshops
     (6/4 pts ≥ 4) that are now category C. Fix: bind a `topAB` boolean in `ScientificProductionService`
     (`isTopAStarAB`: for a **workshop-adjusted** item, category ∈ {A\*,A,B} is authoritative; for everything else the
     legacy `S>=4` is kept verbatim — so journals and **SENSE-scale books** whose category label C carries 4 pts are
     unaffected, and no unintended 2016↔2026 divergence is introduced). Declared `topAB` in `FormulaVariableContract`;
     re-pointed the two formulas to `(topAB && !feeJournal) ? (S/max(N-2, 1)) : 0` (re-stamped, hash `6b2c5ba1…`).
     **Scope decisions:** 2016 is already correct (there category ≡ S>=4 for every workshop case — FV Info 2016 has no
     A\*+A indicator, only A\*+A+B), and 2026 `Info_B (A*, A)` (S>=8) is already correct (workshop pts ≤6 < 8), so both are
     left untouched. Unit test (workshop cat C excluded; normal B + SENSE-C book preserved; total). **Live-verified**
     (dana.petcu Cluster Workshops, cat C / 4 pts, via temp decision then reverted): authorScore **0.0** in
     `Info_B (A*, A, B) 2026` (top — now excluded) vs **4.0** in `Info_B_Conferințe 2026` (all-conference — still counts).
   - **[6c LIVE-VERIFIED 2026-07-03]** app boots with the new indicator/field; the versioned indicator computes with no
     regression. **Definitive 2016-vs-2026 proof** on a real workshop paper: dana.petcu's *"IEEE International Conference
     on Cluster Computing **Workshops** and Posters"* (parent IEEE Cluster = CORE **A**) — same paper, points unchanged
     at **4.0**, category **B in FV Info 2016** (`Info_B_Conferințe`, workshop one-lower) vs **C in FV Info 2026**
     (`Info_B_Conferințe 2026`, flat-C), both `src=SCOPUS+CORE(WS)`. Verified via a temporary CONFIRMED authorship-decision
     (dana.petcu isn't self-onboarded), then reverted (decision + generated runs/results deleted; decisions back to
     florin-only). **Correction to an earlier note:** the local dataset is *not* a Mathematics cohort — 40 of 55 users are
     Informatică (SCIA 20 + TDIS 20), 15 Math; 7 Informatică users have workshop papers. The reason most reports are
     empty is that **only florin has confirmed authorship decisions** (scoring reads confirmed pubs), not the department.
     Note: `IPDPSW`-style forums whose parent acronym the CORE matcher doesn't resolve fall to the SCOPUS-D path and are
     never workshop-adjusted under either standard (a matcher-coverage limitation, unrelated to the 2026 relabel).

## Open questions / notes

- **DOAJ editions.** Only the 2026-05 snapshot is on disk; per-year APC resolution wants a series of editions. Source
  older DOAJ dumps (they're published) to backfill; until then the single edition is the floor.
- **b↔c 20% compensation** (up to 20% of perspective-b thresholds transferable from c) — niche; defer (H68 #6).
- Prof d) "≥1 R&D project" minimum — a small extra gate; the H64 project layer + `proj_*` injection already supplies
  project data.
- Depends on: H61 (mechanism done), H64 (projects), the SENSE/CORE scorers, the DOAJ import. No new engine.
