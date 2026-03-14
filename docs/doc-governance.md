# Documentation Governance

Status: active documentation governance baseline.

## Structure Rules

- Top-level `docs/` is for durable project docs only.
- `docs/tasks/active/` is for docs tied to active tasks in `TASKS.md`.
- `docs/tasks/closed/` is for docs tied to archived tasks in `TASKS-done.md`.
- `docs/archive/` is for non-task historical or exploratory material.

## Update Triggers

Update docs in the same PR when:

1. public or internal route contracts change,
2. source-of-truth architecture or contract decisions change,
3. verification commands or CI gate contracts change,
4. startup/readiness/operability expectations change,
5. the docs tree structure or classification rules change.

## Placement Rule

- If a doc exists because of a task id in `TASKS.md` or `TASKS-done.md`, it belongs under `docs/tasks/...`.
- If it explains the steady-state project for contributors or operators, it belongs at top level.

## Verification

- Top-level project docs must be indexed in `docs/README.md`.
- Task-derived docs must not live at top level.
- Use `npm run verify-docs-governance` after doc-tree changes.
