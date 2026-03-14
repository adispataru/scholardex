# Contracts

Status: active project contract summary.

## Identity And Source Families

- Supported canonical source families include `SCOPUS`, `WOS`, `GSCHOLAR`, and `USER_DEFINED`.
- Scholardex owns canonical identity for publication, forum, author, affiliation, and citation linkage.
- Source-specific records keep lineage authority; merged runtime projections are derived outputs.

## Ownership Rules

- Non-owned fields must not be destructively overwritten by lower-authority sources.
- Deterministic IDs and replay-safe upserts are required for source events, source links, and canonical facts.
- Ambiguous cross-source identity resolution must quarantine into conflicts rather than auto-merge.

## Runtime Contract Decisions

- `/api/rankings/wos` intentionally remains stable while reading from Postgres.
- Shared MVC route names are entity-first and stable.
- User-defined publication onboarding remains an in-place user flow under `/user/publications/add`.
- H21.1 lock (2026-03-14): wizard onboarding contract target source is `USER_DEFINED` with deterministic `USER_DEFINED:FORUM:*` and `USER_DEFINED:PUBLICATION:*` source record key shapes; contract source of truth is `docs/tasks/active/h21.1-user-defined-wizard-onboarding-contract.md`.

## Compatibility Rules

- Public API namespaces are preserved unless a task explicitly migrates them.
- Removed MVC aliases stay removed and should not be reintroduced as compatibility shims unless explicitly planned.
- Historical task docs may contain older contract details, but active implementation should follow the current project docs and open-task docs.
