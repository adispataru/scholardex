# H08-S05 Observability Regression Guards
Status: active indexed source-of-truth document.

Date: 2026-03-04

## Added guardrail command

- `npm run verify-h08-observability-guardrails`
- Script: `scripts/verify-h08-observability-guardrails.js`
- Baseline entrypoint: `npm run verify-h08-baseline`

## Guard coverage

1. Runtime stacktrace-printing regression guard
- Blocks new `printStackTrace()` usage in `src/main/java/**`.
- Debt-aware allowlist keeps current known debt explicit until remediation.

2. Runtime stdout/stderr regression guard
- Blocks new `System.out.println` / `System.err.println` usage in `src/main/java/**`.
- Debt-aware allowlist for known legacy files; prints allowlist-shrink hints when debt is removed.

3. Scheduler diagnostics floor guard
- Verifies `ScopusUpdateScheduler` still contains critical operability diagnostics markers:
  - `@Scheduled` loop presence,
  - publication-task failure log marker,
  - citation-task failure log marker.

## Notes

- This slice is characterization-first and debt-aware by design.
- Guardrails prevent new observability regression while allowing planned remediation in `H08-S06`.
