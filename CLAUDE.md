# AGENTS.md

This file defines repository-local operating rules for Codex agents and contributors.

## Safety Rules

- Never run destructive git commands such as `git reset --hard` or `git checkout --` unless explicitly requested.
- Do not revert or modify unrelated local changes in a dirty worktree.
- Keep changes scoped to the requested task.

## Editing Rules

- Prefer minimal diffs.
- Preserve existing code style and structure unless asked to refactor.
- Avoid broad mechanical rewrites that are not required for the task.

## Testing Expectations

- Run targeted tests for touched areas whenever possible.
- If a test cannot be run, state this explicitly and explain why.
- For doc-only/config-only changes, run at least a project sanity check command when practical.

## Communication Expectations

- Provide concise progress updates while working.
- Call out assumptions explicitly when requirements are ambiguous.
- Summarize what changed and what was validated in the final handoff.

## Task And Documentation Discovery

- Active high-level backlog items live in `TASKS.md`.
- Completed tasks and handoff history live in `TASKS-done.md`.
- Task-specific docs for open work live in `docs/tasks/active/`.
- Task-specific docs for archived work live in `docs/tasks/closed/`.
- Durable project references live in top-level `docs/`.

Search order:
- If the user references an open `Hxx`, start with `TASKS.md`, then open the matching doc under `docs/tasks/active/` if it exists.
- If the user asks whether work is already done, superseded, or historically covered, check `TASKS-done.md` before searching task docs.
- Use top-level `docs/` as the default documentation search surface for current architecture, contracts, workflows, operations, and contributor guidance.
- Use `docs/tasks/active/` only when task-specific planning or implementation detail is needed for an open task.
- Use `docs/tasks/closed/` intentionally for implementation history, contract evidence, or closeout notes when current code or top-level docs do not answer the question.

Search heuristics:
- For implementation work: check code first, then `TASKS.md`, then top-level `docs/`, then task docs if needed.
- For planning or backlog work: check `TASKS.md` first, then relevant docs in `docs/tasks/active/`, then top-level `docs/`.
- For cleanup or closure assessment: check `TASKS.md`, `TASKS-done.md`, code/tests, then task docs as supporting evidence.

Noise-reduction rules:
- Do not start with `docs/tasks/closed/` for current behavior unless the request is explicitly historical or current code/top-level docs leave a gap.
- Do not treat closed task docs as the current source of truth when a top-level project doc exists.
- Use task ids from `TASKS.md` or `TASKS-done.md`, not filename pattern alone, to classify a doc as task history versus project guidance.
- When current code and top-level docs disagree with a closed task doc, prefer code plus current top-level docs unless the user is explicitly asking for history or handoff evidence.
- When creating task-specific docs, place them under `docs/tasks/active/` while the task is open and move them to `docs/tasks/closed/` when the task is archived.

## Branching And Commits

- Branch naming convention for agent work: `codex/*`
- Use Conventional Commits:
  - `feat: ...` for new functionality
  - `fix: ...` for bug fixes
  - `docs: ...` for documentation-only changes
  - `chore: ...` for maintenance/config updates
