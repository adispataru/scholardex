# H79 Informatica 2026 report (CNATDCU standards update)

**Status:** Scoped (2026-07-03)
**Created:** 2026-07-03 — from the 2026 CNATDCU standards (`data/standards/2026/standarde-{conf,prof,abilitare}-2025.html`)
compared against the shipped `informatica-2016` report.

## Context

The 2026 Informatica standard (COMISIA 2. INFORMATICĂ) keeps the **conf/prof numeric minima essentially unchanged**
from 2016 — the changes are structural + a few new data/scoring rules. Verified against code (2026-07-03):

| Perspective | 2026 Conf | 2026 Prof | already in `informatica-2016` |
|---|---|---|---|
| b) production | min 32, A*+A+B≥16 | min 56, **A*+A≥24**, A*+A+B≥40 | Perspectiva B 32/56 + "Publicații de top" 16/40 ✓ (A*+A≥24 is **new**) |
| c) impact (citations) | min 48, A*+A+B≥12 | min 120, A*+A+B≥40 | Perspectiva C 48/120 + "Citări de top" 12/40 ✓ |
| d) academic | min 36 | min 60 + ≥1 R&D project | Perspectiva D 36/60 ✓ |

Standard is 4 perspectives a)–d), each **îndeplinit/neîndeplinit**, all eliminatory (must pass).

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

1. **Copy the report.** Leave `informatica-2016` untouched (the 2016 standards, all positions, all users). Create
   `informatica-2026` as a **copy** that **keeps assistant + lecturer** thresholds too (2026 dropped them nationally,
   but UVT keeps them as internal standards) and updates **conf / prof** + adds **HABIL** (`HABIL` enum added; extract
   the numbers from `standarde-abilitare-2025.html` — its section is structured differently, needs a dedicated parse).
   Open: the 2026 national standard has no grand **Total** (eligibility is per-perspective) — decide whether the 2026
   copy keeps a Total row for the internal asist/lect continuation.
2. **New versioned indicators (no re-point).** `informatica-2016` keeps pointing at `Info_C` (`CANDIDATE_ONLY`) and its
   existing A*+A+B "top" filter indicators. Create **`Info_C_2026`** (`ANY_COAUTHOR` — H61 mechanism done; the 2026
   text confirms per-cited-paper) and a 2026 copy of the A*+A+B "top" filter indicator; `informatica-2026` points at
   the new ones. `Info_C` itself is never modified.
3. **New `A*+A` prof production indicator** — a category-restricted counter over {A*, A} pubs + the prof `≥24`
   threshold criterion (2026-only).
4. **SENSE 16→12 — keep both indicators.** Refactor `ComputerScienceBookService` to **return the category** (not
   hard-coded points). Keep the 2016 book/chapter indicator (formula → 16/8/4/2/1) AND add a 2026 book/chapter
   indicator (formula → 12/8/4/2/1); the two reports point at their respective indicators.
5. **DOAJ APC capture + fee-journal exclusion, with per-year edition resolution.** Extend `DoajJournalFact` (+ import +
   projection) to carry the APC flag from the dump's `APC` / `Has other fees` columns, stamped with the **edition
   year** (`as_of`). The exclusion must resolve **per publication year**: for a pub in year Y, use the DOAJ edition for
   Y, else the **closest previous edition** (mirrors the H60 ranking-year / forum-list "closest earlier year"
   resolution). This needs **multiple DOAJ editions over time** — we currently have one snapshot (`…20260517…`), so
   backfilling history requires older DOAJ dumps; v1 can use the single snapshot as the floor while the multi-edition
   resolution is wired. Then the CS journal scorer/threshold path excludes fee-charging journals from the
   perspective-b/c threshold points. (Predatory/Beall's is already gated.)

## Open questions / notes

- **DOAJ editions.** Only the 2026-05 snapshot is on disk; per-year APC resolution wants a series of editions. Source
  older DOAJ dumps (they're published) to backfill; until then the single edition is the floor.
- **b↔c 20% compensation** (up to 20% of perspective-b thresholds transferable from c) — niche; defer (H68 #6).
- Prof d) "≥1 R&D project" minimum — a small extra gate; the H64 project layer + `proj_*` injection already supplies
  project data.
- Depends on: H61 (mechanism done), H64 (projects), the SENSE/CORE scorers, the DOAJ import. No new engine.
