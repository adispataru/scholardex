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
- **Slice 2 — export mechanism (TODO):** an `INDICATOR_TOTAL` scalar-cell policy — write a named indicator's computed
  total into a template cell (currently the only scalar cells are the 3 MANUAL Hirsch ones; there's no
  scalar-from-indicator path yet).
- **Slice 3 — template (TODO):** copy `Fisa-verificare_Informatica_2016.xlsx` → `…2026.xlsx`; add the
  "Număr proiecte ca director" cell in `D-Perspectiva D` (bound to the indicator total), the `Centralizator` criterion
  `count ≥ 1`, and the **A\*+A** publication row. Team-size/competition footnote.
- **Slice 4 — plumbing (TODO):** `Informatica2026ReportTypeImportSupport` (copy of 2016 → new `reportTypeKey` + binding
  → 2026 template); set FV Info 2026 `reportTypeKey = informatica-2026`.

## References
- Export machinery: `Informatica2016ReportTypeImportSupport`, `report-templates/informatica-2016/binding.json`,
  `TemplateXlsxRenderer`, `TemplateXlsxScoreParser`, `ActivityBlockProjector`.
- Grant scoring precedent: `Info_D_v` (GenericActivity, budget tier × director multiplier).
- H50 (report export/import) is the parent mechanism.
