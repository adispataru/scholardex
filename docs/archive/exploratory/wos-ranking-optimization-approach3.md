# WoS Ranking Optimization - Approach 3 (Immutable Ingestion Ledger + Rebuildable Views)
Status: exploratory archived proposal; retained for context only.

## Summary
This approach introduces an immutable ingestion ledger and rebuildable materialized views. It is the most robust for auditability and future policy shifts, while still preserving AIS/RIS as operational and IF as reference-only data sourced from official WoS extracted JSON.

It is the most ambitious option and requires the largest implementation effort.

## Target Architecture
- Immutable raw ingestion store:
  - `wos.import_events`
    - metadata (`sourceType`, `sourceFile`, `version`, checksum, ingestedAt),
    - raw payload chunks for replay.
- Canonical fact tables derived from events:
  - `wos.metric_facts` (`journalId`, `year`, `metricType`, `value`, lineage refs).
  - `wos.category_facts` (`journalId`, `year`, `category`, `metricType`, `quarter`, `rank`, lineage refs).
- Identity registry:
  - `wos.journal_identity` with ISSN/eISSN aliases and merge/split lineage.
- Rebuildable read models:
  - `wos.ranking_view`,
  - `wos.scoring_view`,
  - optional `wos.quality_view` for ingestion anomalies.

## Ingestion Strategy
- Remove JIF gov ingestion from operational pipeline.
- Operational ingestion:
  - AIS/RIS gov files only.
- Official WoS extracted data ingestion:
  - JSON replay from `wos-json-1997-2019` (SCIE/SSCI) for AIS and IF facts.
- Normalize sentinels (`-999`) and track discarded records with reason codes.
- All transformations are deterministic and replayable from `import_events`.

## Indicator.Strategy.IMPACT_FACTOR Handling
- Kept as official WoS extracted data-only strategy backed by fact lineage.
- Explicit policy gate in scoring lookup:
  - IF allowed only from official WoS extracted data source facts.
- Strong deprecation path with audit visibility (usage + source provenance).

## DB Access Contract (CacheService Replacement)
- All runtime reads use materialized views/facts; `CacheService` is not used as primary data access.
- `CacheService` may remain as optional edge cache for computed aggregates only.
- Query design centered on indexed views:
  - view search for ranking API,
  - category-year lookup for reporting.

## Big-Bang Migration Plan
1. Build event schema + parser adapters (JSON + AIS/RIS Excel).
2. Import all official WoS extracted/current sources into `import_events`.
3. Run deterministic transformation jobs to produce facts and views.
4. Validate parity/performance.
5. Cut over runtime reads to views.
6. Keep replay tooling for future rebuilds and corrections.

## Testing and Validation
- Import correctness:
  - full replay consistency,
  - sentinel normalization and discard reason accounting.
- Data quality:
  - identity merge lineage integrity,
  - category normalization consistency.
- Performance:
  - projection rebuild time budgets,
  - API/report query latencies from views.
- Regression:
  - AIS/RIS scoring parity,
  - IF fallback determinism from official WoS extracted data.
- Migration safety:
  - replay determinism checks,
  - end-to-end reconciliation and rollback snapshots.

## Risks and Tradeoffs
- Pros:
  - Best auditability and reproducibility.
  - Simplifies future reprocessing and policy changes.
  - Strongest long-term foundation for cache decommissioning.
- Cons:
  - Highest engineering and operational complexity.
  - Requires pipeline orchestration, monitoring, and replay ops.

## Recommendation Position
Best long-term architecture if the team can absorb significant implementation and operational complexity; otherwise Approach 2 is the pragmatic default.
