# Release Hygiene

Status: active release and merge hygiene baseline.

## Before Merge

- Run the relevant validation union for the touched change types.
- Record any intentionally skipped checks with a reason.
- Update project docs or task docs in the same PR when contracts or workflow expectations changed.

## For Release-Affecting Changes

- Include rollback or recovery notes when behavior, schema, or read/write ownership changes.
- Preserve public route and API compatibility unless an explicit migration task says otherwise.
- Ensure cutover-sensitive work has deterministic validation evidence.

## Documentation Rule

- Keep general release expectations here.
- Keep task-specific rollout evidence and closeout details in `TASKS-done.md` and `docs/tasks/closed/`.
