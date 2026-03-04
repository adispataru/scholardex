# H01-S05 Consolidation Strategy

Date: 2026-03-03

## Scope

This strategy follows H01-S04 priority order:

1. `P0` -> `C01` (CNFIS scoring path)
2. `P1` -> `C04` (reporting scoring family)
3. `P2` -> `C06` (asset scripts)

## Strategy Decisions

- `C01`: Keep one CNFIS service path and harden behavior via characterization tests before any structural refactor.
- `C04`: Consolidate shared scoring behavior through common helpers/base contracts first, then reduce duplication in concrete scorers.
- `C06`: Keep intentional command-role differences, but centralize reusable validation flow where safe.

## C01 (P0) — Target Shape and Migration

### Target shape

- Single authoritative CNFIS scoring path remains `CNFISScoringService2025`.
- CNFIS subtype contract is explicit in code:
  - preferred source: `scopusSubtype`
  - fallback: `subtype`
- Category parsing is resilient and never silently skips only because delimiter format is missing.

### Migration steps

1. Add characterization tests around current `CNFISScoringService2025` behavior:
   - subtype fallback precedence
   - malformed/missing category delimiter
   - `cp` and `ch` branch behavior (IEEE/WoS conditions)
2. Keep current service boundaries; avoid premature extraction until test coverage is stable.
3. Remove dead local artifacts (unused locals/log noise) only after tests are in place.
4. Document CNFIS input assumptions in code comments and drift docs.

### Exit criteria

- CNFIS characterization tests exist and pass.
- No unresolved ambiguity in subtype/category parsing behavior.
- CNFIS scoring behavior changes require test updates (not implicit drift).

## C04 (P1) — Target Shape and Migration

### Target shape

- Scoring family uses one consistent shared contract for:
  - category/index parsing safety
  - allowed-year handling behavior
  - subtype interpretation policy (where applicable)
- Factory resolution never returns `null`; unknown strategy is explicit failure.
- Combined strategy dispatchers (`ComputerScienceScoringService`) match available strategy support.

### Migration steps

1. **Sub-cluster A: Safety/contract**
   - unify category parsing helper usage across abstract bases/concrete scorers.
   - align parse failure semantics (safe fallback + log, not hidden divergence).
2. **Sub-cluster B: Factory/dispatch**
   - replace `ScoringFactoryService` `return null` with explicit exception.
   - align `ComputerScienceScoringService` subtype dispatch with intended strategy list (`bk` path decision: enable or remove strategy).
3. **Sub-cluster C: Metadata cleanup**
   - fix copy/paste logger class bindings and stale descriptions.
4. **Sub-cluster D: Structural dedup (optional after A/B/C)**
   - extract repeated scoring extractor patterns into helper lambdas/utilities if still duplicated.

### Exit criteria

- Factory no longer returns `null`.
- Category parsing contract is consistent across core scoring abstractions.
- Dispatch coverage and enabled strategies are aligned and documented.
- Drift-prone copy/paste metadata mismatches are removed.

### Status update (2026-03-03, H01-S07)

- Completed: Sub-cluster B step 1 (`ScoringFactoryService` now throws `IllegalArgumentException` for null/unsupported strategies).
- Deferred intentionally: `ComputerScienceScoringService` `bk` dispatch decision (enable vs remove) remains for next C04 slice.

### Status update (2026-03-03, C04 Slice 2)

- Completed: Sub-cluster B step 2 dispatcher alignment in `ComputerScienceScoringService`.
- Combined `CS` now delegates book flows explicitly:
  - publications: `bk`, `ch`
  - activities: `Book`, `Book Series`
- Sub-cluster B is now complete (factory explicit failure + dispatch alignment).

### Status update (2026-03-03, C04 Slice 3)

- Completed: Sub-cluster A parsing safety unification via `ScoringCategorySupport`.
- `AbstractForumScoringService` and `AbstractWoSForumScoringService` now share the same safe category eligibility contract.
- Economics scorer category parsing now uses shared helpers (removing ad-hoc split logic).

### Status update (2026-03-03, C04 Slice 4)

- Completed: subtype-source policy alignment via `PublicationSubtypeSupport`.
- Reporting services now consistently resolve subtype as `scopusSubtype` first, fallback `subtype`.

### Status update (2026-03-03, C04 Slice 5)

- Completed: metadata/logger cleanup for C04 drift closure.
- Logger targets corrected and stale strategy descriptions aligned with actual behavior.

## C06 (P2) — Target Shape and Migration

### Target shape

- Asset contract stays centralized in `scripts/assets-contract.js`.
- `build-assets.js` and `verify-assets.js` keep role-specific outcomes but reuse a shared missing-assets helper.

### Migration steps

1. Add `scripts/assets-validation.js` with `findMissingAssets(expectedAssets)` helper.
2. Update both scripts to consume shared helper and keep distinct command behavior:
   - build: fallback when esbuild unavailable if prebuilt assets exist
   - verify: strict failure on missing assets
3. Add CI smoke chaining (or document local gate) to run `build` then `verify-assets`.

### Exit criteria

- No duplicated missing-assets filter logic across scripts.
- Build/verify semantics remain intentionally different and documented.
- CI (or equivalent gate) exercises both commands in sequence.

## Cross-Cutting Guardrails

- Keep all consolidation slices small and test-gated.
- Prefer behavior-preserving refactors before rule changes.
- Update `docs/h01-drift-findings.md` after each slice with status notes.
- Reject reintroduction of runtime backup templates through `verify-template-assets`.

## First Execution Slice Recommendation

- Start with `C01` test hardening (smallest high-value risk reducer), then `C04` sub-cluster B (`ScoringFactoryService` null removal) as first refactor slice.
