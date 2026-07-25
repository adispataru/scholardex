# H81 — Informatică 2026 Fișă (xlsx export/import)

**Status:** Slice 1 DONE 2026-07-04. A 2026 version of the `informatica-2016` xlsx export/import (H50 family), adapted
from the 2016 template. Investigation established the structure barely changes; the deltas are the A\*+A publication
criterion and a new perspective-d **director-project count** criterion.

## Standard basis
- **A\*+A publication criterion** (2026 adds `Publicații A*+A`; 2016 had only A\*+A+B). It's a threshold computed by
  Excel formulas on the `Centralizator` sheet from the exported publications — a **template** row, not a new export
  sheet/role. FV Info 2026 declares the identical export roles as 2016 (journals / conferences / citations /
  perspectiva-d activities).
- **Perspective-d project threshold** (`Praguri: Minim un proiect …*4`): at least **one** R&D&I project where the
  candidate is **director/coordonator/responsabil**, obtained **by competition**, **team ≥ 2 members + director**,
  funding **≥ 50,000 EUR**. Of these, only the **director role** and the **budget** are in our data; **team size and
  competition are not captured** → they remain researcher self-declaration + committee review (footnote on the Fișă).
  Budget lives only on the self-entered grant `Buget` field (project facts carry no budget — brainmap doesn't publish
  it).

## Approach (decided): an indicator computes the count, the template displays it
Cleaner than an Excel `COUNTIF` over free-text roles — the director/budget logic lives in one testable indicator, the
count becomes a real indicator the researcher sees, and the template just shows it + checks `≥ 1`.

## Slices
- **Slice 1 — director-project count indicator. DONE 2026-07-04.** New GenericActivity indicator
  `Info_D_Proiecte_Director` on the *Grant Cercetare* activity, formula
  `B = proj_budget != null ? proj_budget : Buget; (Rol != 'Membru' && B >= 50000) ? 1 : 0` → total =
  "Număr proiecte ca director". **Reuses `Info_D_v`'s proven `B` pattern** (Info_D_v already scores grants budget-tiered
  with `Rol == 'Membru' ? X : X*2`), so **no `Buget` field-type change** — which matters because Info_D_v is shared with
  FV Info 2016, and making `Buget` numeric would alter its behaviour on blank budgets and mutate the frozen 2016 report.
  Added to FV Info 2026 only (role `perspectiva-d-director-count`); 2016 untouched. Re-stamped. **Live-verified:**
  florin's total = **1** (the Coordonator-local @ 225000 ≥ 50k; Membru @ 270000 and the two blank-budget grants
  correctly excluded). Mirrored to seed.
- **Slice 2 — export mechanism. DONE 2026-07-04.** New `INDICATOR_TOTAL` `BindingPolicy` — writes an indicator's
  computed total into a fixed template cell. `TemplateXlsxRenderer` gained a 4-arg
  `render(binding, rowsByRole, tilesByRole, totalsByRole)` overload (old 3-arg delegates with `Map.of()`) that, after
  the role loop, stamps each `INDICATOR_TOTAL` scalar cell with `totalsByRole.get(cell.source)` — the source is the
  indicator's export **role** key, and `ReportInstanceSnapshotBuilder.populateTotals` already keys the run's per-role
  totals by role for *every* report type (XLSX just never consumed them), so no snapshot change was needed. MANUAL
  cells (Hirsch indices) stay untouched; a missing total or absent sheet is a warn-and-skip (a template can declare the
  cell before its indicator exists). `Informatica2016ReportTypeImportSupport.render` now threads `snapshot.getTotals()`
  (harmless for 2016 — it ships only MANUAL scalar cells). Unit-tested (`TemplateXlsxRendererScalarCellTest`: stamp /
  MANUAL-untouched / no-matching-total-skipped); transfer suite green.
- **Slice 3 — template. DONE 2026-07-04.** New `report-templates/informatica-2026/template.xlsx` (openpyxl copy of the
  2016 one). `D-Perspectiva D`: added `C24` "Număr proiecte ca director (competiție, ≥50.000 EUR)" + `K24` (placeholder
  0, **outside** the `SUM(K10:K21)` points total, filled on export by the `INDICATOR_TOTAL` scalar cell) + a `C25`
  team-size/competition-declarative footnote. `Centralizator` (appended rows 23–24, above the Hirsch block, so no
  internal-formula shift): **A\*+A** criterion `D23 = 'B-Reviste'!J17+'B-Reviste'!J18+'B-Conferinte'!K19+'B-Conferinte'!K20`
  (the **correct** A\*+A subtotal — 2016's frozen `E10` summed only A\*, `J17+K19`; left untouched) with
  `E23 = IF(D23>=24,…)`; **director-project** criterion `D24 = 'D-Perspectiva D'!K24`, `E24 = IF(D24>=1,…)`.
  **Abilitare block** (rows 35–39, appended after the Hirsch/legend blocks so it disturbs neither the internal
  same-sheet formulas nor the binding-referenced Hirsch MANUAL cells `D25:D27`): per-perspective thresholds referencing
  the *stable* realized-points cells — B `D36=D7` gated `≥44 AND A*+A+B≥28 AND A*+A≥12`, C `D37=D11` gated
  `≥84 AND top-citări≥26`, D `D38=D15` gated `≥48`, and a combined `B39 "TOTAL abilitare" = AND(E36,E37,E38)` (the 2026
  standard sets no combined Total-points gate for abilitare). **Perspectiva-B per-rank gates corrected to 2026**
  *Publicații de top* (A*+A+B = CONF 16 / PROF 40 / HABIL 28) — and this aligned the formulas with the sheet's own
  labels, which the 2016 template contradicted: `C10` always read "…**40** de categ A* sau A sau B" but `E10` checked
  16, and `C8` read "oricare dintre categorii" (no gate) but `E8` checked 16. Fixes: `E8` (lector) → points-only
  `IF(D7>=12,…)`; `E9` (conf) stays A*+A+B ≥16; `E10` (prof) → A*+A+B ≥40 **and** A*+A ≥24 with the A*+A subtotal
  corrected from 2016's A*-only `J17+K19` to the true `J17+J18+K19+K20`. (Asistent `E7` untouched — its original
  `OR2`-referencing formula is out of scope.)
- **Slice 4 — plumbing. DONE 2026-07-04.** `report-templates/informatica-2026/binding.json` (copy of 2016 →
  `reportTypeKey`/`templateResource` retargeted + the `INDICATOR_TOTAL` scalar cell
  `{cell:"'D-Perspectiva D'!K24", source:"perspectiva-d-director-count"}` — sheet name **must** be quoted for POI).
  `Informatica2026ReportTypeImportSupport` (structural copy of the 2016 support; shared renderer/parser, threads
  `snapshot.getTotals()`). Registry auto-discovers it (no enum/UI key list to update). FV Info 2026 already carried
  `reportTypeKey = informatica-2026` (so export was previously *failing* on an unregistered support — this slice makes
  it resolve). Seed consistent: `individualReports.json` has the key + director-role mapping, `indicators.json` has the
  indicator. Unit-tested (`Informatica2026TemplateTest`): binding loads with the 4 roles + scalar cell; a render stamps
  `K24=1` from the run total and preserves the A\*+A / director criterion formulas.

## Live end-to-end verification — DONE 2026-07-04
Booted (`agent-dev`, port 8181, db `scholardex` — the fixed `spring.mongodb.uri`; the stale `test` DB is unused) and
exported florin's FV Info 2026 Fișă via `GET /reports/researcher/{email}/report/{reportDefinitionId}/export?run=…`
(run `6a493c559624190a66f260f6`, `directorScore=1`). HTTP 200, 84 KB valid xlsx. Confirmed in the **downloaded file**:
- **`D-Perspectiva D!K24 = 1.0`** — the director count stamped by the `INDICATOR_TOTAL` scalar cell from the run total
  (cached computed value also 1.0). Full seam proven: run → `snapshot.totals["perspectiva-d-director-count"]` →
  renderer scalar write → template cell → `Centralizator!D24 = 'D-Perspectiva D'!K24`.
- Perspectiva-B corrected gates present (`E8` points-only, `E9` A*+A+B≥16, `E10` A*+A+B≥40 ∧ A*+A≥24), the A*+A /
  director criteria, and the full Abilitare block — all intact after render.
- **Robustness bonus:** florin has >10 conference pubs, so the B-Conferinte table expanded +2 rows; POI's FormulaShifter
  correctly followed the moved aggregation cells — the exported cross-sheet refs became `K26` (A*+A+B) and `K21+K22`
  (A*+A), which post-shift are *exactly* "Total categoria A*+A+B" and "Total A*"/"Total A". Expansion + the new criteria
  formulas compose correctly. (B-Reviste unshifted — florin has ≤8 journal pubs.)

Remaining before stage/prod: the H80 data migration (seed + CORE re-import), gated on public-UI polish.

## References
- Export machinery: `Informatica2016ReportTypeImportSupport`, `report-templates/informatica-2016/binding.json`,
  `TemplateXlsxRenderer`, `TemplateXlsxScoreParser`, `ActivityBlockProjector`.
- Grant scoring precedent: `Info_D_v` (GenericActivity, budget tier × director multiplier).
- H50 (report export/import) is the parent mechanism.
