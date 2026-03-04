# H10 Documentation Governance and Ownership

Date: 2026-03-04  
Status: active governance baseline for contributor and operational docs.

## 1. Purpose

Prevent documentation drift by defining ownership, update triggers, and lightweight review cadence for core project docs.

## 2. Ownership Model

Repository-level ownership follows `CODEOWNERS` (`@adispataru`).  
Operational role mapping used in documentation:

1. `Platform Backend Owner` - runtime/setup/docs that reflect app boot and backend contracts.
2. `App Architecture Owner` - architecture boundaries, layering contracts, contributor workflow policy.
3. `Quality Owner` - guardrails, test/reliability policy, CI quality checks.
4. `Release Owner` - release hygiene, rollback/evidence policy.

For this repository, all roles currently map to `@adispataru` unless reassigned.

## 3. Documentation Ownership Table

| Document | Primary owner role | Update trigger(s) |
|---|---|---|
| `README.md` | Platform Backend Owner | local run/setup/config behavior changes, new required prerequisites, actuator/public probe contract changes |
| `CONTRIBUTING.md` | App Architecture Owner | branch/commit workflow changes, PR expectations changes, local verification baseline changes |
| `docs/h10-quality-gates-matrix.md` | Quality Owner | new guardrail command, removed command, command contract changes |
| `docs/h10-failure-triage.md` | Quality Owner | new recurring CI/guardrail failure class, changed error message/pattern, new required CI job |
| `docs/h10-release-hygiene.md` | Release Owner | required checks set changes, rollback policy changes, merge gate policy changes |
| `docs/h09-ci-gates.md` | Quality Owner | workflow/job/required-check changes in `.github/workflows/**` |
| `docs/h02-dependency-rules.md` | App Architecture Owner | dependency policy changes, allow/deny matrix updates, new architectural enforcement rules |

## 4. Mandatory Update Triggers

Update affected docs in the same PR when any of the following occur:

1. `package.json` script contract changes for any `verify-*` command.
2. `.github/workflows/*.yml` job names, required checks, or gate semantics change.
3. Security/auth contract changes (login/auth failure behavior, CSRF policy, privileged route scope).
4. Architecture boundary rules or enforcement scripts change.
5. Startup/readiness/health endpoint exposure contract changes.
6. Contributor workflow/checklist expectations change.

## 5. Review Cadence

1. Per-PR: reviewer confirms docs impact for contract/workflow changes.
2. Weekly quick scan (lightweight):
   - validate that `CONTRIBUTING.md` commands still exist in `package.json`,
   - validate required-check names still match `docs/h09-ci-gates.md`,
   - validate H10 docs cross-links remain valid.
3. Release boundary check:
   - verify release-affecting PRs include rollback + evidence notes.

## 6. PR Checklist Add-On

When behavior/contracts/workflows change, PR must include:

1. list of docs updated,
2. why each update was required,
3. confirmation that stale sections were removed (not only appended).

## 7. Exit Condition for H10-S07

H10-S07 is complete when:

1. docs ownership and triggers are explicitly documented,
2. contributor workflow points to this governance policy,
3. `TASKS.md` is updated with completion evidence.
