# H04-S02 Test Strategy and Pyramid Targets

Date: 2026-03-03  
Inputs: `docs/h04-test-inventory.md`, `docs/h03-flow-priority-map.md`, `docs/h03-reporting-contracts.md`

## 1. Purpose

Define a stable testing strategy that:

- keeps fast feedback for day-to-day changes,
- preserves behavior contracts established in H03,
- limits fragile high-level tests to critical flows only.

## 2. Target Pyramid (Class-Level Distribution)

Primary target bands for maintained tests (`src/test/java/**`):

- `unit`: `60% - 75%`
- `contract + slice`: `20% - 30%`
- `integration`: `5% - 10%`
- `e2e`: `0% - 5%` (introduced only for highest-risk cross-system flows)

Current baseline from H04-S01:

- `unit`: `14/24` (58.3%)
- `contract + slice`: `9/24` (37.5%)
- `integration`: `1/24` (4.2%)
- `e2e`: `0/24` (0%)

Rebalance intent:

- increase deterministic unit coverage for scorer/support hotspots,
- avoid expanding controller slices unless protecting route/response contracts,
- add minimal targeted integration coverage for high-value seams.

## 3. Layer Definitions and Quality Criteria

### `unit`

- Scope: one class (or tightly coupled helper pair) with mocked dependencies.
- Must assert domain outcomes, not only method invocation.
- Must include at least one edge-case assertion for non-trivial logic.
- Runtime expectation: milliseconds; no Spring context boot.

### `contract`

- Scope: behavior contract snapshots (route output shape, headers, model keys, cross-base rule parity).
- Must verify observable behavior that refactors must preserve.
- Avoid over-asserting internal implementation details.
- Runtime expectation: low; may use `@WebMvcTest` or plain JUnit depending on boundary.

### `slice`

- Scope: framework slice wiring (`@WebMvcTest`, selected Spring slices).
- Must validate request mapping/auth mapping/serialization-model behavior.
- Keep dependency surface narrow (`@MockBean` for external collaborators).
- Runtime expectation: moderate; avoid broad context loading.

### `integration`

- Scope: minimal Spring context and cross-bean seam checks.
- Must focus on wiring and critical cross-layer assumptions.
- Keep count low and stable; each test must justify added runtime.
- Runtime expectation: highest in this repository test stack.

### `e2e`

- Scope: full-system workflows.
- Only for top-risk production scenarios not defensible by lower layers.
- Each added E2E test requires a documented risk rationale in H04 gap matrix.

## 4. Assertion-Depth Guidelines

- Prefer value/structure assertions over interaction-only assertions.
- Interaction verification is acceptable for orchestration tests, but must include at least one meaningful output/state check when possible.
- For contracts, assert only externally visible behavior:
  - HTTP status, redirect, headers, view name, required model keys, workbook metadata.
- Avoid brittle assertions on ordering unless order is business-significant.

## 5. Flakiness and Stability Policy

- No nondeterministic data/time behavior without explicit control (fixed clocks, deterministic inputs).
- New flaky test rule:
  - if a test flakes twice in 7 days, quarantine or rewrite before adding further tests in that area.
- Quarantined tests must be tracked in H04 docs/tasks with owner and expected fix slice.
- Keep tests isolated: no shared mutable static state.

## 6. Runtime and Execution Policy

- Default local gate for refactor safety:
  - `npm run verify-h04-baseline`
- Mongo repository integration contract gate (Docker required):
  - `npm run verify-h04-mongo-integration`
- Broader baseline:
  - `./gradlew test`
- Asset/boundary checks remain mandatory:
  - `npm run verify-assets`
  - `npm run verify-template-assets`
  - `npm run verify-architecture-boundaries`

Target runtime posture for near-term H04:

- keep default full suite under ~10 seconds on current local baseline class.
- keep heavy suites visible and reviewed when new tests are added.
- apply soft-budget reporting via `scripts/verify-test-runtime.js` (warn/report, no hard fail).

## 7. Test Selection Rules for New Work

1. Add/modify `unit` tests first for pure logic and dispatch behavior.
2. Add/modify `contract` tests when behavior must remain stable across refactors.
3. Add `slice` tests only when request mapping/transport behavior is in scope.
4. Add `integration` tests only for wiring/seam risk not covered by lower layers.
5. Add `e2e` tests only with explicit high-risk justification.

## 8. H04-S03 Handoff

This strategy defines the decision rules for `H04-S03`:

- gap matrix entries must map each risk to a target layer selected using Section 7,
- P0 flows from H03 must have at least one stable regression signal at the lowest reliable layer.

## 9. S07 Closeout and H05 Handoff (2026-03-03)

- H04 is closed: inventory, strategy, gap matrix, hotspot tests, seam tests, and reliability guardrails are complete.
- H04 baseline source-of-truth:
  - `docs/h04-test-inventory.md`
  - `docs/h04-test-strategy.md`
  - `docs/h04-gap-matrix.md`
  - `docs/h04-reliability-guardrails.md`
  - `npm run verify-h04-baseline`
  - `npm run verify-h04-mongo-integration`
- Adoption rules:
  - run `npm run verify-h04-baseline` before and after refactors touching H03/H04 covered flows;
  - run `npm run verify-h04-mongo-integration` for repository query/data-access changes;
  - treat contract/runtime guard failures as change signals requiring explicit decision or follow-up.
- Forward link to H05:
  - use H04 guardrails as quality gates while standardizing frontend/template structures and shared UI behavior.
