# Contributing

## End-to-End Workflow

1. Sync local `main` and create a focused branch (`codex/<topic>`).
2. Implement a scoped change (avoid unrelated edits).
3. Run baseline checks for your change type (see `Verification by Change Type`).
4. Open PR into `main` with:
   - concise change summary,
   - commands run + outcomes,
   - explicit risks/assumptions.
5. Resolve review feedback and rerun affected checks.
6. Merge only when required CI checks are green.

## Branching

- Use `codex/*` branch names for agent-driven work.
- Open pull requests into `main`.

## Commit Messages

This repository uses Conventional Commits.

Examples:

- `feat: add researcher filtering endpoint`
- `fix: handle null forum ranking safely`
- `docs: document local setup and test commands`
- `chore: add editor and git attributes baseline`

## Local Verification

Minimum baseline before opening a PR:

```bash
npm run build
npm run verify-assets
npm run verify-template-assets
npm run verify-duplication-guardrails
npm run verify-architecture-boundaries
./gradlew test
```

For CI parity on build/test/security-sensitive changes:

```bash
npm run verify-h09-baseline
```

For failure diagnosis paths, use `docs/h10-failure-triage.md`.
For release-affecting change hygiene, use `docs/h10-release-hygiene.md`.
For documentation ownership/update triggers, use `docs/h10-doc-governance.md`.

If your change affects startup/config wiring, also verify boot task resolution:

```bash
./gradlew bootRun -m
```

## Verification by Change Type

1. Refactor with behavior-preservation risk:

```bash
npm run verify-h03-baseline
npm run verify-h04-baseline
```

2. Repository query/data-access changes:

```bash
npm run verify-h04-mongo-integration
npm run verify-h06-persistence
```

3. Security/validation/error-handling changes:

```bash
npm run verify-h07-guardrails
```

4. Observability/operability changes:

```bash
npm run verify-h08-baseline
```

5. CI/quality-gate workflow changes:

```bash
npm run verify-h09-baseline
```

If multiple categories apply, run the union of commands.
See `docs/h10-quality-gates-matrix.md` for the canonical change-type command map.

## Guardrail Expectations

When editing templates, do not add new `/vendor/*` asset references. Use `/assets/app.css` and `/assets/app.js`.
Do not commit backup templates (`*-bak.html`) under `src/main/resources/templates/**`; checks will fail.
Do not reintroduce removed legacy CNFIS artifacts (`CNFISScoringService`, `CNFISReport`) or null-fallback scoring dispatch; `verify-duplication-guardrails` enforces these guardrails.
Do not add new controller/view imports from `core.repository` or `core.service.reporting`; `verify-architecture-boundaries` enforces these guardrails.
Do not introduce new mutating `GET` routes, transport-layer `printStackTrace`, or unsafe `start/end` year parsing in controllers; `verify-h07-guardrails` enforces debt-aware guardrails.

## Pull Request Checklist

- Scope is focused and does not include unrelated changes.
- Tests/checks were run and results are described.
- Risks or assumptions are listed when relevant.
- Docs are updated when behavior, commands, or contracts changed.
- For release-affecting changes, include rollback note + verification evidence per `docs/h10-release-hygiene.md`.
