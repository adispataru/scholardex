# H60 Relative Year Specs (recent-window + latest-rankings)

**Status:** Planning
**Created:** 2026-06-15

## Purpose

Add relative, self-rolling year windows to indicator scoring so report definitions stop carrying
fixed absolute year ranges that go stale every year. Two concepts:

- **Article inclusion** — "previous N years" (e.g. the math standard's `A_recent` = t‑1…t‑7).
- **Ranking-list selection** — "latest N rankings" the journal is scored against (e.g. the math
  standard's "use the latest ISI/SRI list available at submission, regardless of publication year").

Motivating case: the FV Matematică indicators (`Mate_S_recent` uses a fixed `Absolute(2018→2025)`
recent window — wrong for a 2026 dossier and frozen; all three use `scoreYearRange Absolute(2019→2023)`,
a stale ranking-list window). See `h50-individual-report-export-import.md` and the math standard
(Ordin 6129/2016, Anexa 1).

## Findings that shape the design (verified 2026-06-15)

- **`yearRangeSpec` (article inclusion) is NOT enforced anywhere in scoring.** Its only readers are
  `IndicatorV1MigrationRunner` and a fingerprint string in `UserIndicatorResultService`.
  `ScientificProductionService` scores every publication passed to it regardless of year. So today
  `Mate_S_recent`'s window is dead config and `Srecent == S`. → enforcing inclusion is **net-new work**,
  and turning it on will change results for any indicator that already carries a (silently-ignored)
  `Absolute` year range — needs a sweep + tests.
- **`scoreYearRangeSpec` IS enforced**: `allowedYears(itemYear)` → `AbstractForumScoringService.computeScoresWithForum`,
  which picks the year with the **highest** RIS among the candidate years.
- **`ScoreYearRangeSpec.AllYears` already calls `LocalDate.now()`** (`[1990..currentYear]`), so wall-clock
  is already in the scoring path — relative resolution is not introducing a new determinism class, but
  this task should remove the drift (anchor on a reference year instead).

## Decisions (locked 2026-06-15)

1. **Anchor = `referenceYear` stored on the run.** Relative windows resolve against a `referenceYear`
   captured when the run is generated (default = the run's creation year; may later be a configurable
   "evaluation year" on the report). Persisted on `UserIndividualReportRun` → deterministic replay; the
   export/detail path re-scores and MUST read `run.referenceYear`, not `now()`.
2. **`YearRangeSpec.PreviousNYears(int n)`** → resolves to `[t-n … t-1]` (excludes the reference year t),
   matching the standard's `A_recent`. (`LastNYears`, the inclusive `[t-n+1 … t]` variant, is only added
   if a report actually needs it — otherwise skipped to avoid two near-identical types.)
3. **`ScoreYearRangeSpec.LatestNRankings(int n)`** → the `n` most recent ranking list-years **present in
   our DB and ≤ referenceYear** (NOT the journal's own available years). Rationale:
   - Correct "excluded journal" semantics: a journal dropped from the latest list is **not found →
     excluded**, instead of silently falling back to an older year where it was still ranked.
   - Journal-independent, consistent candidate window across a dossier.
   - Deterministic: capping at `referenceYear` means a later import of a newer ranking list does not
     retroactively change an old run.
   - `n>1` tolerates a journal's year-to-year list wobble (the extractor still picks the best RIS among
     the latest n); math uses `n=1` (single latest list).

## Architecture / how it folds

1. **Model**: add `referenceYear` to `UserIndividualReportRun`; set in `buildAndSaveRun`. Add the two new
   records to the sealed `YearRangeSpec` / `ScoreYearRangeSpec` `permits` clauses. MongoDB `_class`
   handles persistence (no Jackson annotations needed); existing `Absolute`/`AllYears` configs stay valid.
2. **Resolution contract**: grow `ScoreYearRangeSpec.allowedYears(int itemYear)` into a context-aware
   resolve — `{ itemYear, referenceYear, availableRankingYears }`. `LatestNRankings` is resolved where the
   ranking dataset is known; needs a **"distinct ranking list-years in DB" lookup** (cached, filtered
   `≤ referenceYear`) — the new input the context carries. Update the ~8 scoring services + `computeScoresWithForum`.
3. **Enforce `yearRangeSpec` (net-new)**: add a publication-year inclusion filter in the scoring path
   keyed on `getEffectiveYearRange().resolve(referenceYear)`. Sweep existing indicators for dead absolute
   ranges before enabling; cover with tests.
4. **Reference-year threading**: pass `referenceYear` from the run into
   `UserReportFacade.computeReportScopedIndividualReport` / `buildReportScopedIndicatorDetail` →
   `ScientificProductionService` → scoring services. The export/detail path must use the run's stored
   `referenceYear`.
5. **Admin UI**: indicator editor gains "Previous N years" / "Latest N rankings" options + an `n` field.
6. **Determinism**: keep the H52 replay-shape gate green; relative resolution is pure given
   `(referenceYear, availableRankingYears)`. Optionally migrate `AllYears`'s `now()` to `referenceYear`.

## Apply to FV Matematică (after the mechanism lands)

- `Mate_S_recent.yearRangeSpec` → `PreviousNYears(7)`.
- `Mate_S` / `Mate_S_recent` / `Mate_C` `scoreYearRangeSpec` → `LatestNRankings(1)` (latest list).
- (Separate, config-only, not part of H60: fix `Mate_C` formula to **count** qualifying citations
  `SRI ≥ 0.5 ? 1 : 0` and the `≥ 0.5` vs `> 0.5` boundary — see the math-standard analysis.)

## Testing note (2026-06-15)

- **Do not validate the recent-window enforcement against florin's run.** His publications are all
  within the recent window, so `Srecent == S` whether or not inclusion filtering is enforced — a real
  run can't distinguish "filter works" from "filter is a no-op". Cover `PreviousNYears(n)` with **unit
  tests** using synthetic publications that straddle the boundary (an old pub that must be excluded from
  Srecent but kept in S), not with a live export.

## Exit criteria

- `PreviousNYears`/`LatestNRankings` resolve deterministically from `(referenceYear, DB ranking years)`;
  unit-tested incl. excluded-journal (not in latest list → no score) and boundary cases.
- `yearRangeSpec` inclusion filtering is enforced in scoring and covered by tests; no regression on
  indicators with `AllYears`.
- A run records `referenceYear`; re-export/replay of an old run uses the stored year (stable across a
  later ranking import). Replay-shape guard green.
- Admin editor can configure both relative specs.

## Dependencies

Builds on the H52 typed indicator/scoring infrastructure (`YearRangeSpec`, `ScoreYearRangeSpec`,
strategy registry). No hard external dependency.
