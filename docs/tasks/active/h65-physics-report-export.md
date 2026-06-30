# H65 Physics (Fizică / FF) report — DOCX export

**Status:** Planning — **postponed behind [H63](h63-openalex-enrichment.md) (OpenAlex) + [H64](h64-canonical-projects.md)
(canonical projects)**, which supply data the physics indicators need (corresponding author for P; trusted
project budget/attribution for A9/A10).
**Created:** 2026-06-16

Source docs (user-supplied): `FF_Criterii-concurs.pdf` (Ordin 6129/2016, Anexa 1 — Fizică), report template
`FV-Verificare_FF-1.docx`. Bound report id: TBD.

## Scoping done so far (2026-06-16)

The largest fišă yet — **21 tables**. Methodology read (PDF pages 1–5); template fully mapped.

### The core new primitive: `n_ef` (effective author count)
A bracketed transform of the real author count, used as the divisor everywhere instead of raw `N`
(dampens huge HEPP author lists):
```
n ≤ 5 → n ;  5<n≤15 → (n+5)/2 ;  15<n≤75 → (n+15)/3 ;  n>75 → (n+45)/4
```
HEPP exception: `n_ef` may come from the internal note's author count — treat as manual/out of scope.
Engine fit: bind a computed `Nef` var in `FormulaContext` (publication path `ScientificProductionService`
+ activity path `ActivityReportingService`) and declare it in `FormulaVariableContract`. Then most
indicators are config (`S/Nef`, `4/Nef`, …).

### Indicators (summary table T20: A | I | P | C | h | T)
| id | what | formula | notes |
|---|---|---|---|
| I | articles as author | ΣAIS/Nef | reuse AIS strategy |
| P | principal/corresponding author | ΣAIS | **first-author now (H65); corresponding via H63**; alphabetical-ordering exclusion stays manual |
| A1 | books intl, author | Σ4/Nef | WoS Master Book List publisher allowlist |
| A2 | chapters intl / reviews | Σ1/Nef | |
| A3 | books intl, **editor** | Σ0.5/Nef | author-vs-editor role |
| A4 | national books/manuals | Σ0.5/Nef | |
| A5 | national chapters | Σ0.2/Nef | |
| A6 | ISI Proceedings | Σ0.2/Nef | |
| A7 | intl patents | Σ3/Nef | reuse `Brevet` activity (N_autori→Nef, Tip=Triadic) |
| A8 | national patents | Σ0.5/Nef | reuse `Brevet` (Tip=National) |
| A9 | project/program director | Σ0.5 (count) | reuse `Proiect educational`; excludes research projects |
| A10 | research project € | ΣV/100000 | reuse `Grant Cercetare.Buget` now → **H64 canonical project budget** later |
| A | total didactic | ΣA_i | |
| C | citations in journals w/ non-null IF | **count** | like Mate_C count; self-cit rule TBD |
| h | Hirsch index | — | **new computation** over candidate pubs (have `citedByCount`) |
| T | composite total | TBD | Conf `A+I+P=5` fits; Lector inconsistent — confirm formula |

### Template (21 tables)
- Reference/static: T0–T4 (formulas, lector + conf/prof). Prerequisites: T5 (Da/Nu — doctorate, ≥2
  recommendation letters → manual). Detail fill: T6 (articles + `Autor principal` flag), T7–T16 (A1–A10),
  T17 (I), T18 (P), T19 (citations). Summary: **T20** (A/I/P/C/h/T × Lector/Conf/Prof/obtained).
- Thresholds seen: Lector A≥0.5,I≥1,P≥1,T≥1.5 · Conf A≥1,I≥2,P≥2,C≥20,h≥5,T≥5 · **Prof row not yet read**.

### Reuse vs new
- **Reuse**: AIS strategy; the FEAA/CNCSIS publisher-allowlist pattern (here WoS Master Book List);
  Mate_C citation-count; the docx render/binding/cell-total infra; existing activities `Grant Cercetare`
  (A10 Buget), `Brevet` (A7/A8 N_autori/Tip), `Proiect educational` (A9).
- **New**: `Nef` var (small, core); h-index computation; author-vs-editor role (A3); WoS Master Book List
  publisher recognition; `Nef` in the activity context (patents); the T composite.

## Dependencies (why postponed)
- **[H63] OpenAlex** → corresponding author so **P = first OR corresponding** (else P is first-author-only).
- **[H64] canonical projects** → trusted project budget + director-attribution for **A9/A10** (interim: the
  existing `Grant Cercetare`/`Buget` activity works, so physics *could* ship first-author + activity-based,
  but we chose to do the data first).

## Still to do before/while implementing
- **Read PDF pages 6–14**: Prof/CS I thresholds (T20 row), exact **C** definition (self-citation? IF
  threshold? count vs weighted), **h** threshold, the **T composite** formula, Da/Nu specifics, HEPP `n_ef`
  exception. (These gate the summary + slice 4.)

## Status (2026-06-26) — SLICE 1 DONE; methodology fully resolved from the PDF

All earlier "still to do / read PDF pages 6–14" gaps are closed (the FF PDF is in-repo, fully read). Resolved
formulas/thresholds: **I=ΣAIS/Nef · P=ΣAIS (first-or-corresponding) · A1..A8=k/Nef · A9=Σ0.5 (count) · A10=ΣV/100000 ·
C=Σcᵢ/Nefᵢ (ISI-IF cites, self-excluded) · h=WoS Hirsch · T=A+P/2+I/2+C/20+h/5**. Thresholds — Lector I≥1,P≥1,T≥1.5 ·
Conf A≥1,I≥2,P≥2,C≥20,h≥5,T≥5 · Prof A≥2,I≥4,P≥4,C≥40,h≥10,T≥12.

**Key reuse confirmed:** the DOCX binding/export framework is already working (`feaa-2024`/`matematica-2016` —
`TemplateDocxRenderer` + `binding.json` + a `@Component` support class; the old "H50.4 docx remaining" note was stale).
P uses the H63 `PUBLICATIONS_FIRST_OR_CORRESPONDING` role; h uses H67; C self-cit uses H61 `CANDIDATE_ONLY`.

**Slice 1 DONE (2026-06-26):**
- **1a (scoring foundation):** `EffectiveAuthorCountSupport.computeNef` (the Nef brackets); `Nef` bound on every
  publication/citation score (`ScientificProductionService`) + allowed in `FormulaVariableContract` → I=`S/Nef`,
  A1=`4/Nef`… are config formulas. `AbstractReport.Criterion` gained per-indicator `weights` (default 1.0) →
  the composite T = weighted criterion (`ReportingComputationSupport`). 0-author → Nef 0 → the non-finite guard zeroes it.
- **1b (fizica-ff DOCX):** `report-templates/fizica-ff/{template.docx (the real 21-table fišă), binding.json}` (I/P
  article tables 17/18 + T20 summary) + `Fizica2024ReportTypeImportSupport`; physics article roles wired into the
  snapshot dispatch. **Live-verified** on the maths department (provisional run): finite I=ΣAIS/Nef, P=ΣAIS, T for all
  13 people; render unit-tested vs the real template. Suite 2472/2472.
- **Note:** the `fizica-ff` report *definition* (which indicators/thresholds per position) is per-deployment config
  (admin-created/seeded), built up across slices — the code registers the report *type*. A1–A10/C/h indicators + the
  full threshold rows land in slices 2–4.

## Proposed slices
1. **Nef core + research half** — `Nef` primitive (helper + bind + contract), I, P (first-author), T17/T18 + I/P in summary. **[DONE 2026-06-26]**
2. **A1–A6** — books/chapters/reviews/proceedings (WoS Master Book List allowlist + editor role), A1–A6 tables + A subtotal. **[DONE 2026-06-26]** — modeled as **manual-entry activities** (the WoS Master Book List is confirmed unavailable, H66; the fišă is manual-evidence by design, so the candidate self-declares publisher + link, scored `k/Nef`). Nef bound in the activity path from `N_autori` (`ActivityReportingService`); 6 `STACKED_BLOCKS` A-table bindings (tables 7–12) + reusable renderer enhancements (`BindingBlock.totalMarker`, `firstDataRow`-aware block walk); A = ΣA₁..A₆ → summary A + `T = A + I/2 + P/2`. The 6 `Fizica_A*` activity/indicator defs are per-deployment config. Suite 2475/2475.
3. **A7–A10** — patents + projects (reused activities; `Nef` in activity context; A10 budget via H64), A complete.
   **[DONE 2026-06-29]** — code: `binding.json` 4 STACKED_BLOCKS roles `fizica-a7..a10` → real-template tables 13–16
   (verified: Brevet intl/national, program director, research-project director; same 5-col layout as A1–A6);
   `Fizica2024ReportTypeImportSupport.A_BLOCKS` → A1..A10 (generic `fizica-a*` dispatch already handled it) so
   **A = ΣA₁..A₁₀**. Render test vs the real 21-table fišă green (A7–A10 totals + A subtotal 5.20); transfer suite green.
   **Config (per-deployment, not code):** `Fizica_A7..A10` indicator defs + formulas (A7 `Tip=='National'?0:3/Nef`,
   A8 `Tip=='National'?0.5/Nef:0`, A9 `Rol=='Director'?0.5:0`, A10 `(proj_budget!=null?proj_budget:Buget)/100000`)
   each tagged `blockByIndicatorId→"A7".."A10"` + role `fizica-a7..a10`, and the A-criterion `indicatorIndices`
   extended to A7–A10 — mirroring the slice-2 `Fizica_A1..A6` defs. A10 = first physics consumer of the H64 trusted budget.

   ### Slice 3 — scope (2026-06-29)
   Mirrors slice 2 (per-table STACKED_BLOCKS + per-deployment indicator config). **No activity/seed gaps** — all three
   underlying activities already exist with the needed fields:
   - **Brevet** — `N_autori`, `Tip` ∈ {Triadic, European, International, National}, `Dovezi` → A7 (intl) + A8 (national)
   - **Proiect educational** — `Rol` ∈ {Director, Membru}, `Nume` → A9 (director count; *educational/program*, not research)
   - **Grant Cercetare** — `Rol`, `Buget`, `Nume Proiect`, `refs=[PROJECT_GRANT_ID]` → A10 (budget, **via H64 `proj_budget`**)

   **Code (this slice):**
   - `fizica-ff/binding.json`: add roles `fizica-a7`..`fizica-a10` at `tableIndex` 13–16, blocks
     `{activityName:"A7".."A10", firstDataRow:1, totalMarker:"Punctaj total indicator A7".."A10"}` (copy A1–A6).
   - `Fizica2024ReportTypeImportSupport`: extend `A_BLOCKS` from `A1..A6` → `A1..A10` (the dispatch is already generic —
     `roleKey startsWith "fizica-a"`; this makes the **A subtotal = ΣA₁..A₁₀** and iterates the new blocks).
   - Renderer: no change expected (slice 2 added `totalMarker` + firstDataRow block walk).
   - Tests: binding render of tables 13–16 + scoring per indicator (below).

   **Config (per-deployment `fizica-ff` report def — like the slice-2 `Fizica_A*` defs):** indicators + formulas, each
   tagged to its role + `blockByIndicatorId → "A7".."A10"`, and the A-criterion `indicatorIndices` extended to A7–A10:
   - **A7** (Brevet, intl): `Tip == 'National' ? 0 : 3/Nef`  (Triadic/European/International all = 3/Nef)
   - **A8** (Brevet, national): `Tip == 'National' ? 0.5/Nef : 0`
   - **A9** (Proiect educational): `Rol == 'Director' ? 0.5 : 0`  (count; research excluded by activity choice)
   - **A10** (Grant Cercetare): `(proj_budget != null ? proj_budget : Buget) / 100000`  ← **first physics consumer of
     the H64 trusted budget**, same fallback form as CS `Info_D_v`
   - `Nef` for A7/A8 is bound from `Brevet.N_autori` (slice-2 activity-path Nef); A9/A10 don't use Nef.

   **Decisions/risks:** confirm the patent weighting against the PDF (assumed all non-national patents = 3/Nef; if
   European/International carry sub-weights, A7 becomes a bracketed formula). A9 director-attribution stays declared
   (`Rol`); canonical director via H78. A10's `proj_budget` only engages once a researcher links a project (H78) — until
   then it falls back to declared `Buget`, so no score moves on existing data.
4. **C + h + T + summary** — citation count, Hirsch computation, composite T, T20 obtained row. (Da/Nu manual.)
   **[SCORE DONE 2026-06-29]** — `Fizica2024ReportTypeImportSupport` reads the `fizica-c` (C=Σcᵢ/Nefᵢ) + `fizica-h`
   (WoS Hirsch) indicator totals and renders the full composite **T = A + P/2 + I/2 + C/20 + h/5**; binding summary
   `docxTotals` add cells 4=C, 5=h. Render test vs the real template green. C/h are per-deployment config indicators
   (C = Mate_C `{RIS, excludeSelf}` kind + formula `S/Nef`; h = `HIRSCH`/`WOS_VENUE`). **This completes the report's
   SCORING** (I/P/A1–A10/C/h/T + summary vs Lector/Conf/Prof thresholds).
   **Follow-on (4-citations, deferred):** the **C citation DETAIL table (table 19)** is *nested* — cited-pub rows
   (`I., II.`) each with citing sub-rows (`1., 2.`) and merged cells — unlike the maths flat `FIXED_TABLE` citation
   table. Filling it needs renderer work (a nested cited→citing expansion) verified against the rendered output; it is
   evidence, not score, so it's split out. The other detail tables (I=17, P=18, A1–A10=7–16) already render.

   ### Slice 4 — scope (2026-06-29) — final slice
   Verified template layout: summary **table 20** columns `Indicator | A | I | P | C | h | T` (cells 1–6 → C=cell 4,
   h=cell 5, T=cell 6; rows: Lector has `-` for C/h, Conf C≥20/h≥5, Prof C≥40/h≥10); **table 19** = citations detail
   (`Nr.publ.citată | Nr.publ.care citează | Referinţa | … | Punctaj`). h has **no** detail table — it's a summary scalar.
   - **C** = `Σ cᵢ/Nefᵢ` (post-PDF resolved, Nef-weighted, NOT a raw count — early "count" note superseded). **Pure
     config, no new scoring code:** reuse **Mate_C's exact kind** (`{strategy:"RIS", excludeSelf:true}`) — that strategy
     bakes in the IF-journal ("non-null IF") citation universe + self-exclusion and produces the per-pub base `S`; the
     scorer already binds `Nef` per pub (slice 1, `ScientificProductionService`). The fizica-c formula is just **`S/Nef`**
     (vs Mate_C's `S >= 0.5 ? 1 : 0`) → summed over pubs = `Σ cᵢ/Nefᵢ`. So the IF-gate + self-exclusion live in the
     **kind**, the Nef-weighting in the **formula**.
   - **h** = WoS Hirsch: a **`HIRSCH`** strategy indicator, **`HIndexSource.WOS_VENUE`** (H67; *indicative* — computed
     from our citation graph, not the official WoS index — carry the H67 caveat).
   - **T** = `A + P/2 + I/2 + C/20 + h/5` (extend the support's current `A + P/2 + I/2`).

   **Code:**
   - `binding.json`: add `fizica-c` STACKED/citation role → table 19 (citation rows + total → `C`); add summary
     `docxTotals` cells **4 = C** (`totalKey "fizica-C"`) and **5 = h** (`totalKey "fizica-h"`). (h needs no table.)
   - `Fizica2024ReportTypeImportSupport`: dispatch C citation snapshot items → table 19; read `C` + `h` totals;
     **T = A + P/2 + I/2 + C/20 + h/5**; put C/h into the summary totals.
   - Tests: render table 19 (C rows + total) + summary C/h/T against the real template.

   **Config (per-deployment):** the `Fizica_C` indicator (Mate_C-style citation count, IF-journal gate, `CANDIDATE_ONLY`
   self-exclusion, Nef-weighted) + `Fizica_h` (`HIRSCH`/`WOS_VENUE`), wired to roles `fizica-c`/`fizica-h`; the full
   per-position threshold rows (Lector/Conf/Prof incl. C/h) in the `fizica-ff` report def.

   **Resolved (2026-06-29):** C reuses Mate_C's RIS+excludeSelf kind (IF-gate + self-exclusion baked in) with formula
   `S/Nef` — **no new scoring code** (config only, like A1–A10). The only build complexity left is the **C citation
   detail rows** (dispatch publication-citation snapshot items into table 19) — confirm the C snapshot item shape
   (cited pub / citing count / reference) matches Mate_C's existing citation projection, and reuse it.
   **Risks/notes:** h's `WOS_VENUE` source is *indicative* (H67 caveat — carry it on the report). Da/Nu (T5
   prerequisites: doctorate, ≥2 recommendation letters) stay manual — out of scope. This **completes H65** (full
   21-table fišă: I/P/A1–A10/C/h/T + summary vs thresholds).

## Exit criteria
Physics fišă exports per a real run: I/P (Nef), A1–A10 + A, C, h, T, summary vs thresholds; corresponding-author
P once H63 lands; trusted project figures once H64 lands; replay-shape guard green; no regression to other report types.
