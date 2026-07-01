# H68 Advanced criteria / threshold extensions

**Status:** In progress (scoped 2026-06-30) — building slices 1+2; 3/4/5 deferred to their first consuming report.
**Created:** 2026-06-16 (from the [standards capability assessment](../../standards-capability-assessment.md))

## Refined scope (2026-06-30, verified against code)

Baseline (verified): `AbstractReport.Criterion {name, indicatorIndices, thresholds[{position,value}],
contributesToTotal, weights}` embedded in the report Mongo doc; eval is pure **SUM(indicator scores) ≥ threshold** per
`Position`; pass/fail counted in `individual-report-dashboard.js`. `UserIndividualReportRun.referenceYear` exists (H60);
**no PhD date** on `User.ResearcherProfile`.

Status of the 6 extensions:
- **#2 Caps** — per-indicator cap is **DONE** (`Indicator.maxPoints` + `applyPointsCap`, wired in
  `ReportInstanceSnapshotBuilder:151` + `UserReportFacade:476`, unit-tested). Remaining: criterion-level cap +
  per-edition/group cap (niche).
- **#4 Count+point** — greenfield; highest recurrence (~8 non-STEM faculties); low ambiguity.
- **#1 Post-PhD anchor** — greenfield; needs `phdConferralDate` + a date-filtered criterion.
- **#5 Da/Nu gates** — greenfield; open question = the boolean's input source (manual attestation vs derived).
- **#3 Best-of assignment** — greenfield, most invasive (pre-scoring exclusivity + tie-break).
- **#6 Cross-criterion compensation** — greenfield, niche (sport only).

**Latent bug found:** `Criterion.weights` (H65) is applied in `ReportingComputationSupport` (render/export) but NOT in
`IndividualReportComputer` (compute) — the two criterion-score paths disagree on weighted criteria. Fix in slice 1.

Build decision (2026-06-30): **do slices 1+2 now** (safe, high-reuse, unambiguous); **defer 3/4/5** to their first
consuming report so the ambiguous semantics are pinned by a real standard (avoids the H61 "built-then-mis-specified"
trap). Consumers aren't being built yet.

- **Slice 1 — DONE (2026-06-30):** unified the **three** criterion-score computations (`ReportingComputationSupport`
  render/export, `IndividualReportComputer`, `GroupReportRunner`) onto one private lookup-agnostic aggregator, so H65
  `weights` now apply on the group paths too (they previously did a plain sum that ignored weights — the latent bug).
  Object-keyed lookup for the group paths (their indicators may have null ids), id-keyed for render/export. Tests +
  full suite green.
  **Count/point (ITEM_COUNT) deferred:** investigation showed it needs item-count plumbing through all three scorers
  (they return summed doubles only) AND is largely already expressible via a `[1]`-per-item indicator formula (the sum
  is then the count — per the assessment's DB reality check). Speculative + not "modest config" → folded into the
  deferred set with post-PhD/Da-Nu/best-of/compensation, to be pinned by a real consuming standard.
- **Slice 2 — DONE (2026-06-30):** criterion-level cap `Criterion.maxTotal` clamps the aggregated (post-weight) score;
  applied in the shared aggregator; admin-editor number input added. Per-indicator caps were already done
  (`Indicator.maxPoints`). Tested.
- **Deferred (to first consumer):** count/point mode, post-PhD anchor (profile `phdConferralDate` may land early),
  Da/Nu gates, best-of assignment, cross-criterion compensation.


## Goal

Extend the criteria/threshold engine for evaluation patterns that recur across domains but aren't expressed
by the current model (criteria = per-position thresholds over indicator score sums). These are **modest,
mostly-config extensions** — not a new engine.

## Extensions (each with the domains that need it)

1. **Post-PhD temporal anchor** — count only items dated after the candidate's doctorate ("Total după
   doctorat"). Needed by FSGC (C7), drept (C8), FSP-sociology (C7). Needs a **PhD-conferral date** on the
   researcher + a date-filtered cumulative criterion (beyond plain year-spec selectors).
2. **Per-indicator / per-group point caps (plafoane)** — "max 50p", "max 100p", "I29.1 ≤10p",
   "I9+I10 ≤2 contributions/conference edition". Needed by FLIT, FSP, sport, FMT. Expressible via `min()`
   if exposed at the criterion/indicator level; a per-edition group cap is new.
3. **Best-of single-indicator assignment** — "a publication maps to a single, most-favorable indicator"
   (cross-indicator dedup/assignment). Needed by FAD, FSGC, FLIT. A pre-scoring assignment pass.
4. **Mixed count + point criteria** — some criteria are "# articles ≥X" (count of items) and others
   "sum of points ≥Y" in the same grid. Needed by nearly all non-STEM. Add a **count-of-items** criterion
   type alongside the existing score-sum threshold.
5. **Da/Nu qualitative eligibility gates** — doctorate held, ≥2 recommendation letters, ethics/no-violation,
   habilitation. Needed by fizica (T5), drept (C1), arte. A boolean checklist distinct from point thresholds.
6. **Cross-criterion compensation** — a shortfall in one criterion compensated by another (sport: C1 via
   I2/I9 for a transitional period). Niche.

## Current baseline

`report.criteria` already carries `{indicatorIndices, thresholds[{position, value}]}` per position — the
score-sum + per-position-threshold half exists. This task adds the count/cap/best-of/temporal/boolean
variants on top.

## Open decisions

- PhD-date source (researcher profile?) + run reference date for the post-PhD anchor.
- Cap representation (per-indicator field vs `min()` in formula vs criterion-level).
- Best-of as a deterministic pre-scoring assignment; tie-break rule.
- Count-criterion vs point-criterion typing in the criteria model + the summary rendering.

## Relation

Sibling to [H66](h66-curated-allowlists.md) + [H67](h67-h-index.md) (the istorie "h OR citations" OR-gate
lands here). Consumers: FSGC, drept, FLIT, FAD, FSP, sport, fizica (Da/Nu).
