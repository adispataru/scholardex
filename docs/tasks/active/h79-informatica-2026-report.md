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
- **Info_C citation exclusion — RE-POINT.** `Info_C.kind.policy = CANDIDATE_ONLY` today; 2026 c) requires *citing pubs
  share no author/co-author with the cited paper* = **`ANY_COAUTHOR`** (H61 — mechanism already shipped). The standard
  also **answers H61's open question**: per-cited-paper. Cheap re-point + re-stamp.
- **SENSE book points — 16→12 for the top tier.** `ComputerScienceBookService:125-133` scores **A=16**/B=8/C=4/D,E=2
  (chapter one rank lower); 2026 wants **A=12**/8/4/2/1. Fix: have the SENSE scorer **return the category** and let the
  indicator formula map category→points (so 2016 keeps 16, 2026 uses 12), or a `SENSE2026` variant.
- **APC/fee-journal exclusion — DATA IS ON DISK, not yet parsed.** 2026 b) excludes journals "care condiționează
  publicarea articolului de plata unei taxe" from threshold points. The offline DOAJ dump
  `data/doaj/doaj_journalcsv_20260517_2321_utf8.csv` **already carries** cols `APC` (yes/no), `APC amount`,
  `Has other fees`. But `DoajJournalFact` stores only id/issn/title — the importer (`DoajOnboardingService`) **doesn't
  capture the APC fields yet**. So: extend the DOAJ fact + import + projection to carry the APC flag, then gate the
  Informatica forum threshold-points on non-fee journals. (Predatory/Beall's is already gated.)

## Slices

1. **Report variants + positions.** Keep `informatica-2016` as an **internal** report (assistant/lecturer national
   standards were dropped in 2026, but UVT wants them internally). Create a **`informatica-2026`** report (copy) with
   **conf / prof / HABIL** thresholds only (`HABIL` enum value already added). Extract the HABIL threshold numbers from
   `standarde-abilitare-2025.html` (its section is structured differently — needs a dedicated parse). Drop the "Total"
   criterion for the 2026 variant (eligibility is per-perspective, no grand total).
2. **`Info_C` → `ANY_COAUTHOR`** (config + hash re-stamp via the `indicator-migration` profile).
3. **New `A*+A` prof production indicator** — a category-restricted counter over {A*, A} pubs + the `≥24` prof
   threshold criterion.
4. **SENSE 16→12** — refactor `ComputerScienceBookService` to expose the category; 2026 book/chapter indicator formulas
   map category→points (12/8/4/2/1); 2016 keeps 16/8/4/2/1.
5. **APC/fee exclusion** — extend `DoajJournalFact` (+ import + projection + the CS journal scorer/threshold path) to
   capture and honor the DOAJ APC flag; exclude fee-charging journals from the perspective-b/c threshold points.

## Open questions / notes

- **APC per-year.** The standard implies per-publication-year APC status; we have a single current DOAJ snapshot
  (2026-05). v1 = current-snapshot APC flag; historical per-year APC would need historical DOAJ dumps (defer).
- **b↔c 20% compensation** (up to 20% of perspective-b thresholds transferable from c) — niche; defer (H68 #6).
- Prof d) "≥1 R&D project" minimum — a small extra gate; the H64 project layer + `proj_*` injection already supplies
  project data.
- Depends on: H61 (mechanism done), H64 (projects), the SENSE/CORE scorers, the DOAJ import. No new engine.
