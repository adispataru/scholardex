# Architecture

Status: active project architecture summary.

## Current Shape

- Mongo remains the canonical ingest, fact, queue, and event store.
- PostgreSQL serves the reporting/read-model side for migrated reporting and ranking flows.
- Scholardex is the canonical identity layer for publications, forums, authors, affiliations, and cross-source linkage.

## Core Boundaries

- Ingestion and canonical materialization live behind application services and canonical contracts.
- Runtime MVC/API reads prefer projection-backed or Postgres-backed read ports where cutovers have landed.
- Admin flows operate maintenance, initialization, conflict resolution, and source-link triage.
- User flows cover dashboard, publications, activities, reports, exports, and user-driven onboarding/import paths.

## Persistence And Read Decisions

- Source-specific events and raw imports remain lineage-authoritative inputs.
- Canonical facts and source links define cross-source identity and ownership.
- Projection rebuilds are deterministic and replay-safe.
- Postgres reporting tables/materialized views support reporting-heavy and cutover read paths.

## Canonical Route Families

- Shared authenticated reads: `/forums`, `/wos/categories`, `/core/rankings`, `/universities`, `/events`
- User-owned routes: `/user/dashboard`, `/user/publications`, `/user/activities`, `/user/individual-reports`, `/user/exports/cnfis`
- Admin-only management remains under `/admin/**`

## Reference Layer

- Detailed task history and migration decisions live under `docs/tasks/closed/`.
- Open task design docs, when present, live under `docs/tasks/active/`.
