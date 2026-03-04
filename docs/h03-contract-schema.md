# H03-S02 Contract Schema

Date: 2026-03-03  
Status: active schema for H03 characterization artifacts.

## 1. Purpose

Define a single, reusable contract format for high-impact flows so behavior can be frozen before refactors.

This schema is used by:

- flow contracts (web/controller + facade + reporting behaviors),
- characterization test planning,
- regression gate mapping in H03-S06.

## 2. Contract Record Template (Required Fields)

Each contract entry must contain all fields below.

| Field | Required | Description |
|---|---|---|
| `Contract ID` | yes | Stable ID (`C-F01-01`, `C-F02-03`, etc.) |
| `Flow ID` | yes | Must reference `docs/h03-flow-priority-map.md` (`F01..`) |
| `Priority` | yes | `P0|P1|P2` (copied from flow map) |
| `Entrypoint` | yes | HTTP route/method or service method signature |
| `Auth assumptions` | yes | Principal required, role assumptions, unauthorized behavior |
| `Inputs` | yes | Path/query/body parameters + defaults + preconditions |
| `Output contract` | yes | View name / response status / headers / payload/workbook contract |
| `Side effects` | yes | Persistence, cache mutation, async triggers, file writes |
| `Invariants` | yes | Behavior rules that must always hold |
| `Known edge cases` | yes | Not-found, empty data, malformed inputs, missing auth |
| `Zone path` | yes | `Z1 -> Z2 -> Z3/Z4` execution path |
| `Current tests` | yes | Existing tests that partially/fully cover the contract |
| `Planned tests` | yes | Characterization tests to add in H03-S03/S04/S05 |
| `Owner` | yes | Responsible zone owner role (from H02 ownership) |

## 3. Optional Fields

Use when needed:

- `Non-goals`: clarify what is intentionally out of scope.
- `Observability hooks`: logs/metrics/events expected.
- `Data sensitivity`: PII/export controls relevant to the flow.

## 4. Contract Entry Format

Use this section structure for each entry:

```md
### Contract ID: C-Fxx-yy

- Flow ID: `Fxx`
- Priority: `P0|P1|P2`
- Entrypoint:
- Auth assumptions:
- Inputs:
- Output contract:
- Side effects:
- Invariants:
- Known edge cases:
- Zone path:
- Current tests:
- Planned tests:
- Owner:
- Non-goals: (optional)
- Observability hooks: (optional)
- Data sensitivity: (optional)
```

## 5. Invariant Authoring Rules

When writing `Invariants`:

1. Use testable statements, not intent statements.
2. State exact expected outcomes (status/view/header/model key/score behavior).
3. Prefer deterministic wording (`must`, `must not`) over soft wording (`should`).
4. Keep each invariant atomic so one failing assertion maps to one rule.

## 6. Coverage Mapping Rules

For `Current tests` and `Planned tests`:

- Reference concrete classes (and methods when possible).
- Mark one status per contract item:
  - `covered`
  - `partial`
  - `missing`
- A contract is considered baselined only when all required invariants are at least `partial`, and P0 contracts have no `missing` on core success/failure paths.

## 7. Acceptance Criteria for H03-S02

This schema is considered complete when:

1. All required fields are explicitly defined.
2. The format is stable enough to use directly in H03-S03/S04/S05 artifacts.
3. The schema aligns with H02 zone ownership and dependency boundaries.
