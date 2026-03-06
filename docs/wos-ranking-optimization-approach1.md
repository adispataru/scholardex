# WoS Ranking Optimization - Approach 1 (Incremental In-Place Evolution)

## Summary
This approach keeps a single `wos.rankings` collection and evolves `WoSRanking` incrementally so AIS/RIS remain first-class, IF is retained as reference-only data sourced from official WoS extracted data, and JSON extracted data (`data/wos-json-1997-2019`) is merged into the same document model.

It is the lowest-risk migration path for existing code, but it keeps model complexity high and can limit long-term scalability.

## Target Architecture
- Keep `wos.rankings` as the primary store.
- Refactor `WoSRanking` document shape to normalized metric and category blocks (still in one document):
  - `metricsByYear`: `{ year: { ais, ris, ifFromOfficialWosExtract } }`
  - `categoriesByYear`: `{ year: { "CATEGORY": { aisQuarter, aisRank, risQuarter, risRank, ifQuarterFromOfficialWosExtract, ifRankFromOfficialWosExtract }}}`
  - `identity`: `{ primaryIssn, eIssn, aliasIssns[], normalizedTitle }`
  - `sourceCoverage`: `{ hasAisGov, hasRisGov, hasIfOfficialWosExtract, years[] }`
- Keep API contracts unchanged (`/api/rankings/wos`, report endpoints).

## Ingestion Strategy
- Remove operational JIF Excel branch from `RankingService.loadRankingsFromExcel`.
- Keep only AIS and RIS from government files as operational feed.
- Add an official WoS extracted JSON importer:
  - Input: `journals-SCIE-year-*.json`, `journals-SSCI-year-*.json`.
  - Parse `articleInfluenceScore` and `journalImpactFactor`.
  - Normalize sentinels (`-999` => missing/null).
  - Merge by deterministic identity strategy:
    - primary key: normalized ISSN/eISSN,
    - fallback: title normalization + alias linking,
    - record unresolved collisions in an audit table.
- IF from JSON is stored under official WoS extracted data fields only (`ifFromOfficialWosExtract`), not fed by current gov sources.

## Indicator.Strategy.IMPACT_FACTOR Handling
- Mark as deprecated in configuration/docs.
- Keep runtime fallback read-only against IF values from official WoS extracted data when present.
- No new operational data dependencies for IF.
- Add warning telemetry whenever strategy is used.

## DB Access Contract (Replacing Cache Heavy Reads)
- Replace `CacheService#getAllRankings()` usage in ranking/report query paths with targeted Mongo queries:
  - by ISSN/eISSN/aliases,
  - by category-year,
  - by latest-year summaries.
- Add indexes:
  - `identity.primaryIssn`, `identity.eIssn`, `identity.aliasIssns`, `normalizedTitle`.
  - selective indexes for latest-year query fields.
- Keep only a minimal in-memory hot cache for derived top-category counts if needed.

## Big-Bang Migration Plan
1. Freeze current WoS ingestion writes.
2. Full rebuild of `wos.rankings` from:
   - official WoS extracted JSON (SCIE/SSCI, 1997-2019),
   - existing AIS/RIS gov files.
3. Compute normalized fields and aliases.
4. Re-index.
5. Cut over reads to new query paths.
6. Keep old fallback backups for rollback.

## Testing and Validation
- Import correctness:
  - parse all JSON yearly files,
  - sentinel conversion (`-999` => missing),
  - AIS/RIS excel year-specific parsing checks.
- Data quality:
  - alias merge determinism,
  - category normalization consistency across years/editions.
- Performance:
  - `/api/rankings/wos` page/search latency under full dataset,
  - scoring lookup latency without `getAllRankings()` scans.
- Regression:
  - AIS/RIS report score parity against baseline.
  - deterministic IF fallback behavior when missing.
- Migration safety:
  - row-count reconciliation,
  - checksum/sample diffs,
  - rollback snapshot tested.

## Risks and Tradeoffs
- Pros:
  - Lowest change surface for code using `WoSRanking`.
  - Fastest path to remove JIF gov import dependency.
- Cons:
  - Continued document bloat and nested-map complexity.
  - Harder future evolution versus normalized facts/projections.

## Recommendation Position
Viable as a short-term stabilization path, but not ideal as target architecture if the goal is sustained cache elimination and easier long-term calculations.
