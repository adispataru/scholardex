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
- **Deferred (to first consumer):** count/point mode, Da/Nu gates, best-of assignment, cross-criterion compensation.
  Post-PhD anchor: the **data field landed early (2026-07-03)** — `User.ResearcherProfile.phdAwardYear` (Integer,
  nullable; workspace profile form + `/profile/save` round-trip, live-verified). The date-filtered *criterion* is still
  deferred to its first consumer (FSGC/drept), and must treat a null year as "no anchor" (count all).


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

## Slice 3 — percent-of-criterion caps (scoped 2026-07-24)

**Consumer (first real one):** Informatică Perspectiva D — the 2026 OM (3019/2025) caps D(x) Consolidare
echipe and D(xiv) pachete software at "maximum 10% din punctajul total al perspectivei d" (D(xvii) Premii
too, but that item is S3-gated and out of scope). The **2016 standard has the same three caps** (x, xiv,
xvi), so both FV Info reports get flagged. Verified: the official FV 2016 Excel does NOT implement these
caps (plain SUM on the D sheet) — whatever we ship becomes the operational interpretation.

**Semantics — fixed point, user decision 2026-07-24.** The final criterion total `T` satisfies
`capped_i = min(c_i, p_i·T)` and `T = rest + Σ capped_i`. Chosen over "percent of the other indicators"
(conservative, EU-indirect-costs style) and "percent of the raw total" (violates its own recheck) because
it is the only reading where the shipped state satisfies the OM constraint against the FINAL total, and it
is candidate-favorable — consistent with the standards' own favorability ethos ("lista cea mai favorabilă
candidatului"). Closed form via water-filling (≤|F| passes): assume all flagged caps bind,
`T = (rest + Σ non-binding c_i) / (1 − Σ binding p_i)`, drop any i with `c_i ≤ p_i·T`, repeat.
Numeric pin: rest=90, raw=30, p=10% → cap=10, T=100 (exactly 10%).
Guards: `1 − Σp ≤ 0` → fall back to percent-of-rest + warn (degenerate config); `rest=0` with all flagged
binding → T=0 (faithful to the letter; documented).

**Model:** `Criterion.maxPercentOfTotal: Map<Integer, Double>` (indicatorIndex → percent, 0–100), nullable,
mirrors the `weights` keying. Embedded doc — new nullable field is deserialization-safe, but boot against
real data anyway (record-component lesson).

**Engine:** second phase inside the single private `computeCriterionScores` core
(`ReportingComputationSupport`) — order: weights → percent caps (fixed point) → `maxTotal` clamp. Add a
detail overload exposing per-indicator effective contributions so UI/export can annotate.

**UI/export:** indicator card gets a "counts as X in <criterion> (10% cap)" note from the detail map; FV
export must keep the render→parse round-trip green (binding block-name invariant test).

**Data (AFTER code deploys — new field is read only by new code):** both FV Info reports' Perspectiva D
criterion: `maxPercentOfTotal: {13: 10, 17: 10}` — indices verified identical in both reports (13 =
Info_D_x, 17 = Info_D_xiv; safe because the D_v swap was done in-place).

**Tests:** the numeric pin above; non-binding flagged indicator; two flagged both binding (rest=80, raws
30+40, p=10% each → T=100, caps 10+10); one binding one not; rest=0; Σp≥1 guard; composition with weights
and maxTotal; legacy criterion docs without the field.
