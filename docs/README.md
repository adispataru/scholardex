# Documentation Index

Status: active project documentation index.

## Purpose

This directory now separates durable project docs from task-specific history.

- Top-level `docs/` files are project-facing references.
- `docs/tasks/active/` contains docs for tasks still open in `TASKS.md`.
- `docs/tasks/closed/` contains docs for tasks archived in `TASKS-done.md`.
- `docs/archive/` contains non-task historical or exploratory material.

## Project Docs

- `docs/architecture.md`
- `docs/contracts.md`
- `docs/workflows.md`
- `docs/operational-playbook.md`
- `docs/frontend-conventions.md`
- `docs/quality-gates.md`
- `docs/failure-triage.md`
- `docs/doc-governance.md`
- `docs/release-hygiene.md`
- `docs/c01-cnfis-rule-spec.md`

## Placement Rules

- Put new top-level docs here only when they describe durable project architecture, runtime contracts, workflows, or contributor operations.
- Put task-scoped design, findings, closeout notes, and migration detail under `docs/tasks/active/` or `docs/tasks/closed/`.
- Put abandoned proposals or non-task historical context under `docs/archive/`.

When in doubt, prefer `docs/tasks/...` over creating a new task-named top-level file.
