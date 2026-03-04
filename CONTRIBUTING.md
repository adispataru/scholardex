# Contributing

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

Before opening a PR, run:

```bash
npm run build
npm run verify-assets
npm run verify-template-assets
npm run verify-duplication-guardrails
npm run verify-architecture-boundaries
./gradlew test
```

For H03 contract-safety baseline checks before refactors, run:

```bash
npm run verify-h03-baseline
```

For H04 reliability/runtime guardrails, run:

```bash
npm run verify-h04-baseline
```

Before merging repository query/data-access changes, also run:

```bash
npm run verify-h04-mongo-integration
```

For H07 security/validation/error hardening changes, also run:

```bash
npm run verify-h07-guardrails
```

For H08 observability/operability hardening changes, also run:

```bash
npm run verify-h08-baseline
```

If your change affects startup/config wiring, also verify boot task resolution:

```bash
./gradlew bootRun -m
```

When editing templates, do not add new `/vendor/*` asset references. Use `/assets/app.css` and `/assets/app.js`.
Do not commit backup templates (`*-bak.html`) under `src/main/resources/templates/**`; checks will fail.
Do not reintroduce removed legacy CNFIS artifacts (`CNFISScoringService`, `CNFISReport`) or null-fallback scoring dispatch; `verify-duplication-guardrails` enforces these guardrails.
Do not add new controller/view imports from `core.repository` or `core.service.reporting`; `verify-architecture-boundaries` enforces these guardrails (existing debt remains allowlisted until remediated).
Do not introduce new mutating `GET` routes, transport-layer `printStackTrace`, or unsafe `start/end` year parsing in controllers; `verify-h07-guardrails` enforces debt-aware guardrails.

## Pull Request Checklist

- Scope is focused and does not include unrelated changes.
- Tests/checks were run and results are described.
- Risks or assumptions are listed when relevant.
