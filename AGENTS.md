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

## Branching And Commits

- Branch naming convention for agent work: `codex/*`
- Use Conventional Commits:
  - `feat: ...` for new functionality
  - `fix: ...` for bug fixes
  - `docs: ...` for documentation-only changes
  - `chore: ...` for maintenance/config updates
