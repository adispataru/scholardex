# Precious config snapshot (H54.1)

Git-tracked JSON snapshot of this app's **config-class precious collections** — definitions,
catalog, and org structure that are human/operationally authored and **not reconstructable**
from the ingestion pipeline. See [`docs/data-ownership-inventory.md`](../../docs/data-ownership-inventory.md)
for the full ownership classification and the precious-vs-derived rationale.

This directory deliberately contains **no personal data**. The PII subset of the precious
layer (user accounts, authorship decisions, activity instances, report runs, memberships)
is dumped separately to a git-ignored location under `data/backups/precious-pii-<timestamp>/`.

## Contents

Each `<collection>.json` is a JSON array of the collection's documents in canonical extended
JSON, sorted by `_id` for stable, reviewable diffs.

| File | Collection | Kind |
|---|---|---|
| `indicators.json` | `indicators` | indicator definitions |
| `individualReports.json` | `individualReports` | report definitions |
| `groupReports.json` | `groupReports` | report definitions |
| `scholardex.groups.json` | `scholardex.groups` | evaluation groups |
| `institutions.json` | `institutions` | seed |
| `domains.json` | `domains` | seed |
| `activities.json` | `activities` | admin activity-type catalog |
| `scholardex.artisticEvent.json` | `scholardex.artisticEvent` | admin artistic-event catalog |
| `scholardex.departments.json` | `scholardex.departments` | org structure |
| `scholardex.org_divisions.json` | `scholardex.org_divisions` | org structure |
| `scholardex.division_report_selections.json` | … | org reporting config |
| `scholardex.department_report_hides.json` | … | org reporting config |

## Regenerate

From the repo root, against the target DB:

```bash
mongosh "mongodb://localhost:27017/test" scripts/h54-1-snapshot-precious.js
```

This rewrites every file here deterministically and writes the PII subset to the git-ignored
`data/backups/` location. Commit the resulting diff under `seed/precious-config/` only.

## Restore (config)

```bash
# per collection
mongoimport --uri "mongodb://localhost:27017/test" --collection indicators \
  --jsonArray --mode upsert --file seed/precious-config/indicators.json
```

(or an equivalent `mongosh` loop using `EJSON.parse` + `insertMany`/upsert).
PII restore uses the local `data/backups/precious-pii-<timestamp>/*.jsonl` dumps.
