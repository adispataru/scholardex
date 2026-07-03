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
   - **Per-year:** v1 uses the single May-2026 snapshot as the floor (retroactive exclusion accepted). Per-pub-year
     resolution via closest-earlier DOAJ edition (mirrors H60) is a later add needing historical dumps.
   - **Deploy note:** production needs the DOAJ re-import (APC parse) + a projection run to populate `membership.apc`.

## Open questions / notes

- **DOAJ editions.** Only the 2026-05 snapshot is on disk; per-year APC resolution wants a series of editions. Source
  older DOAJ dumps (they're published) to backfill; until then the single edition is the floor.
- **b↔c 20% compensation** (up to 20% of perspective-b thresholds transferable from c) — niche; defer (H68 #6).
- Prof d) "≥1 R&D project" minimum — a small extra gate; the H64 project layer + `proj_*` injection already supplies
  project data.
- Depends on: H61 (mechanism done), H64 (projects), the SENSE/CORE scorers, the DOAJ import. No new engine.
